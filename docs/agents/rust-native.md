# Rust core (`native/`)

In force since 2026-11-18, when the owner approved [design 0025](../design/0025-rust-engine-migration.md). "Still to apply" at the end lists the parts that wait for later work.

Read this before touching anything under `native/`, the Kotlin bindings to it, or the Gradle and CI steps that build it.

The owner does not read Rust (owner statement, 2026-11-18). So the rules here are enforced by tools and CI wherever possible, and reports to the owner describe behaviour, not code.

## Layout

`native/` is one Cargo workspace. Rust code lives nowhere else.

| Crate | Holds | `unsafe` |
| --- | --- | --- |
| `fga-sensing` | frame handling, template matching, classifiers; since #524 the PaddleOCR text recogniser (preprocessing, CTC decoding, the `tract` and `ort` backends) | forbidden |
| `fga-capture` | proxy, TLS, response parsing, log storage | forbidden |
| `fga-automation` | automation flow (added in stage 6 of 0025) | forbidden |
| `fga-ffi` | the Kotlin boundary: bindings, panic handling, frame hand-off | allowed, with rules below |

- The Kotlin side holds interfaces and thin binding code only. Thresholds, templates and model files of a Rust implementation stay inside `native/`.
- A crate other than `fga-ffi` has no Android or JNI dependency, so `cargo test` runs it on a PC.

## Code rules

- **No `unsafe` outside `fga-ffi`.** Every other crate starts with `#![forbid(unsafe_code)]`.
- **`unsafe` inside `fga-ffi`:** each block has a `// SAFETY:` comment that says why it is sound. A PR that adds or changes one says so in its description.
- **No `unwrap`, `expect`, `panic!`, `todo!` or slice indexing that can panic outside tests.** Clippy enforces the first four (`unwrap_used`, `expect_used`, `panic`, `todo`). Return a `Result` with a typed error instead.
- **Every function that Kotlin can call catches panics** (`catch_unwind`) and turns them into an error value. The workspace keeps `panic = "unwind"`; `panic = "abort"` would kill the app.
- **A reader that cannot decide returns "unknown".** It never guesses. This matches the conservative policy of the Kotlin readers.
- **Bounded memory in capture code.** Parse responses as streams. Every buffer, queue and cache has a stated maximum. Nothing is loaded whole because it is "usually small".
- **One async runtime**, owned by `fga-capture`, started and stopped with the Android service. No detached threads that outlive the service.
- **Logs** go through one callback to the app's existing logger, so Rust and Kotlin lines appear in the same place. Never log response bodies, tokens or account identifiers.
- Comments follow the repo rule: write why, not what.

## Dependencies

- A new crate needs a reason in the PR description and the planner's approval. Prefer the standard library and crates already in the workspace.
- Licences: MIT, Apache-2.0, BSD and similar permissive licences only. No GPL or AGPL. `cargo-deny` checks licences and known advisories in CI.
- `Cargo.lock` is committed. `rust-toolchain.toml` pins the compiler version; bump it in its own PR.
- No crate that needs a C or C++ build step unless the design names it. Such crates complicate the Android build and the 16 KB page alignment.

## Build and test

Run these from `native/`. `rust-toolchain.toml` there pins the compiler, so rustup switches to it on its own.

```bash
cargo fmt --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
cargo deny check
cargo ndk -t armeabi-v7a -t arm64-v8a --platform 24 -o ../build/rustJniLibs build --release -p fga-ffi
cargo ndk -t armeabi-v7a -t arm64-v8a --platform 24 clippy --workspace --all-targets -- -D warnings
```

The ABIs are the ones the app ships (`shippedAbis` in `app/build.gradle.kts`). `--platform 24` is the app's `minSdk`, so the library never needs a newer libc than the app supports, and the Android lint run uses it too so it checks the same build. The last line checks the code that only builds for Android and ARM, which the PC runs leave out.

The inference backends of `fga-sensing` are Cargo features that are off by default in that crate. Since issue #530, `fga-ffi` turns on `tract` for its device probes, so every `--workspace` command above, the Android lint run and the Android build compile `tract`; `ort` stays off in all of them unless `fga-ffi`'s `ort-probe` feature is asked for (issue #535, "ONNX Runtime on Android" below). Lint and test each backend on `fga-sensing` alone with (for `tract` this now repeats what the workspace commands do):

```bash
cargo clippy -p fga-sensing --all-targets --features tract -- -D warnings
cargo test -p fga-sensing --features tract
cargo clippy -p fga-sensing --all-targets --features ort -- -D warnings
cargo test -p fga-sensing --features ort
```

