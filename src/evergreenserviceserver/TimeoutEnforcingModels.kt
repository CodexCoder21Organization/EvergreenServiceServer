package evergreenserviceserver

import community.kotlin.clocks.simple.Clock
import community.kotlin.clocks.simple.SystemClock
import photogenerationmanager.api.GeneratedImage
import photogenerationmanager.api.GenerationState
import photogenerationmanager.api.ImageGenerationModel
import photogenerationmanager.api.ImageGenerationStatus
import photogenerationmanager.api.PromptGenerationModel
import photogenerationmanager.api.PromptGenerationStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * The maximum wall-clock time a single generation may stay `PENDING` before the server gives up on it
 * and reports `ERROR`. Five minutes: a generation that has not produced a result by then is treated as
 * stuck (e.g. a backing model that never returns), so a consumer polling its status is never left
 * waiting forever. Enforced here, on the server, so the limit holds no matter which consumer is polling.
 */
const val DEFAULT_GENERATION_TIMEOUT_MS: Long = 5 * 60 * 1000L

/**
 * Server-side [ImageGenerationStatus] used to report an enforcer-induced ERROR (timeout or cancel).
 * The backing model's own statuses are returned unchanged; only the overriding ERROR is built here.
 */
internal class ServerImageGenerationStatus(
    override val state: GenerationState,
    override val image: GeneratedImage?,
    override val error: String?
) : ImageGenerationStatus

/** Server-side [PromptGenerationStatus] counterpart of [ServerImageGenerationStatus]. */
internal class ServerPromptGenerationStatus(
    override val state: GenerationState,
    override val prompt: String?,
    override val error: String?
) : PromptGenerationStatus

/**
 * Tracks the lifecycle of each generation id a wrapping model has started, and decides — from an
 * injected [Clock] — when a still-`PENDING` generation should be reported as `ERROR` instead, because
 * it either timed out or was cancelled. This is the shared core of [TimeoutEnforcingImageModel] and
 * [TimeoutEnforcingPromptModel]; the [Clock] is what makes the timeout verifiable in a fast unit test
 * with a `ManualClock`.
 *
 * A generation is recorded with [onRequested] (its start time is read from the clock), can be
 * [cancel]led while pending, and its effective override (if any) is computed by [overrideError] from
 * the backing model's current state.
 */
internal class GenerationLifecycleEnforcer(
    private val clock: Clock,
    private val timeoutMs: Long
) {
    private val startMillis = ConcurrentHashMap<String, Long>()
    private val cancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Records that generation [id] has started, stamping its start time from the clock. */
    fun onRequested(id: String) {
        startMillis[id] = clock.currentTimeMillis()
    }

    /**
     * Marks [id] cancelled, but only if it is tracked and would currently be reported as `PENDING`
     * (per [delegateState]) — a generation that is unknown, already finished, already cancelled, or
     * already timed out cannot be cancelled.
     *
     * @return `true` iff this call newly cancelled a still-pending generation.
     */
    fun cancel(id: String, delegateState: GenerationState?): Boolean {
        if (!startMillis.containsKey(id)) return false      // unknown id
        if (id in cancelled) return false                   // already cancelled
        if (overrideError(id, delegateState) != null) return false  // already timed out (or cancelled)
        if (delegateState != GenerationState.PENDING) return false  // already DONE/ERROR — nothing to cancel
        cancelled.add(id)
        return true
    }

    /**
     * Returns the ERROR message that must override the backing model's [delegateState] for [id], or
     * `null` if the backing model's own status should stand. A cancelled generation always reports
     * its cancellation (cancel is sticky — it wins even over a later backing-model result, which the
     * consumer has stopped polling for); otherwise a generation still `PENDING` past the deadline
     * reports the timeout.
     */
    fun overrideError(id: String, delegateState: GenerationState?): String? {
        if (id in cancelled) return "Generation '$id' was cancelled."
        if (delegateState == GenerationState.PENDING && timedOut(id)) {
            return "Generation '$id' timed out after ${timeoutMs / 1000} seconds without completing."
        }
        return null
    }

    private fun timedOut(id: String): Boolean {
        val start = startMillis[id] ?: return false
        return clock.currentTimeMillis() - start >= timeoutMs
    }
}

