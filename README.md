# EvergreenServiceServer

A url:// service server that exposes a local **Evergreen Generator** as two model endpoints on the
global P2P network:

- **`url://evergreen-image-model/`** — an async
  [`ImageGenerationModel`](https://github.com/CodexCoder21Organization/PhotoGenerationManagerApi):
  `requestImageGeneration(prompt, inputImages)` → id, then `imageGenerationStatus(id)` → the image,
  and `cancelImageGeneration(id)` to abandon one in flight.
- **`url://evergreen-prompt-model/`** — an async
  [`PromptGenerationModel`](https://github.com/CodexCoder21Organization/PhotoGenerationManagerApi):
  `requestPromptGeneration(prompt, inputImages)` → id, then `promptGenerationStatus(id)` → text,
  and `cancelPromptGeneration(id)`.

The models are **asynchronous** so every RPC is fast — the slow generation runs server-side, which is
what lets a public consumer reach this NAT'd node through the relay (relays tolerate short RPCs, not
long blocking ones).

It hosts the
[`EvergreenImageGenerationModel` / `EvergreenPromptGenerationModel`](https://github.com/CodexCoder21Organization/PhotoGenerationManagerEmbedded)
implementations behind url:// RPC, delegating all domain logic to them.

## Generation timeout and cancellation

Each backing model is wrapped in a server-side **timeout/cancel enforcer**
(`TimeoutEnforcingImageModel` / `TimeoutEnforcingPromptModel`), so the limits hold no matter which
consumer is polling:

- **Timeout** — a generation that stays `PENDING` for more than **5 minutes**
  (`DEFAULT_GENERATION_TIMEOUT_MS`) is reported as `ERROR` ("timed out after 300 seconds…"). A
  generation that finishes first is never masked. The deadline is measured against an injectable
  `community.kotlin.clocks.simple.Clock` (default `SystemClock`), so the behaviour is verified
  instantly in unit tests with a `ManualClock` — no real waiting.
- **Cancellation** — `cancelImageGeneration(id)` / `cancelPromptGeneration(id)` cancel a still-pending
  generation; its next status becomes an `ERROR` ("was cancelled"). Cancel is sticky and wins over a
  later backing result; cancelling an unknown, finished, or already-cancelled generation returns
  `false`. This is what backs the Cancel button on the
  [PhotoGenerationManagerWui](https://github.com/CodexCoder21Organization/PhotoGenerationManagerWui)
  request page.

## Why run it locally

The Evergreen server lives on a private LAN (`https://192.168.86.243:9443`). Run
EvergreenServiceServer **on a machine with LAN access to Evergreen**; it joins the P2P network via
the default ContainerNursery relay (`198.199.106.165:35000`), so a public consumer — e.g.
[`PhotoGenerationManagerWui`](https://github.com/CodexCoder21Organization/PhotoGenerationManagerWui)
running on ContainerNursery — can reach these models **by URL** even though this node is behind NAT
(the relay handles NAT traversal; no port forwarding needed).

```
  PhotoGenerationManagerWui (public, on ContainerNursery)
        │  openSandboxedConnection("url://evergreen-image-model/", ImageGenerationModel::class)
        ▼
  P2P relay (198.199.106.165:35000)
        ▼
  EvergreenServiceServer (your LAN, behind NAT)
        ▼
  Evergreen Generator (https://192.168.86.243:9443)
```

Image bytes cross the SJVM sandbox boundary as **hex strings** (the sandbox has no
`java.util.Base64`).

## Environment variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `EVERGREEN_SERVER_URL` | `https://192.168.86.243:9443` | Evergreen server base URL |
| `IMAGE_MODEL_URL` | `evergreen:///mbns/el/home/courier/anxu/gemfuse_image_agent` | Evergreen image model |
| `PROMPT_MODEL_URL` | `evergreen:///mbns/el/home/courier/lyria_rewriter/v4p1s_whitewater` | Evergreen text (prompt-rewriter) model — must return text; `mbns`/`bns` URLs need **three** slashes (`evergreen:///mbns/...`) |
| `IMAGE_SERVICE_DOMAIN` | `evergreen-image-model` | url:// domain for the image model |
| `PROMPT_SERVICE_DOMAIN` | `evergreen-prompt-model` | url:// domain for the prompt model |

Pick distinct `*_SERVICE_DOMAIN` values if several people run their own EvergreenServiceServers.

## Building & running

```bash
scripts/build.bash evergreenserviceserver.buildMaven            # server artifact
scripts/build.bash evergreenserviceserver.buildImageClientJar   # SJVM client bytecode (image)
scripts/build.bash evergreenserviceserver.buildPromptClientJar  # SJVM client bytecode (prompt)
scripts/build.bash evergreenserviceserver.buildFatJar evergreen-server.jar
scripts/test.bash --test .

java -jar evergreen-server.jar    # registers both url:// services; Ctrl+C to stop
```

### Tests

Three layers of tests, all hermetic (no real Evergreen server, no public relay):

- **Handler tests** (`tests/testEvergreenServiceServerHandlers.kts`) construct each RPC handler over
  the real Embedded model pointed at a fake self-signed HTTPS Evergreen server (image hex round-trip,
  input-image SHA-256 forwarding, the prompt text path, prompt-from-image error) and, over simple
  in-memory models, cover the remaining handler surface: the `DONE`/`PENDING`/`ERROR` status maps
  (including the default "generation failed" message), the descriptive missing-parameter errors, the
  `handleRequest(RpcRequest)` success/error wrapping, the unknown-method service descriptor, the
  `__bytecode_request` branch, and the hex / `parseHexImageList` edge cases (multiple images, a `List`
  parameter, empty/missing images, and odd-length hex).
- **End-to-end tests** (`tests/testEvergreenServiceServerEndToEnd.kts`) exercise the *full* `url://`
  transport between two in-JVM `UrlProtocol2` nodes wired directly over loopback. A simplified
  in-memory `ImageGenerationModel`/`PromptGenerationModel` is registered behind a `url://`; a consumer
  calls `UrlResolver.openSandboxedConnection(url, Model::class)` and runs the **real client bytecode**
  in an SJVM sandbox, doing the async request→poll→DONE flow over real RPC. These assert that input
  images marshal client→server as hex (on **both** the image and prompt paths), that a generated image
  `ByteArray` survives the sandbox→host proxy byte-for-byte, that an `ERROR` status round-trips, that a
  server-side failure surfaces to the client as a thrown exception (not a hang or a fake id), and that
  both services **coexist on one node** and route independently. The client bytecode is loaded from the
  classpath via the `buildImageClientResourcesJar()` / `buildPromptClientResourcesJar()` build rules.
- **Config tests** (`tests/testEvergreenServiceServerConfig.kts`) cover `resolveServerConfig` (env-var
  defaults, overrides, and blank-as-unset, with an injected environment lookup) and
  `loadServerResource` (the descriptive missing-resource error).