`tract` is pure Rust. On the PC, `ort` has its build script download a prebuilt ONNX Runtime from `cdn.pyke.io` (checked against a hash in the crate) and link it statically, so it needs network access on the first build and compiles no C++ of ours; the download is cached under the user's cache folder (`%LOCALAPPDATA%\ort.pyke.io` on Windows, `~/.cache/ort.pyke.io` on Linux). On Windows the prebuilt runtime is the DirectML build, and the build also places `DirectML.dll` next to the binaries it produces (a link into that cache folder), which they load at start.

The model and the labelled samples are local only (see "Test data"), so the tests that run the real model need two environment variables and skip themselves, with one printed line, without them. They are how a change to preprocessing or decoding is checked against the Python result the model was chosen on:

```bash
export FGA_OCR_MODEL=/path/to/PP-OCRv6_rec_small.onnx   # the owner keeps it under build/orchestration/rust/ocr/models/
export FGA_OCR_SAMPLES=/path/to/samples                  # NNNN.png and index.json, under build/orchestration/rust/ocr/samples/
cargo test -p fga-sensing --features tract --test model -- --nocapture
```

The evaluation tool runs every sample and prints the agreement with Python, the +62 and +16 misses and false accepts, and the timings; it takes the backend, the model, the samples folder and an output file, plus an optional allowed character set. Use `--release`, since a debug build of tract is many times slower. In Git Bash, set `MSYS_NO_PATHCONV=1` for the allowed set, or the shell rewrites `+/...` into a Windows path:

```bash
cargo run --release -p fga-sensing --features tract --example ocr_eval -- \
    tract "$FGA_OCR_MODEL" "$FGA_OCR_SAMPLES" ../build/ocr-eval-tract.tsv --allowed "+/0123456789"
```

In the app, Gradle builds the library only when asked:

```bash
./gradlew -Pfga.native=true -PfgaDeviceTests=true :app:assembleDeviceTest :app:assembleDeviceTestAndroidTest
py -3 tools/device-qa/run.py --build --native --suite nativeProbe
```

`-Pfga.native=true` registers `cargoNdkBuild`, which runs the `cargo ndk` build above into `app/build/generated/rustJniLibs` and adds it to every variant's native libraries. Without the property no task looks at `native/`, and the APKs carry no Rust library. The `nativeProbe` device suite runs the probe tests in `app/src/androidTest`; on an APK built without the library they report themselves skipped and their results are logged under the logcat tag `FgaNativeProbe`. The skipped reason carries the loader's message and says "not packaged" only when the class loader could not find `libfga_ffi.so` itself; a library that is packaged but does not load (for example a missing or broken `libonnxruntime.so`) is reported as such.

