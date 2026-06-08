@file:WithArtifact("evergreenserviceserver.buildMaven()")
@file:WithArtifact("photogenerationmanager.api:photo-generation-manager-api:0.0.4")
@file:WithArtifact("community.kotlin.clocks.simple:community-kotlin-clocks-simple:0.0.3")
@file:WithArtifact("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
@file:WithArtifact("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.22")
@file:WithArtifact("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.22")
@file:WithArtifact("junit:junit:4.13.2")
package evergreenserviceserver

import build.kotlin.withartifact.WithArtifact
import community.kotlin.clocks.simple.ManualClock
import org.junit.Assert.*
import org.junit.Test
import photogenerationmanager.api.GeneratedImage
import photogenerationmanager.api.GenerationState
import photogenerationmanager.api.ImageGenerationModel
import photogenerationmanager.api.ImageGenerationStatus
import photogenerationmanager.api.PhotoGenerationException
import photogenerationmanager.api.PromptGenerationModel
import photogenerationmanager.api.PromptGenerationStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for the server-side generation timeout + cancellation enforcement
 * ([TimeoutEnforcingImageModel] / [TimeoutEnforcingPromptModel] over [GenerationLifecycleEnforcer]),
 * driven entirely by a [ManualClock] so the 5-minute timeout is verified instantly — no real waiting.
 *
 * Each test stands up an in-memory backing model whose per-id status the test controls directly (the
 * `states` map). The backing model never times anything out itself; all timeout/cancel behaviour under
 * test belongs to the wrapping enforcer. Five minutes is `5 * 60 * 1000L` ms (= 300 s).
 *
 * (kompile test scripts allow only top-level functions — no top-level `val`/`const`, and typed
 * factories must be local funs — so the status factories are passed into each `body` lambda.)
 */

/**
 * Stands up a [TimeoutEnforcingImageModel] over a controllable in-memory backing model with the given
 * [timeoutMs] and a [ManualClock] starting at [clockStartMs], then hands [body] the clock, the
 * enforcer, the mutable `states` map (id -> backing status), and two factories — `mkStatus` to build a
 * backing status and `mkDoneImage` to build a produced image — so a test can flip a generation to
 * DONE/ERROR mid-flight.
 */
private fun withImageEnforcer(
    timeoutMs: Long,
    clockStartMs: Long,
    body: (
        clock: ManualClock,
        enforcer: TimeoutEnforcingImageModel,
        states: ConcurrentHashMap<String, ImageGenerationStatus>,
        mkStatus: (GenerationState, GeneratedImage?, String?) -> ImageGenerationStatus,
        mkDoneImage: () -> GeneratedImage
    ) -> Unit
) {
    fun mkStatus(state: GenerationState, image: GeneratedImage?, error: String?): ImageGenerationStatus =
        object : ImageGenerationStatus {
            override val state = state
            override val image = image
            override val error = error
        }
    fun mkDoneImage(): GeneratedImage = object : GeneratedImage {
        override val imageBytes = byteArrayOf(1, 2, 3, 4)
        override val contentType = "image/png"
        override val url = "url://img/done"
    }
    val clock = ManualClock(clockStartMs)
    val states = ConcurrentHashMap<String, ImageGenerationStatus>()
    val ids = AtomicInteger(0)
    val backing = object : ImageGenerationModel {
        override fun requestImageGeneration(prompt: String, inputImages: List<ByteArray>): String {
            val id = "img-${ids.incrementAndGet()}"
            states[id] = mkStatus(GenerationState.PENDING, null, null)
            return id
        }
        override fun imageGenerationStatus(generationId: String): ImageGenerationStatus =
            states[generationId] ?: throw PhotoGenerationException("Unknown generation id: $generationId")
    }
    body(clock, TimeoutEnforcingImageModel(backing, clock, timeoutMs), states, ::mkStatus, ::mkDoneImage)
}

/** Prompt-side counterpart of [withImageEnforcer]. */
private fun withPromptEnforcer(
    timeoutMs: Long,
    clockStartMs: Long,
    body: (
        clock: ManualClock,
        enforcer: TimeoutEnforcingPromptModel,
        states: ConcurrentHashMap<String, PromptGenerationStatus>,
        mkStatus: (GenerationState, String?, String?) -> PromptGenerationStatus
    ) -> Unit
) {
    fun mkStatus(state: GenerationState, prompt: String?, error: String?): PromptGenerationStatus =
        object : PromptGenerationStatus {
            override val state = state
            override val prompt = prompt
            override val error = error
        }
    val clock = ManualClock(clockStartMs)
    val states = ConcurrentHashMap<String, PromptGenerationStatus>()
    val ids = AtomicInteger(0)
    val backing = object : PromptGenerationModel {
        override fun requestPromptGeneration(prompt: String, inputImages: List<ByteArray>): String {
            val id = "prompt-${ids.incrementAndGet()}"
            states[id] = mkStatus(GenerationState.PENDING, null, null)
            return id
        }
        override fun promptGenerationStatus(generationId: String): PromptGenerationStatus =
            states[generationId] ?: throw PhotoGenerationException("Unknown generation id: $generationId")
    }
    body(clock, TimeoutEnforcingPromptModel(backing, clock, timeoutMs), states, ::mkStatus)
}

// ---- Timeout (image) --------------------------------------------------------

/** A generation still pending one millisecond before the deadline is reported PENDING (not timed out). */
@Test
fun testImagePendingJustBeforeTimeoutStaysPending() {
    withImageEnforcer(5 * 60 * 1000L, clockStartMs = 1_000L) { clock, enforcer, _, _, _ ->
        val id = enforcer.requestImageGeneration("a cat")
        clock.advanceBy(5 * 60 * 1000L - 1)
        assertEquals("just before the deadline a pending generation stays PENDING",
            GenerationState.PENDING, enforcer.imageGenerationStatus(id).state)
    }
}

/** At exactly the 5-minute deadline a still-pending generation becomes ERROR with a timeout message. */
@Test
fun testImagePendingAtDeadlineBecomesTimeoutError() {
    withImageEnforcer(5 * 60 * 1000L, clockStartMs = 1_000L) { clock, enforcer, _, _, _ ->
        val id = enforcer.requestImageGeneration("a cat")
        clock.advanceBy(5 * 60 * 1000L)
        val status = enforcer.imageGenerationStatus(id)
        assertEquals("at the deadline a pending generation must be ERROR", GenerationState.ERROR, status.state)
        assertNull("a timed-out generation must not carry an image", status.image)
        assertEquals("Generation '$id' timed out after 300 seconds without completing.", status.error)
    }
}

/** Well past the deadline it remains a timeout ERROR (and the message reflects the configured timeout). */
@Test
fun testImagePendingPastDeadlineStaysTimeoutError() {
    withImageEnforcer(5 * 60 * 1000L, clockStartMs = 0L) { clock, enforcer, _, _, _ ->
        val id = enforcer.requestImageGeneration("a cat")
        clock.advanceBy(5 * 60 * 1000L * 4)
        assertEquals("Generation '$id' timed out after 300 seconds without completing.",
            enforcer.imageGenerationStatus(id).error)
    }
}

/** A generation that completes before the deadline is DONE — and stays DONE even after the deadline passes. */
@Test
fun testImageDoneBeforeDeadlineIsNeverMaskedByTimeout() {
    withImageEnforcer(5 * 60 * 1000L, clockStartMs = 0L) { clock, enforcer, states, mkStatus, mkDoneImage ->
        val id = enforcer.requestImageGeneration("a cat")
        clock.advanceBy(60_000L)
        states[id] = mkStatus(GenerationState.DONE, mkDoneImage(), null) // backing model finished at 1 minute
        assertEquals("a completed generation is DONE before the deadline", GenerationState.DONE, enforcer.imageGenerationStatus(id).state)
        clock.advanceBy(5 * 60 * 1000L * 2) // long past the deadline
        val status = enforcer.imageGenerationStatus(id)
        assertEquals("a real DONE result must never be masked by the timeout", GenerationState.DONE, status.state)
        assertNotNull("the produced image must survive", status.image)
    }
}

/** A backing ERROR before the deadline is surfaced verbatim (the enforcer does not rewrite it). */
@Test
fun testImageBackingErrorIsSurfacedVerbatim() {
    withImageEnforcer(5 * 60 * 1000L, clockStartMs = 0L) { _, enforcer, states, mkStatus, _ ->
        val id = enforcer.requestImageGeneration("a cat")
        states[id] = mkStatus(GenerationState.ERROR, null, "evergreen box is unreachable")
        val status = enforcer.imageGenerationStatus(id)
        assertEquals(GenerationState.ERROR, status.state)
        assertEquals("a backing error must be surfaced verbatim", "evergreen box is unreachable", status.error)
    }
}

/** An unknown generation id still throws PhotoGenerationException (the contract is unchanged). */
@Test
fun testImageUnknownIdStillThrows() {
    withImageEnforcer(5 * 60 * 1000L, clockStartMs = 0L) { _, enforcer, _, _, _ ->
        val e = try { enforcer.imageGenerationStatus("nope"); null } catch (ex: PhotoGenerationException) { ex }
        assertNotNull("an unknown id must still throw", e)
        assertEquals("Unknown generation id: nope", e!!.message)
    }
}

// ---- Cancel (image) ---------------------------------------------------------

/** Cancelling a still-pending generation returns true and makes its status an ERROR explaining the cancel. */
@Test
fun testImageCancelPendingReportsErrorAndReturnsTrue() {
    withImageEnforcer(5 * 60 * 1000L, clockStartMs = 0L) { _, enforcer, _, _, _ ->
        val id = enforcer.requestImageGeneration("a cat")
        assertTrue("cancelling a pending generation returns true", enforcer.cancelImageGeneration(id))
        val status = enforcer.imageGenerationStatus(id)
        assertEquals(GenerationState.ERROR, status.state)
        assertNull("a cancelled generation carries no image", status.image)
        assertEquals("Generation '$id' was cancelled.", status.error)
    }
}

/** Cancelling an unknown id returns false and does not invent a generation. */
@Test
fun testImageCancelUnknownIdReturnsFalse() {
    withImageEnforcer(5 * 60 * 1000L, clockStartMs = 0L) { _, enforcer, _, _, _ ->
        assertFalse("cancelling an unknown id returns false", enforcer.cancelImageGeneration("nope"))
    }
}

/** Cancelling an already-DONE generation returns false and leaves the DONE result intact. */
@Test
fun testImageCancelAlreadyDoneReturnsFalseAndKeepsResult() {
    withImageEnforcer(5 * 60 * 1000L, clockStartMs = 0L) { _, enforcer, states, mkStatus, mkDoneImage ->
        val id = enforcer.requestImageGeneration("a cat")
        states[id] = mkStatus(GenerationState.DONE, mkDoneImage(), null)
        assertFalse("cancelling a finished generation returns false", enforcer.cancelImageGeneration(id))
        assertEquals("a finished generation stays DONE after a no-op cancel", GenerationState.DONE, enforcer.imageGenerationStatus(id).state)
    }
}

/** A second cancel of the same generation returns false; the first cancel's ERROR stands. */
@Test
fun testImageCancelTwiceSecondReturnsFalse() {
    withImageEnforcer(5 * 60 * 1000L, clockStartMs = 0L) { _, enforcer, _, _, _ ->
        val id = enforcer.requestImageGeneration("a cat")
        assertTrue(enforcer.cancelImageGeneration(id))
        assertFalse("a second cancel of the same generation returns false", enforcer.cancelImageGeneration(id))
        assertEquals("Generation '$id' was cancelled.", enforcer.imageGenerationStatus(id).error)
    }
}

/** Cancel is sticky: once cancelled, a later backing DONE does not un-cancel the generation. */
@Test
fun testImageCancelIsStickyOverLaterDone() {
    withImageEnforcer(5 * 60 * 1000L, clockStartMs = 0L) { _, enforcer, states, mkStatus, mkDoneImage ->
        val id = enforcer.requestImageGeneration("a cat")
        assertTrue(enforcer.cancelImageGeneration(id))
        states[id] = mkStatus(GenerationState.DONE, mkDoneImage(), null) // backing finishes after the cancel
        val status = enforcer.imageGenerationStatus(id)
        assertEquals("a cancelled generation stays cancelled even if the backing model later finishes",
            GenerationState.ERROR, status.state)
        assertEquals("Generation '$id' was cancelled.", status.error)
    }
}

/** Cancel takes precedence over a concurrent timeout: a cancelled-then-expired generation reports the cancel. */
@Test
fun testImageCancelTakesPrecedenceOverTimeout() {
    withImageEnforcer(5 * 60 * 1000L, clockStartMs = 0L) { clock, enforcer, _, _, _ ->
        val id = enforcer.requestImageGeneration("a cat")
        assertTrue(enforcer.cancelImageGeneration(id))
        clock.advanceBy(5 * 60 * 1000L * 2)
        assertEquals("a cancelled generation reports the cancel, not a timeout",
            "Generation '$id' was cancelled.", enforcer.imageGenerationStatus(id).error)
    }
}

/** A timed-out generation can no longer be cancelled (there is nothing pending to cancel). */
@Test
fun testImageCancelAfterTimeoutReturnsFalse() {
    withImageEnforcer(5 * 60 * 1000L, clockStartMs = 0L) { clock, enforcer, _, _, _ ->
        val id = enforcer.requestImageGeneration("a cat")
        clock.advanceBy(5 * 60 * 1000L)
        assertFalse("a timed-out generation cannot be cancelled", enforcer.cancelImageGeneration(id))
        assertEquals("Generation '$id' timed out after 300 seconds without completing.",
            enforcer.imageGenerationStatus(id).error)
    }
}

/** Two independent generations time out on their own clocks, not the first one's. */
@Test
fun testImageTwoGenerationsHaveIndependentDeadlines() {
    withImageEnforcer(5 * 60 * 1000L, clockStartMs = 0L) { clock, enforcer, _, _, _ ->
        val first = enforcer.requestImageGeneration("first")
        clock.advanceBy(5 * 60 * 1000L - 60_000L) // first is 4 minutes in
        val second = enforcer.requestImageGeneration("second") // starts fresh
        clock.advanceBy(60_000L) // first reaches 5 min, second only 1 min
        assertEquals("the first generation has reached its own deadline", GenerationState.ERROR, enforcer.imageGenerationStatus(first).state)
        assertEquals("the second generation is still well within its deadline", GenerationState.PENDING, enforcer.imageGenerationStatus(second).state)
    }
}

// ---- Timeout + cancel (prompt) ---------------------------------------------

/** The prompt enforcer times out a still-pending generation at the deadline, with a timeout message. */
@Test
fun testPromptPendingAtDeadlineBecomesTimeoutError() {
    withPromptEnforcer(5 * 60 * 1000L, clockStartMs = 0L) { clock, enforcer, _, _ ->
        val id = enforcer.requestPromptGeneration("a cat")
        clock.advanceBy(5 * 60 * 1000L)
        val status = enforcer.promptGenerationStatus(id)
        assertEquals(GenerationState.ERROR, status.state)
        assertNull("a timed-out prompt generation carries no prompt", status.prompt)
        assertEquals("Generation '$id' timed out after 300 seconds without completing.", status.error)
    }
}

/** A prompt generation that completes before the deadline stays DONE past it. */
@Test
fun testPromptDoneBeforeDeadlineIsNeverMaskedByTimeout() {
    withPromptEnforcer(5 * 60 * 1000L, clockStartMs = 0L) { clock, enforcer, states, mkStatus ->
        val id = enforcer.requestPromptGeneration("a cat")
        states[id] = mkStatus(GenerationState.DONE, "a richer prompt", null)
        clock.advanceBy(5 * 60 * 1000L * 3)
        val status = enforcer.promptGenerationStatus(id)
        assertEquals(GenerationState.DONE, status.state)
        assertEquals("a richer prompt", status.prompt)
    }
}

/** Cancelling a pending prompt generation returns true and reports the cancellation as an ERROR. */
@Test
fun testPromptCancelPendingReportsErrorAndReturnsTrue() {
    withPromptEnforcer(5 * 60 * 1000L, clockStartMs = 0L) { _, enforcer, _, _ ->
        val id = enforcer.requestPromptGeneration("a cat")
        assertTrue(enforcer.cancelPromptGeneration(id))
        val status = enforcer.promptGenerationStatus(id)
        assertEquals(GenerationState.ERROR, status.state)
        assertEquals("Generation '$id' was cancelled.", status.error)
    }
}

/** A non-default timeout is honoured and reflected (in seconds) in the timeout message. */
@Test
fun testPromptCustomTimeoutReflectedInMessage() {
    withPromptEnforcer(timeoutMs = 30_000L, clockStartMs = 0L) { clock, enforcer, _, _ ->
        val id = enforcer.requestPromptGeneration("a cat")
        clock.advanceBy(29_999L)
        assertEquals("just before a 30s deadline stays pending", GenerationState.PENDING, enforcer.promptGenerationStatus(id).state)
        clock.advanceBy(1L)
        assertEquals("Generation '$id' timed out after 30 seconds without completing.", enforcer.promptGenerationStatus(id).error)
    }
}