/**
 * Wraps a backing async [ImageGenerationModel] (the Embedded) to enforce a server-side generation
 * [timeoutMs] and support cancellation, both driven by an injected [clock]:
 *
 *  - [requestImageGeneration] delegates and remembers when the generation started.
 *  - [imageGenerationStatus] returns the backing status unchanged, except a still-`PENDING` generation
 *    that has been cancelled or has exceeded the timeout is reported as `ERROR` with an explanatory
 *    message. A real `DONE`/`ERROR` from the backing model is never masked (except by a prior cancel).
 *  - [cancelImageGeneration] cancels a still-pending generation.
 *
 * The default [clock] is [SystemClock] and the default [timeoutMs] is [DEFAULT_GENERATION_TIMEOUT_MS]
 * (5 minutes); tests inject a `ManualClock` and a short timeout to verify the behaviour instantly.
 */
class TimeoutEnforcingImageModel(
    private val delegate: ImageGenerationModel,
    clock: Clock = SystemClock(),
    timeoutMs: Long = DEFAULT_GENERATION_TIMEOUT_MS
) : ImageGenerationModel, AutoCloseable {

    private val lifecycle = GenerationLifecycleEnforcer(clock, timeoutMs)

    override fun requestImageGeneration(prompt: String, inputImages: List<ByteArray>): String {
        val id = delegate.requestImageGeneration(prompt, inputImages)
        lifecycle.onRequested(id)
        return id
    }

    override fun imageGenerationStatus(generationId: String): ImageGenerationStatus {
        val status = delegate.imageGenerationStatus(generationId)  // throws for an unknown id (unchanged contract)
        val override = lifecycle.overrideError(generationId, status.state) ?: return status
        return ServerImageGenerationStatus(GenerationState.ERROR, null, override)
    }

    override fun cancelImageGeneration(generationId: String): Boolean {
        // The backing state decides whether there is still anything to cancel; an unknown id is "not pending".
        val state = try { delegate.imageGenerationStatus(generationId).state } catch (e: Exception) { null }
        return lifecycle.cancel(generationId, state)
    }

    override fun close() {
        (delegate as? AutoCloseable)?.close()
    }
}

/**
 * Prompt-side counterpart of [TimeoutEnforcingImageModel]: enforces the same server-side timeout and
 * cancellation around a backing [PromptGenerationModel], driven by an injected [clock].
 */
class TimeoutEnforcingPromptModel(
    private val delegate: PromptGenerationModel,
    clock: Clock = SystemClock(),
    timeoutMs: Long = DEFAULT_GENERATION_TIMEOUT_MS
) : PromptGenerationModel, AutoCloseable {

    private val lifecycle = GenerationLifecycleEnforcer(clock, timeoutMs)

    override fun requestPromptGeneration(prompt: String, inputImages: List<ByteArray>): String {
        val id = delegate.requestPromptGeneration(prompt, inputImages)
        lifecycle.onRequested(id)
        return id
    }

    override fun promptGenerationStatus(generationId: String): PromptGenerationStatus {
        val status = delegate.promptGenerationStatus(generationId)  // throws for an unknown id (unchanged contract)
        val override = lifecycle.overrideError(generationId, status.state) ?: return status
        return ServerPromptGenerationStatus(GenerationState.ERROR, null, override)
    }

    override fun cancelPromptGeneration(generationId: String): Boolean {
        val state = try { delegate.promptGenerationStatus(generationId).state } catch (e: Exception) { null }
        return lifecycle.cancel(generationId, state)
    }

    override fun close() {
        (delegate as? AutoCloseable)?.close()
    }
}
