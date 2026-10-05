# Validation OnePlus / Adreno 840 — PR #5

Status: hardware validation required. Passing CI is not a Vulkan device certification.

## What changed

- Output fallback uses explicit CPU buffer overrides for `output.*`, `output_norm.*`
  and tied `token_embd.*` weights/biases. Host operation offload is disabled in
  this mode so the output projection is not automatically sent back to Vulkan.
  The GPU-layer setting is a requested offload count, not proof of actual tensor placement.
- Preparation validates CPU as well as GPU contexts with a short prefill, a large
  batch after existing KV entries and two sequential decodes. Logits are read
  synchronously and rejected on NaN/+infinity or an entirely masked distribution.
- Recoverable GPU decode failures during prompts or generation try a fixed list:
  same offload with CPU output, 3/4, 1/2, 1/4, CPU. Each candidate must also replay
  the exact resident tokens and pass the batch that actually failed. The context
  size, chat metadata and accepted sampler tokens are retained; displayed text
  is not emitted again. Sampling randomness may change after a backend reload.
- KV-capacity warnings and cancellation do not cause compatibility fallback.
- All decode calls, including benchmarks, keep raw return codes and phase. A
  bounded failure history survives subsequent successes. No prompt text is logged.
- Batch arrays use C++ ownership rather than unchecked allocations in llama_batch_init.
- Benchmarks synchronize GPU work; the old thread-tuning cache is invalidated.

## Automated checks

`bash scripts/test-native.sh /path/to/pinned/llama.cpp` runs ASan/UBSan tests:

1. Existing context/budget/Unicode helpers.
2. Fallback order, cancellation, finite-logit validation, raw diagnostics and batch boundaries.
3. The actual `ai_chat.cpp` with a deterministic fake backend: multi-token probe
   failure, explicit output override, replay failure, mid-generation recovery,
   all GPU candidates failing, cancellation, invalid CPU/GPU logits and resource ownership.

These tests exercise production control flow but do not execute real Vulkan kernels.
CI also builds ARM64/Vulkan, executes JVM tests and checks APK signing, native libraries,
16 KiB ELF alignment and the generated instrumentation APK. Instrumentation tests are
compiled and packaged by CI but must run on the physical Adreno 840 device.

The real-device test now checks deterministic semantics, not merely non-empty output.
The first turn must produce 4 for 2+2, the second turn must use conversation history
and produce 5, invalid UTF-8 is rejected and a repeated-character run such as the
previously observed `@@@@` corruption fails the test.

## Identify the tested binary

Use the artifact `PocketAI-4.2.2-arm64-vulkan-test` for the exact CI commit.
It contains:

- `PocketAI-4.2.2-arm64-vulkan-test.apk`: isolated UI build,
  package `com.pocketai.app.vulkanvalidation`, label `PocketAI Vulkan Test`.
- `PocketAI-4.2.2-engine-androidTest.apk`: engine instrumentation test.
- `DEVICE-SMOKE.sh`: ADB helper that installs the instrumentation APK, optionally
  pushes a local GGUF, runs CPU and requested-Vulkan passes and captures diagnostics.
- `ADRENO-840-VALIDATION.md`, `SOURCE-COMMIT.txt`, `SHA256.txt` and verification evidence.

Record `SOURCE-COMMIT.txt`, `SHA256.txt`, APK version, model SHA256, Android build
and GPU driver. The diagnostic session embeds the source revision and application ID.
The UI validation build is isolated from the normal PocketAI package. Its models and
preferences are therefore separate. The CI debug signing key may change between builds:
upgrading an earlier test install can require removing only the validation package.
Do not uninstall the user's main app.

## One-command engine validation

Recommended from a computer with Android platform-tools and the exact GGUF available locally:

```bash
bash DEVICE-SMOKE.sh --local-model /path/to/Qwen2.5-3B-Instruct-Q4_K_M.gguf
```

That mode proves reliability even if PocketAI automatically falls all the way back to CPU.
To validate that the requested Vulkan pass remains active:

```bash
bash DEVICE-SMOKE.sh --local-model /path/to/Qwen2.5-3B-Instruct-Q4_K_M.gguf --require-vulkan
```

The helper refuses to treat an instrumentation skip as success and writes both the
instrumentation result and filtered native/device diagnostics beside the script.

An existing device path can be used with `--device-model`, but it must be readable
by the instrumentation package. If Android scoped storage blocks it, use `--local-model`.

## Required device matrix

Use Qwen2.5-3B-Instruct-Q4_K_M first, context 2048, batch 256, maximum GPU setting.
Keep a CPU reference with identical model and prompts.

| Case | Acceptance |
| --- | --- |
| Automated CPU + requested-Vulkan smoke test | Correct numeric semantics across two turns; no `@@@@`-style degeneracy; no invalid UTF-8; raw decode status clean |
| Cold load and first short prompt, then a second turn | Non-empty coherent responses; no immediate decode error |
| Formatted prompts around 10, 29, 255, 256, 257 tokens and multiple batches | Prefill/logit/generation transitions work |
| Output override | Confirm output projection placement with an instrumented graph/backend trace; layer count alone is insufficient |
| 256–512 token response and repeated turns near context capacity | No crash, duplicate streamed output or corrupted history; clean context-limit errors where applicable |
| Cancellation during prepare, prefill, generation | Cancellation stops further attempts; reload/new prompt succeeds |
| Retuning/benchmark followed by a normal prompt | Correct output and timing; main conversation remains usable |
| At least five load/unload cycles | No steady memory growth after resources are released |
| Reduced free RAM and warm device | Clean failure or fallback, no unsupported claim of GPU acceleration |
| Failure injection in a diagnostic build | CPU-output, partial GPU and final CPU paths preserve existing conversation and expose raw failure history |

Export diagnostics immediately after failure. Keep the latest raw code separate
from the retained failure list: the last code may be zero after successful recovery.
Compare time to first token, prompt tokens/s, generation tokens/s, memory and thermal
status against CPU. An automatic CPU fallback is a reliability pass, not a GPU performance pass.

## Limitations

A C++ catch cannot recover from SIGABRT, driver process death or an indefinitely
blocked GPU call. Cancellation is checked between stages and uses llama.cpp's abort
callback, whose GPU support is limited. The finite fallback list is not a hard timeout
on an individual Vulkan driver call. A process-isolated inference service would be
needed for robust recovery from those failures; it is outside this targeted change.

Do not mark the PR ready solely from CI: attach device results for the exact build.
