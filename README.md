# EvergreenServiceServer

A url:// service server that exposes a local **Evergreen Generator** as two model endpoints on the
global P2P network:

- **`url://evergreen-image-model/`** — an
  [`ImageGenerationModel`](https://github.com/CodexCoder21Organization/PhotoGenerationManagerApi):
  `generateImage(prompt, inputImages)` → an image (with embedded XMP/XML metadata).
- **`url://evergreen-prompt-model/`** — a
  [`PromptGenerationModel`](https://github.com/CodexCoder21Organization/PhotoGenerationManagerApi):
  `generatePrompt(prompt, inputImages)` → a new prompt string.

It hosts the
[`EvergreenImageGenerationModel` / `EvergreenPromptGenerationModel`](https://github.com/CodexCoder21Organization/PhotoGenerationManagerEmbedded)
implementations behind url:// RPC, delegating all domain logic to them.

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
| `PROMPT_MODEL_URL` | `evergreen:///mbns/vz/home/courier/rakicevic/gemfuse_image_agent` | Evergreen text model |
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

The tests construct each RPC handler over the real Embedded model pointed at a fake self-signed
HTTPS Evergreen server and verify the image hex round-trip, input-image SHA-256 forwarding, the
prompt text path, and error handling.