The `ocrProbe` device suite (issue #530) runs the PP-OCRv6 recogniser through `libfga_ffi.so` with the `tract` backend and, for comparison, the app's Tesseract engine, on the labelled samples from "Test data". It reports agreement with the Python text, time per crop and process memory (`/proc/self/status` and Android PSS). The model and the samples are not committed, so they are pushed to these device paths first:

- `/sdcard/Download/fga-device-qa/ocr/PP-OCRv6_rec_small.onnx`
- `/sdcard/Download/fga-device-qa/ocr/samples/index.json` and `0000.png` to `0369.png`

`--local-fixtures` pushes every device path listed in the case file `docs/reference-images/cases/ocrProbe.json` (under `imports`) from the same relative path inside a local folder, so that folder must hold `ocr/PP-OCRv6_rec_small.onnx` and `ocr/samples/`. Keep it under `build/`:

```bash
mkdir -p build/ocr-device-fixtures/ocr
cp build/orchestration/rust/ocr/models/PP-OCRv6_rec_small.onnx build/ocr-device-fixtures/ocr/
cp -r build/orchestration/rust/ocr/samples build/ocr-device-fixtures/ocr/
py -3 tools/device-qa/run.py --build --native --suite ocrProbe --local-fixtures build/ocr-device-fixtures
```

Without the library the suite reports itself skipped; with it, a missing model or sample fails the case and names the path. The model copy the suite makes in the app's cache folder is removed when the run ends; the pushed files under `/sdcard/Download/fga-device-qa/` stay.

`tract-recognise` and `ort-recognise` pass with all 370 texts equal to Python, `tract-recognise-allowed` and `ort-recognise-allowed` with at least 366, and `ort-recognise-1thread` with 370. These floors are the values measured on the three devices of #530 (tablet, MuMu, BlueStacks), which equal the PC's: a lower count is a regression to investigate, not a number to lower. The report and the `FgaOcrProbe` log line give the exact count. The cases share one process, so the case file runs the Tesseract case first, each engine's memory case before its other cases, and the tract cases before the ONNX Runtime ones. Results are logged under the logcat tag `FgaOcrProbe`.

### ONNX Runtime on Android (issue #535)

The `ocrProbe` suite also measures the recogniser with ONNX Runtime (`ort-memory`, `ort-recognise`, `ort-recognise-allowed` at ONNX Runtime's default thread count, and `ort-recognise-1thread` with one intra-op thread, the like-for-like comparison with tract, which runs on one thread). It needs a second Gradle property, off by default:

```bash
./gradlew -Pfga.native=true -Pfga.native.ort=true -PfgaDeviceTests=true :app:assembleDeviceTest :app:assembleDeviceTestAndroidTest
py -3 tools/device-qa/run.py --build --native-ort --suite ocrProbe --local-fixtures build/ocr-device-fixtures
```

- `-Pfga.native.ort=true` is meaningful only with `-Pfga.native=true` (alone it stops the build). It turns on `fga-ffi`'s `ort-probe` feature, which adds `ocrLoadOrt`, `ocrReadOrt` and `ocrUnloadOrt` next to the tract functions. Without the property nothing changes: the same tasks, no extra dependency resolved, and the same `libfga_ffi.so`. With both properties, every variant that Gradle builds packages `libonnxruntime.so` and the ONNX Runtime build of `libfga_ffi.so` (`assembleCi` and `assembleBeta` too, as `fga.native` alone does for `libfga_ffi.so`), so the properties are for device-test builds, and neither is ever set for a release build. Without the feature those three functions do not exist; the suite catches `UnsatisfiedLinkError` and reports the ONNX Runtime cases skipped (`"requires": "ort"`) while the tract cases still run.
- `--native-ort` implies `--native` and fails the run when an ONNX Runtime case was skipped; with `--native` alone those cases may be skipped.
- The runtime is Microsoft's `com.microsoft.onnxruntime:onnxruntime-android` AAR from Maven Central, version 1.31.0 (the minor that `ort` 2.0.0-rc.15 binds and the one the PC tests use): `https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.31.0/onnxruntime-android-1.31.0.aar`, SHA-256 `72683787a80d0608a3121b967d8e1239aef48286826661c953211626690540a7` (its SHA-1 equals Maven Central's published `.sha1`). Gradle resolves it into its own configuration only when the property is on, checks the SHA-256, and extracts only `jni/<abi>/libonnxruntime.so` into `app/build/onnxruntime/jniLibs/<abi>/`, so the AAR's Java classes, JNI library and manifest (an `INTERNET` permission and a telemetry provider) never reach the APK. It is never committed.
- Version and SHA-256 are pinned in three places that change together: `gradle/libs.versions.toml` (the version), `app/build.gradle.kts` (`extractOnnxRuntime`, the SHA-256) and `.github/workflows/native-ci.yml` (both, for the CI build that fetches the AAR itself). Each place says so in a comment.
- Linking: `ort-sys`'s documented way to use a supplied runtime is `ORT_LIB_PATH` (the folder that holds it) with `ORT_PREFER_DYNAMIC_LINK=1`; `libfga_ffi.so` then lists `libonnxruntime.so` as `NEEDED`, and Android loads it from the APK with `System.loadLibrary("fga_ffi")`. `ORT_LIB_PATH` is one folder per build, so Gradle runs one cargo-ndk build per ABI (`cargoNdkBuildOrtArm64V8a`, `cargoNdkBuildOrtArmeabiV7a`) in place of `cargoNdkBuild`. No `load-dynamic`, which would add the `libloading` crate.
- What the AAR's `libonnxruntime.so` is: ONNX Runtime 1.31.0, MIT licence (the POM), `minSdkVersion` 24 (its manifest; the ELF note says API 24, NDK r28), C++ runtime linked in (it needs only `libc`, `libm`, `libdl`, `liblog`, `libandroid`), all `LOAD` segments 16 KB aligned on arm64 and armeabi-v7a. ABIs in the AAR: arm64-v8a, armeabi-v7a, x86, x86_64; the app packages the first two. Size as packaged: 34,208,672 B (arm64-v8a), 24,181,976 B (armeabi-v7a).
- Execution providers: the probes register none, so ONNX Runtime uses its CPU provider. ONNX Runtime's mobile documentation says the Android package also supports NNAPI and XNNPACK; the binary exports `OrtSessionOptionsAppendExecutionProvider_Nnapi`. Neither is enabled here.
- The prebuilt runtime that `ort` itself downloads (`cdn.pyke.io`, listed in `ort-sys`'s `build/download/dist.tsv`) exists for `aarch64-linux-android` only (a static NNAPI build, which would also need `libc++_shared.so` linked), not for `armv7-linux-androideabi`, so the Android build does not use it.
- `ort` allows one ONNX Runtime environment per process, ever, so `OrtBackend` builds it on the first load and keeps it for the life of the process; a memory reading after `ocrUnloadOrt` still includes it.

Lint and build the feature for Android without Gradle: lint needs no runtime (`ORT_SKIP_DOWNLOAD=1` defers ort-sys's missing-library error to the link step), the build needs the extracted `libonnxruntime.so` of each ABI (`.github/workflows/native-ci.yml` does the same with the AAR from Maven Central):

```bash
ORT_SKIP_DOWNLOAD=1 cargo ndk -t armeabi-v7a -t arm64-v8a --platform 24 clippy --workspace --all-targets --features fga-ffi/ort-probe -- -D warnings
ORT_PREFER_DYNAMIC_LINK=1 ORT_LIB_PATH=../app/build/onnxruntime/jniLibs/arm64-v8a \
    cargo ndk -t arm64-v8a --platform 24 -o ../build/rustJniLibsOrt build --release -p fga-ffi --features ort-probe
```

On the PC, `cargo test --workspace --features fga-ffi/ort-probe` runs the ONNX Runtime probe tests with the prebuilt runtime that `ort` downloads, and with `FGA_OCR_MODEL` and `FGA_OCR_SAMPLES` set, `tests/ocr_ort_model.rs` reads the same 25 samples as Python at the default thread count and at one thread.

### Speed measurements: the Kotlin reader, input width and tract threads (issue #549)

Three switches measure where the recogniser's time goes. They change no behaviour of the app and choose nothing; all are off by default.

- **Minimum model width.** `Recognizer::with_min_width` (and `preprocess::prepare_crop_with`, `to_input_tensor_with`) pad a short crop to a chosen width instead of RapidOCR's 320. Resize, normalisation and the padding value (0 after normalisation) stay the same; the allowed range is 8 (the model emits one output step per 8 columns) to `MAX_MODEL_WIDTH`. `TractSettings { min_width, threads }` with `TractBackend::load_file_with` plans that width at load. The default, 320, gives the same tensors as before (the fixed-sample model test still matches Python's texts and confidences).
- **tract threads.** `fga-sensing`'s feature `tract-threads` (through `fga-ffi`'s feature of the same name, Gradle's `-Pfga.native.threads=true`, only with `-Pfga.native=true`) builds `tract-linalg` with its `multithread-mm` feature and gives the tract backend a rayon pool of the requested size, built at load and dropped with the backend. It adds no crate: `tract-linalg` and `rayon` are already in the build (rayon through `tract-transformers`), so `Cargo.lock` only gains dependency lines. Without the feature, more than one thread is an error (`ERR` in the probes), never a silent single-thread run.
- **Probes.** `NativeProbe.ocrLoadTract(path, minWidth, threads)` fills the slot that `ocrRead` and `ocrUnload` use; `minWidth` is 8..4096, `threads` 0..16 (0 and 1 are the calling thread), both checked before the lock. `ocrTractFeatures()` says whether the library has the thread feature. `ocrLoad` is unchanged.

What tract 0.23.10's sources say (`~/.cargo/registry/src/*/tract-linalg-0.23.10/src/multithread.rs` and `src/frame/mmm/mod.rs`): the executor is `tract_linalg::multithread::Executor` (`SingleThread`, `MultiThread(Arc<rayon::ThreadPool>)`, `RayonGlobal`); the process default is `set_default_executor`, and `multithread_tract_scope(executor, f)` overrides it for one call on the current thread, which the backend uses. The thread count is the rayon pool's size, so it is per backend, not per process. `Executor::multithread(n)` panics when the pool cannot be built, so the backend builds the pool itself. `multithread_tract_scope` puts the previous executor back only when its closure returns, not when it panics; the backend therefore catches a panic inside the scope, lets the scope return and restore, and then raises the same panic again (`resume_unwind`). A tract panic so reaches the caller in both builds alike (the probes' panic guard reports `ERR: panic: ...` and their recogniser lock is poisoned), and it never leaves a pool installed on the calling thread. Without the feature the backend sets no executor at all and tract runs on its process default, `SingleThread`. A matrix product runs on the pool only when it has at least 64 panels or 2^21 multiply-adds (`THREADING_PANEL_THRESHOLD`, `THREADING_MAC_THRESHOLD`); the maintainers' comment there says below that size "per-call dispatch overhead (~5 µs native ...) exceeds the parallel speedup" and suggests a higher threshold for "many-small-MMM workloads (mobile vision ...)". The thresholds are left at their defaults. Element-wise and depth-wise work splits on row boundaries, which the source states is bit-identical to the single-thread path; the PC tables below found identical texts and confidences (to the five decimals the evaluation tool writes).

What the model's graph says (read with a small protobuf reader, no package installed): the output has one step per 8 input columns (T = W / 8: 40 at 320, 12 at 96). The graph has five `ReduceMean` over axes 2 and 3 (squeeze-and-excitation pooling over height and width) and two self-attention blocks (`Softmax` over all positions). Both mix the padding columns into every column, so the output for the content is not independent of the padded width. RapidOCR pads with zeros after normalisation to `rec_img_shape` 320 (`ch_ppocr_rec/main.py`, `resize_norm_img`).

PC evaluation (`ocr_eval`, release build):

```bash
cargo run --release -p fga-sensing --features tract --example ocr_eval -- \
    tract "$FGA_OCR_MODEL" "$FGA_OCR_SAMPLES" ../build/ocr-eval-w160.tsv --min-width 160
cargo run --release -p fga-sensing --features tract-threads --example ocr_eval -- \
    tract "$FGA_OCR_MODEL" "$FGA_OCR_SAMPLES" ../build/ocr-eval-t4.tsv --threads 4
```

Results on the owner's PC (issue #549, 370 samples, release build). Equal texts are against Python; the +62 and +16 columns are misses / false accepts (Python: 0/1 and 1/1). No width below 320 is accuracy-neutral: even 256 changes 4 texts without an allowed set and 3 with it. A verifier reproduced the equal-text counts at 320, 256, 160 and 96.

| Minimum width | Equal texts (none / `+/0123456789`) | +62 (none / allowed) | +16 (none / allowed) | Median per crop, one thread (none / allowed) |
|---|---|---|---|---|
| 320 | 370 / 366 | 0/1 / 0/1 | 1/1 / 1/1 | 28.7 / 29.3 ms |
| 256 | 366 / 363 | 0/1 / 0/1 | 0/1 / 0/1 | 23.4 / 23.4 ms |
| 192 | 363 / 364 | 1/1 / 0/1 | 1/1 / 1/1 | 17.5 / 17.0 ms |
| 160 | 361 / 362 | 1/1 / 0/1 | 1/1 / 1/1 | 14.9 / 14.5 ms |
| 128 | 361 / 360 | 0/1 / 0/1 | 2/1 / 2/1 | 11.9 / 11.7 ms |
| 112 | 357 / 357 | 1/1 / 0/1 | 2/1 / 2/1 | 10.7 / 10.6 ms |
| 96 (the content of a 60x30 crop) | 355 / 355 | 0/1 / 0/1 | 2/1 / 2/1 | 8.4 / 8.5 ms |

Threads on a 20-core PC: two threads give no gain, four threads about 17 to 27 % (16 to 17 % in the runs below; the planner gives 17 to 27 % across these and a verifier's runs; texts and confidences equal to one thread in every row). One-thread medians varied between 27.8 and 29.3 ms from run to run.

| Setting | 1 thread | 2 threads | 4 threads |
|---|---|---|---|
| 320, no allowed set | 28.3 ms | 29.1 ms | 23.5 ms |
| 320, `+/0123456789` | 27.8 ms | 25.7 ms | 23.3 ms |
| 160, no allowed set | 14.4 ms | 12.8 ms | 12.0 ms |

The tool also prints the smallest and the median confidence over the texts that read as a score. The model tests add a narrower width (texts of the 25 fixed samples) and, with `--features tract-threads`, two threads (texts and confidences):

```bash
cargo test --release -p fga-sensing --features tract-threads --test model -- --nocapture
```

Device cases in `ocrProbe`, after the existing ones:

- `kotlin-reader`: the current `SupportScoreReader` on each of the 370 crops through `SupportSuite`'s crop path (`FixtureApi` frame `crop` at the score region of row y 200, the row's server profile, `read(bound, true, true)`), so every OCR profile, the dark retry and the template matches run as in production. It reports time per field (median, 90 %, maximum, also per server), OCR calls per field, the +62 and +16 misses and false accepts, and how many fields equal the stage 0 replay's own result (`kt_cls`, `kt_grand` in `index.json`). It needs no Rust library.
- `tract-width-160` and `tract-width-160-allowed`: observations at a width the PC found not accuracy-neutral (no width below 320 was); their `minEqual` is the PC count (361, 362), and a different count is reported, not failed.
- `tract-threads-2`, `tract-threads-4` and their `-allowed` twins at 320 (`minEqual` 370 and 366): need the thread feature; without it they report `"requires": "tract-threads"` and are skipped. Each must also read exactly the texts of the one-thread tract case with the same allowed set that ran before it in the same process (`oneThreadReference`).
- `kotlin-reader` fails only when a field made no OCR call (the crop did not reach the reader) or the reader accepted a wrong +62 or +16 on a labelled row (the stage 0 replay had none). Agreement with stage 0 is reported as same value, both nothing and differ, with the differing rows; it is not checked, because stage 0 ran on MuMu and Tesseract on another CPU may round differently. Its numbers are in one `FgaOcrProbe` log line, since the summary lists only failed cases.

```bash
py -3 tools/device-qa/run.py --build --native-threads --suite ocrProbe --local-fixtures build/ocr-device-fixtures
```

`--native-threads` implies `--native`, builds with `-Pfga.native.threads=true` and fails the run when a `tract-threads` case was skipped; it combines with `--native-ort`. With `--native` alone those cases may be skipped.

### Support-score reader (#555)

`fga_sensing::readers::support_score` decides whether a recognised text is a support score: the class score (0 to 62) and the Grand score (0 to 16), each a value or "unknown" with a reason. The bar is the owner's: never return a wrong value; a miss costs a refresh, a false accept picks the wrong servant. Every rule only ever turns a value into "unknown". It is not connected to the app; the Kotlin reader stays in use.

- `SupportScoreReader::observe` runs the recogniser and the pixel checks, `decide(observation, &ScoreConfig)` applies the rules in a fixed order: crop size, blank, confidence, format, range, clipping. The split lets `support_score_eval` try other settings without running the model again. The reader takes any `Backend` through the `Recognizer`; its unit tests use a scripted fake. `read_with` reads on a recogniser somebody else holds, which the device probe needs.
- The Kotlin reader's Tesseract workarounds (several binarisation profiles, the `416` rule, template matching, agreement across profiles) are not ported.
- **How a threshold is chosen** (R3 of the task): the rule is fixed before the pass counts are looked at. For a confidence floor: with wrong reads present, the midpoint of the gap between the highest wrong and the lowest correct confidence (no floor if they overlap); with none, the lowest correct confidence minus 0.05. A choice is then cross-checked by deriving it on `known_miss` false and applying it to true and the reverse, and on Jp then Kr and the reverse. 370 samples are few: where the rule cannot be validated, the default says so in its comment, and a finding such as "the derived floor differs by server" is a result, not something to tune away.
- **Evaluation:**

```bash
cd native
cargo run --release -p fga-sensing --features tract --example support_score_eval -- \
    tract "$FGA_OCR_MODEL" "$FGA_OCR_SAMPLES" ../build/support-score-eval.tsv
cargo run --release -p fga-sensing --features tract --example support_score_eval -- \
    tract "$FGA_OCR_MODEL" "$FGA_OCR_SAMPLES" ../build/support-score-analysis.tsv --analysis
cargo run --release -p fga-sensing --features tract --example support_score_eval -- \
    tract "$FGA_OCR_MODEL" "$FGA_OCR_SAMPLES" ../build/support-score-sweep.tsv --sweep   # the cut sweep, a few minutes
```

The first prints the numbers of the acceptance line of design 0025, section 5 (misses at most 12 and 25, false accepts 0, no wrong labelled value), the unknown reasons, and every row where the reader differs from the Kotlin reader's stage 0 result, and writes one TSV row per sample. Flags (`--restricted`, `--format`, `--range`, `--floor`, `--char-floor`, `--no-clipping`) change one rule. The second prints the tables the defaults were chosen from (the tables after the mode comparison use the mode of the default configuration): format, character restriction, floor distributions, sensitivity, cross-checks, the rows each rule changes. The Kotlin stage 0 results (`kt_cls`, `kt_grand`) only hold the 62 and 16 that the Kotlin policy asked for, so most rows read as a difference; the "decision on 62/16" lines are the comparison that matters.
- **The defaults** (`ScoreConfig::default()`, pinned by a test): strict `+NN` / `+NN/+MM`, the class plus included (a field cut at the left loses the plus first, and a tolerance for a missing plus let `2/+16` and `52/+16` through the cut sweep); class 0 to 62 and Grand 0 to 16, an out-of-range number making the other field of the compound unknown (`RangePolicy::Joint`; the samples hold no such row, so this is the conservative choice, not a measured one); text confidence at least 0.86 (a margin below the lowest correct read, not a boundary: it separates nothing on the samples) and weakest character at least 0.5 (a character the model gives less than even odds is not a read); no character restriction in the recogniser; the pixel check on; the crop size 60x30, any other size unknown with its own reason (`crop_size`).
- **When a cut is detected, every field and the display form are unknown**: a text that runs into a flat band has lost the lower part of every digit, and a cut field cannot know that it has no Grand score. The same holds for the display form when the range rule leaves no value.
- **The pixel check** (`clipping.rs`) has one rule: a text that ends in a textureless band at the bottom of the crop (the lower part of every digit is missing). A crop with no texture at all is blank, whatever the model reads out of it. There is **no left or right rule** (owner decision): a cut can only shorten a number, so it cannot create the 62 or the 16 the automation acts on (`SupportScoreSelection.kt`), and on real screens `+62/+16` fills the crop edge to edge, so ink at the edge does not separate a whole field from a cut one. The cut sweep showed what such rules cost: ink in the last four columns plus the first column made every wrong number disappear from the sweep but lost 112 of 306 labelled reads (+62 misses 33, +16 misses 109).
- **The cut sweep** (`support_score_eval --sweep`): every sample with a readable label, shifted inside the 60x30 size right and left by 1 to 20 columns and down and up by 1 to 10 rows, read, and every wrong value split by what the automation does with it: F62 (class 62 for a true class that is not 62), F16 (Grand 16 for a true Grand that is not 16, or for a field that has no Grand score) and OTHER (any other wrong number, which acts as a miss), with the correct 62 and 16 accepted on cut crops beside them. The fifth count is "no truth": a 62 or 16 read for a field with no label, no flag and no unshifted read, listed by file (`py_text` is not used as truth). It also compares five rule sets, prints the ink histograms of the real reads, and writes one row per cut crop to the TSV path (file, direction, amount, text, confidence, weakest character, class, Grand, and the five counts `f62 f16 other correct no_truth` as numbers). The rows are those of rule set A (strict format, the pixel check on) whatever `--format` or `--no-clipping` say, like the first table of the rule-set comparison, and the tool sums the five columns per direction and stops with an error if they differ from that table. SYNTHETIC: what moves out is lost, what moves in is the median gray of its row or column, not game content, so a clean sweep is necessary evidence, not proof about real cuts. The env-gated model test `cut_sweep_has_no_wrong_pick` reads every one of those shifts on ten fixed samples and, for four more samples, only the shifts left by 17 to 20 columns; it asserts F62 and F16 are zero, and holds OTHER to the count measured when the reader was last changed (a regression bound, not a target).
- **Device case:** `rust-reader` in `ocrProbe` (after `kotlin-reader`) calls `NativeProbe.supportScoreRead(pixels, width, height)` on the recogniser that `ocrLoad` loaded; there is no load call of its own, and `ocrUnload` serves it. It reports the same tallies as `kotlin-reader` (+62 and +16 read, missed, false accepts, class and Grand values, differences from `kt_cls`/`kt_grand`), the unknown reasons and the time per field. It fails on an `ERR`, a false accept, a wrong value (a labelled value read as another number, or an `is62` row with a class other than 62, or an `is16` row with a Grand score other than 16, labelled number or not), and when it reads fewer than `minRead62` and `minRead16` (the PC counts in the case file; the texts are the same on a device, so a lower count is a regression to investigate). It runs with the rest of the suite (the case file has no per-case filter), after the model and samples are pushed:

```bash
py -3 tools/device-qa/run.py --build --native --suite ocrProbe --local-fixtures build/ocr-device-fixtures
```

CI runs the same set in `.github/workflows/native-ci.yml`, only when `native/` or that workflow changes; `Main CI` calls it and its gate waits for the result. It also fails when the arm64 library is not 16 KB aligned; check that locally with `llvm-readelf -lW` from the NDK (every `LOAD` line must end in `0x4000`).

On the owner's PC, each shell needs:

```bash
export PATH="$USERPROFILE/.cargo/bin:$PATH"
export ANDROID_NDK_HOME="$LOCALAPPDATA/Android/Sdk/ndk/28.2.13676358"
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"   # Gradle only, when there is no local.properties
```

Since 2026-11-18 that PC has Visual Studio 2022 Build Tools (the C++ workload), and Rust uses the MSVC host toolchain (`1.100.0-x86_64-pc-windows-msvc`). Every command above runs there: formatting, both lint runs, all tests including the crypto, TLS and inference probes, `cargo deny check`, and the Android build.

Rules:

- A PR that touches `native/` runs all of the above before its first push, plus the full Kotlin unit test command from `AGENTS.md`.
- A Kotlin-only change needs no Rust tools. Building with the Gradle property `fga.native` off must always work.
- BlueStacks runs the ARM libraries through binary translation, and OpenCV once crashed there with SIGILL (see the comment at the top of `app/build.gradle.kts`). A new crate that uses SIMD or assembly (crypto, inference) is run on BlueStacks and on a real phone before anything is built on top of it.
- `native/target/` is never committed.
- The owner's PC has Rust on the MSVC host toolchain, Visual Studio 2022 Build Tools, `cargo-ndk`, `cargo-deny`, and NDK 28.2.13676358 under `%LOCALAPPDATA%/Android/Sdk/ndk/`; set `ANDROID_NDK_HOME` to it. If something still does not build there, report it instead of installing anything.

## Test data

- **In the repository:** small fixtures with no account data. Synthetic frames, crops that device QA already commits under `docs/reference-images/`, and captured traffic only after it is sanitized.
- **Local only (`build/`):** labelled real-play samples and raw captured traffic. They contain account data and are never committed.
- **Sanitized traffic** means: user and session identifiers, auth keys, signatures, friend codes and names replaced with fixed dummy values, and the result reviewed by the planner before the first commit of that kind.
- CI therefore checks logic and memory limits on fixtures. Accuracy on real-play samples is measured locally by the planner and recorded under `docs/qa/`.

## Models

A classifier model changes what a reader returns, so it is handled like a template image.

- The training script and its fixed settings live in `tools/`. The trained model file is committed next to the reader that uses it.
- Each model has a record: the training script version, the count of training and evaluation samples per label, and the evaluation result table. Evaluation samples are never used for training.
- A PR that replaces a model shows the before and after accuracy table on the same evaluation set.

## What a Rust PR must show

On top of the planner's PR review in [`agent-team.md`](agent-team.md) and the rules in [`verification.md`](verification.md):

- `unsafe` appears only in `fga-ffi`, each block with its `// SAFETY:` comment;
- every Kotlin-callable function catches panics;
- new dependencies, each with its reason;
- the change in `.so` size per ABI. There is no size limit (owner, 2026-11-18): keep the APK close to its current size where that costs no performance, and tell the owner about a large jump;
- for a reader change: the accuracy table on the evaluation samples, before and after, with false positives listed;
- for a capture change: peak memory on the recorded-traffic test, and what stays resident;
- for a unit in the "compare" stage: the list of cases where Rust and Kotlin differ, each explained;
- for an automation-flow change: the replay result (tap order and positions against the Kotlin run).

Risk classes:

- Any change under `native/` is a **risky change** and gets a verification agent.
- Reader logic, automation flow, `fga-ffi`, and moving a unit to the next lifecycle stage are **highest-risk** and get two verifiers.
- The verifier re-runs the Rust commands itself, disables each new guard to see its test fail, and for capture work reports memory even when it passes.

## Agents for Rust work

Which models implement and verify Rust work is in [`agent-team.md`](agent-team.md), which only the planner needs.

## Reports to the owner

Each Rust PR gets a Korean summary from the planner:

- what behaviour changes for a user, and what does not;
- the numbers (accuracy, memory, size) against the baseline;
- what the owner needs to decide or check on a device.

## Crash reports

- A panic is caught and reported as an error. A native crash (for example a segmentation fault) cannot be caught and kills the app.
- For every beta and stable build, keep the unstripped `.so` files outside the APK, named by version code, so a crash trace from a user's device can be read.
- "Native crashes: 0" is a condition for every lifecycle move in 0025.

## Still to apply

1. Before Gemini implements Rust work: add the Rust commands to the Gemini runner allow-list.
2. When the first Rust unit reaches a stable release: update "What this is" and "Module layout" in `AGENTS.md`.
