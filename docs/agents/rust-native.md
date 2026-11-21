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

`-Pfga.native=true` registers `cargoNdkBuild`, which runs the `cargo ndk` build above into `app/build/generated/rustJniLibs` and adds it to every variant's native libraries. It also packages the pinned OCR model (issue #568), which Gradle downloads unless `-Pfga.ocr.model=<path>` (or `run.py --ocr-model <path>`) names a local copy ("Rust test APK" below). Without the property no task looks at `native/`, and the APKs carry no Rust library. The `nativeProbe` device suite runs the probe tests in `app/src/androidTest`; on an APK built without the library they report themselves skipped and their results are logged under the logcat tag `FgaNativeProbe`. The skipped reason carries the loader's message and says "not packaged" only when the class loader could not find `libfga_ffi.so` itself; a library that is packaged but does not load (for example a missing or broken `libonnxruntime.so`) is reported as such.

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

`tract-recognise` and `ort-recognise` pass with all 370 texts equal to Python, `tract-recognise-allowed` and `ort-recognise-allowed` with at least 366, and `ort-recognise-1thread` with 370. These floors are the values measured on the three devices of #530 (tablet, MuMu, BlueStacks), which equal the PC's: a lower count is a regression to investigate, not a number to lower. The report and the `FgaOcrProbe` log line give the exact count. The tract cases load through `ocrLoadTract` at 320, the width the Python results were read at; `ocrLoad` plans the reader's width (see "The reader's width" below). The cases share one process, so the case file runs the Tesseract case first, each engine's memory case before its other cases, and the tract cases before the ONNX Runtime ones. Results are logged under the logcat tag `FgaOcrProbe`.

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

Three switches measure where the recogniser's time goes. The width is also the reader's setting (see "The reader's width" below); threads stay off by default.

- **Minimum model width.** `Recognizer::with_min_width` (and `preprocess::prepare_crop_with`, `to_input_tensor_with`) pad a short crop to a chosen width instead of RapidOCR's 320. Resize, normalisation and the padding value (0 after normalisation) stay the same; the allowed range is 8 (the model emits one output step per 8 columns) to `MAX_MODEL_WIDTH`. `TractSettings { min_width, threads }` with `TractBackend::load_file_with` plans that width at load, and `Backend::min_width` reports it, so `Recognizer::new` pads to the width the backend planned (ONNX Runtime plans none and keeps 320). The default is the reader's 256 (`READER_MIN_WIDTH`). 320 is the width of every comparison with Python's texts and confidences: the fixed-sample model tests load at it explicitly.
- **tract threads.** `fga-sensing`'s feature `tract-threads` (through `fga-ffi`'s feature of the same name, Gradle's `-Pfga.native.threads=true`, only with `-Pfga.native=true`) builds `tract-linalg` with its `multithread-mm` feature and gives the tract backend a rayon pool of the requested size, built at load and dropped with the backend. It adds no crate: `tract-linalg` and `rayon` are already in the build (rayon through `tract-transformers`), so `Cargo.lock` only gains dependency lines. Without the feature, more than one thread is an error (`ERR` in the probes), never a silent single-thread run.
- **Probes.** `NativeProbe.ocrLoadTract(path, minWidth, threads)` fills the slot that `ocrRead` and `ocrUnload` use; `minWidth` is 8..4096, `threads` 0..16 (0 and 1 are the calling thread), both checked before the lock. `ocrTractFeatures()` says whether the library has the thread feature. `ocrLoad` loads at the tract defaults, as the product engine does, and reports `minWidth`.

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

#### The reader's width (issue #591)

The support-score reader plans the model at 256: `TractSettings::default()`, so the product engine (`score_engine::settings`, pinned by a test) and `ocrLoad`. Its answers, not Python's texts, are what count, and on every set they are as good as at 320 or better (`width_decode_eval` on the PC): on the 370 stored samples +62 reads 85 of 86 at both widths and +16 167 of 167 against 166 (`0299.png` reads `+57/+16`, weakest character 0.506, instead of the unknown `+571+16`), with no wrong value and no false accept; the 831 live-scan crops give the same answers at both widths; the 18 crops cut by the screen edge stay refused; and the cut sweep of the model test gives F62 = F16 = 0 and OTHER 46 against 47. On the live-scan crops the confidences move slightly toward the floors at 256 (median text confidence -0.004, median weakest character -0.008) and none falls below them: the lowest accepted text confidence is 0.906 against the floor 0.86, the lowest accepted weakest character 0.551 against 0.5. The `rust-reader` and `rust-engine` device cases keep `minRead16` at 166: `0299.png`'s weakest character sits just above the floor, and a device or tract build that computes it below 0.5 refuses it, as the reader is meant to. In the `ocrProbe` suite on the tablet, MuMu and BlueStacks the median time per field fell by about 19 % with the same answers on every device (tablet 26.7 to 21.6 ms, MuMu 60.4 to 48.7 ms, BlueStacks 76.3 to 62.8 ms). Narrower widths lose answers or accept unchecked ones (same tool): at 224 a live-scan `+1` falls below the confidence floor, at 192 eleven live-scan crops do and the stored +62 reads fall to 83, and at 160 the stored `0168.png` (`62/+16`, refused for its format at 256 and 320) is accepted once its faint leading plus is read, with a Grand score the labels do not give. Decoding constrained to the score format (the tool's P1 to P3) was measured and not adopted: it reads no more fields on the stored or live-scan samples and adds wrong numbers in the cut sweep (OTHER 103 at 256 for P1, 48 to 74 for P3).

```bash
cargo run --release -p fga-sensing --features tract --example width_decode_eval -- \
    "$FGA_OCR_MODEL" "$FGA_OCR_SAMPLES" <live-scan dir> <partial dir> ../build/width-eval --widths 320,256
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
- **Device case:** `rust-reader` in `ocrProbe` (after `kotlin-reader`) calls `NativeProbe.supportScoreRead(pixels, width, height)` on the recogniser that `ocrLoad` loaded, at the reader's width like the product engine; there is no load call of its own, and `ocrUnload` serves it. It reports the same tallies as `kotlin-reader` (+62 and +16 read, missed, false accepts, class and Grand values, differences from `kt_cls`/`kt_grand`), the unknown reasons and the time per field. It fails on an `ERR`, a false accept, a wrong value (a labelled value read as another number, or an `is62` row with a class other than 62, or an `is16` row with a Grand score other than 16, labelled number or not), and when it reads fewer than `minRead62` and `minRead16` (the floors in the case file: +62 is the PC count; +16 is one below it, because `0299.png` passes the weakest-character floor by 0.006 and a device that refuses it is working as designed, see "The reader's width"; a count below a floor is a regression to investigate). It runs with the rest of the suite (the case file has no per-case filter), after the model and samples are pushed:

```bash
py -3 tools/device-qa/run.py --build --native --suite ocrProbe --local-fixtures build/ocr-device-fixtures
```

### Rust test APK (issue #568)

Owner decision: users of the public app see nothing of the engine change. The Rust reader is tried in a separate, owner-only APK built from the `rust` branch; it is never published and is not a release ([`releases-and-versioning.md`](releases-and-versioning.md)).

**Build.** From `rust`, with the shell set up as below:

```bash
./gradlew -Pfga.native=true -Pfga.ocr.model=build/orchestration/rust/ocr/models/PP-OCRv6_rec_small.onnx -PfgaDebugSigning=true -Pfga.rust.build=1 :app:assembleRust
```

- The `rust` build type is `initWith(beta)`: package `io.github.fate_grand_automata.rust`, launcher name `FGA Rust` (`app/src/rust/res/values/strings.xml`), the beta's signing (`signRustRotated` re-signs it with the rotated release key like the beta, or leaves it debug-signed with `-PfgaDebugSigning=true`), version name `<beta.versionName>-rust.<N>` and the beta's version code. N is `-Pfga.rust.build` (a whole number from 1 to 99, default 1; anything else stops the build); count it up for each Rust test build of the same beta. `version.properties` has no key for it because `main` shares that file.
- Without `-Pfga.native=true` the `rust` variant does not exist (`beforeVariants`), so `assemble`, `test` and `check` run as before and every other variant is unchanged. `assembleRust`, `installRust`, `bundleRust` and `packageRust` (with or without `:app:`) stop the build with a message that names the property; an abbreviation or any other rust task gets Gradle's own "not found". No automatic test covers the variant filter, so after changing it check by hand that `./gradlew -m :app:assemble` and `./gradlew -m check` without the property list no task with `Rust` in its name, and that the `-m :app:assembleCi :app:assembleBeta` list is unchanged.
- `packageRust` and `packageRustBundle` (so `assembleRust`, `installRust` and `bundleRust` too) depend on `checkRustKeepRules`, which reads R8's `app/build/outputs/mapping/rust/seeds.txt` and `mapping.txt` and fails the build unless `RustSensing`, its `INSTANCE` and its four native methods are kept under their own names (`app/proguard-rust.pro`). Without the rule R8 drops three of the natives and the build would still pass.
- `BuildConfig.RUST_TEST_BUILD` is true only in this build type. It is the one switch that lets product code reach the engine.

**The model.** `native/ocr-model/model.properties` pins the file name, URL, size and SHA-256 (RapidOCR's model listing names the same URL and hash); `native/ocr-model/NOTICE.txt` is its licence notice (Apache-2.0, PaddleOCR, converted by RapidOCR). To change the model, change `url`, `sha256` and `size` together, and only for a model the support-score reader has been measured with. With `fga.native` on, `provideOcrModel` takes the file from `-Pfga.ocr.model=<path>` or, without it, downloads the URL; it refuses a file whose size or SHA-256 differs (a part file never gets the final name and is deleted on failure) and writes `PP-OCRv6_rec_small.onnx`, `ocr-model.properties` and `PP-OCRv6_rec_small.NOTICE.txt` into `app/build/generated/ocrModelAssets/ocr/`, which the APK carries as `assets/ocr/`. The URL and every redirect must be `https`; a malformed redirect stops the build with the same kind of message. Like `libfga_ffi.so`, the asset goes into every variant Gradle builds with the property on (so the device-test APK has it too); off, the task does not exist. The model is never committed: `tools/ci/repo_guard.py` refuses `.onnx` files, `build/` is ignored, and `tools/ci/apk_check.py` fails a CI APK that holds any `.onnx` entry.

**Product API beside the probes.** `io.github.fate_grand_automata.sensing.RustSensing` (app main source set) calls fga-ffi's `score_engine`: `load(path)` (`OK: <ms>` / `ERR: ...`), `readScore(pixels, 60, 30)` (one `int[]`, laid out in `score_engine/wire.rs`), `unload()` and `isLoaded()` (1, 0, 2 when another call holds the engine lock, or negative for an error). `isLoaded` only tries the lock, so it never waits for a load or a read; `tests/score_engine_busy.rs` checks that through a test hook that holds the lock. The reply carries value and reason separately, so unknown never reads as 0; `ScoreWire.kt` turns anything that does not fit the layout into "unknown, engine error". The **slot rule**: the engine has its own static in Rust, apart from the probes' recogniser. A probe load, read or unload never touches it and the reverse; a second `load` while one is loaded is refused and keeps the loaded engine. The slot holds a `ScoreModel` trait object, so `tests/score_engine_hooks.rs` checks these rules with a marker engine and no model file, which CI runs; `tests/score_engine_model.rs` checks them again on the real model when `FGA_OCR_MODEL` is set. A crop other than 60x30 is answered as `crop_size` without reading the pixels; a pixel array of another length, a read before load and an unload while unloaded have their own answers and host tests (`score_engine/mod.rs`). A null array and a pending Java exception are handled in `product_jni.rs`, which needs a JVM: the host tests cover only the pure parts (the length check through the fetch closure, `wire::after_pending_exception`, `after_pending_exception_code`), not the JNI calls themselves. The reason and display codes, the `unload`/`isLoaded` answers and the already-loaded text are listed once in `score_engine/wire-codes.txt`, which the Rust and the Kotlin tests both read.

**Kotlin binding.** `scripts` has the port `SupportScoreEngine` (`unavailableReason`, `available`, `loadTime`, `read(field: Pattern): EngineScore` with the engine's own time in `engineTime`) and `NoSupportScoreEngine`. The Hilt provider in `ScriptsModule` binds `NoSupportScoreEngine` unless `BuildConfig.RUST_TEST_BUILD`, so no other build ever calls `System.loadLibrary`. `RustSupportScoreEngine` loads lazily on its first use in a run (never on the main thread): it loads the library, copies the packaged model once to `noBackupFilesDir/ocr-model/<sha256>-PP-OCRv6_rec_small.onnx` (streamed through a 64 KB buffer, checked by size and SHA-256 while it is written; a later run reuses a file of the pinned size and deletes anything else in that folder) and loads it. Any failure makes it unavailable for the run, with a reason; nothing it does throws except errors that leave the VM unusable (`VirtualMachineError`, `ThreadDeath`). `ScriptManager` closes it through `RunEngines`, which unloads, when the run's component is released: on the main thread in `startScript` (the previous run's engines) and in the service's `onDestroy`, and on `Dispatchers.Default` in `onScriptExit` (after the script ends, by a stop as well; `stopScript` itself only asks the `ExitManager` to exit) and in the early exits of the launched start. `RunEngines` takes the engines out under its monitor and closes them after leaving it, because `TesseractOcrService.close` waits for a running OCR call. `close()` never waits for a load or read in progress (the state machine is in the class comment). Because of that, the next run can start while the previous run's engine is still loaded: its first load then waits on the script thread, for at most 3 s, while `isLoaded` answers loaded or busy or `load` answers the already-loaded text, and otherwise is unavailable with a reason that says so; it never unloads an engine it did not load. A close, an interrupt or `stopRequested` ends the wait at the next poll (20 ms), and a run already stopped before its first use loads neither the library nor the model. `stopRequested` is the script component's `ExitManager.isExitRequested` (wired in `ScriptsModule`), which reads the stop without waiting; `checkExitRequested` would wait while the run is paused. The model copy and `native.load` cannot be cut short, and a stop during them takes effect after them: in the first-round run of the `ocrProbe` device suite's `rust-engine` case (the first load of a run, library load and model copy included, no copy present before) they took 146 ms on the tablet, 388 ms on MuMu and 581 ms on BlueStacks. In the `rust` build type `proguard-rust.pro` keeps `RustSensing` and its native methods by name for JNI. The support score scan below is the only script that uses it.

**Device case.** `rust-engine` in `ocrProbe`, after `rust-reader`: it uses `RustSupportScoreEngine` on the model packaged in the device-test APK (no pushed model), reads the 370 samples through FixtureApi's crop frame and `SupportScoreLayout.numberRegion(...).getPattern()`, and fails unless the engine is available, every field equals `rust-reader`'s read of the same sample (which must pass earlier in the run), the Pattern's gray bytes equal the bytes `rust-reader` fed, and `rust-reader`'s floors and rules hold. It reports the load time, the time per field and the memory around the load (VmRSS, VmHWM after resetting it through `clear_refs`, Java heap, and peaks sampled during the load). The copy it makes is deleted when the run ends.

```bash
py -3 tools/device-qa/run.py --build --native --ocr-model build/orchestration/rust/ocr/models/PP-OCRv6_rec_small.onnx --suite ocrProbe --local-fixtures build/ocr-device-fixtures
```

**Support score scan.** The one script that uses the engine: the owner starts it on the support selection screen, it scrolls the list and reads every score field with both the Kotlin reader and the Rust engine, and it writes a log the planner pulls with adb. It is not a user feature (no manual page, README or release note).

- **Only in the `rust` build type.** `app/src/rust/java/.../rusttest/SupportScoreScanScript.kt` (an `OwnerTestScript`), its strings (`app/src/rust/res/values`, `values-ko`) and its Hilt bindings (the `@IntoSet` binding, the `ScoreScanStorage`, `ScoreScanDevice` and `ScoreScanNativeFrames` providers, the `SupportScoreScanEntryPoint` that builds the entry point) are in the `rust` source set, so no other build compiles them. Main has only the seam: `OwnerTestScript`, an empty `@Multibinds` set, and `OwnerTestScripts`, which returns nothing and never reads the set unless `BuildConfig.RUST_TEST_BUILD`. The launcher shows one button per offered script; the scan is offered when the detected screen is the support image maker's (the support selection screen), as "Support score scan" / "서포트 점수 스캔". Its response `ScriptLauncherResponse.OwnerTest` sets no preference and there is no `ScriptModeEnum` value for it; `ScriptManager` takes the entry point from the script and shows its exit message.
- **What it does.** Start, in this order: the support list on screen without a dialog, the list loaded, a support present, at least 50 MB free, the engine available (free space comes before the engine, whose first use copies its model of about 21 MB), at least one row from `SupportBoundsFinder.all()`, and then no stop requested (a stop during the engine's first load). Otherwise it ends at once with the reason (for the engine, its `unavailableReason`) and creates, writes and deletes nothing. Per screen: one `useSameSnapIn`; the rows from `SupportBoundsFinder.all()`; for each row the field `SupportScoreLayout.numberRegion(bounds).getPattern()` (60x30 at 720p), read by the Kotlin reader's `read(bounds, true, showsGrandScore(server))` on that snapshot and by the Rust engine, and then saved as PNG just before its line (a read that fails leaves neither; a failing line write, or the process killed between the two writes, can leave a crop without its line). After the last row's line of a screen is on disk, the native capture below (one per read screen; an unchanged screen gets none, and neither does a screen cut short by a stop or the engine). Before every scroll it checks the stop and takes a new snapshot to check that the support list is still there without a dialog, because reading a screen's rows takes long enough for either to change; then `SupportScreen.scrollDown()` and `SupportSelectionLoop.SCROLL_SETTLE` (0.3 s), the wait that loop uses after its scrolls. A stop is also checked before each row and in that wait. When the engine is unavailable, the stop is looked at first, so a stop during its first load ends as `user_stopped`.
- **Native crops (issue #600).** Android renders the run's capture at about 720p (the virtual display itself is created at that size), so no native frame exists during a run. To let PC experiments choose resolution, crop and width by measurement, the scan takes ONE full-display grayscale frame per read screen through the port `ScoreScanNativeFrames` (`scripts`; `HolderScoreScanNativeFrames` in the `rust` source set, the only binding), and saves for each logged row of that screen `native/sNNN-rM.png`: the row's score field mapped into the frame and grown by 20 px at 720p scale on each side (100x70 at 720p scale), cut where it leaves the frame (`clamped` names those sides). On KR and JP, 16:9 and wide (`docs/reference-images/Jp/support/`, `Kr/wide-2560/27.webp`), that margin holds the score panel with its "스코어"/"スコア" label, the laurel background of the row and the dark band under the row. On some screens the left margin also holds the right edge of the neighbouring icon (JP `wide-21x9` 09 and 11, KR `wide-2560/28`, `support-score-audit` 202 to 216): game UI fragments may appear at the left. No friend name, login time, FP text or servant name was found in 86 crops cut from every support reference screenshot, so no side is reduced. The full frame itself is never written.
  - **Mapping.** `ScoreScanNativeMap.of(FgoCaptureGeometry, frame)`: the 720p field (`Transformer.toImage` of `SupportScoreLayout.numberRegion`, as `getPattern` crops it) plus the game-area crop corner the 720p capture was cut at, times frame/capture size per axis; each edge is rounded on its own. The checks are pure Kotlin in `ScoreScanNativeChecks` (`scripts`, tested on the JVM by `ScoreScanNativeChecksTest`); the Android side only reads the states and calls them. Before capturing, they check that a MediaProjection capture is still open (else `capture_stopped`), that `FgoCaptureGeometry.forLandscape(display, CutoutManager::getCutoutAppliedRegion).crop` equals the script's own crop (`gameArea * screenToImage`, what `ScreenshotManager` cuts) and, with MediaProjection, that the capture is at `captureSize`; otherwise `geometry_mismatch`. A frame whose size is not the landscape display size is `frame_size`. `ScoreScanNativeMapTest` (app) checks the mapping on 2560x1440, 1920x1080, 1280x720, 2400x1080 with and without a 80 px cutout (also started from portrait), the tablet's 3040x1904 full screen, split screen, split screen started from portrait and reverse landscape, against the script's own transform to screen pixels (within one 720p pixel: the crop corner and the field are each rounded to whole 720p pixels). The real cases' x and y scales differ by under 0.1 %, so an artificial frame of another aspect (2560x2160 for a 1280x720 capture) checks that the axes are not swapped.
  - **How it is taken during a run.** `ScreenshotServiceHolder.takeFullDisplayScreenshot()` on the script thread (the only thread that takes the run's screenshots), between the screen's last line and the pre-scroll check. MediaProjection: under the capture lock it resizes the virtual display to the full display, takes one frame into its own Mat, and resizes back. Each resize installs a new `ImageReader`, forgets the last frame (`hasFrame = false`, `bufferMat` released) and arms a new first-frame latch, so the next 720p screenshot (the pre-scroll check's) waits up to 3 s for a frame composed after the restore, at the restored size; it can neither be stale nor of the full size. The 720p snapshot the rows were read from is a crop of `grayscaleMat`, which the full-display path does not touch. A first resize that throws after detaching the surface leaves the display without the reader (later screenshots serve the old frame as "nothing new"); a restore that throws leaves the full-size reader (later screenshots are of the full size). So the Android side compares `MediaProjectionScreenshotService.captureState()` (size, density, and whether the display renders into the current reader; a read-only method added for this, no existing caller changes) before and after: any difference ends the scan as `native_restore_failed`, with the two states as detail, before any snapshot or gesture. On a 1280x720 display the capture already has the full size, so both resizes return early: no new reader, and the frame is the newest image or, if nothing changed, the last frame captured. A projection that is already stopped when the capture starts is `capture_stopped` (also when it stops during the capture): nothing is resized, and the next 720p snapshot throws the capture's "screen capture stopped" error, so the scan ends as `error` and the owner sees the app's usual error dialog for a stopped capture. Root (`ResizedScreenshotProvider`): it copies one screencap and changes no state, so the same guarantees hold without a restore.
  - **Game-area settings during a scan.** The first frame after a resize is signalled on the main looper while the capture holds the capture lock. If a game-area setting is finished during the capture, `ScriptRunnerServiceController` calls `resizeCapture()` on the main thread, which waits for that lock: the main thread stalls, the capture's first-frame wait runs into its 3 s timeout, the restore runs, and then the main thread resizes the capture to the new geometry. The capture states before and after then differ, so the scan ends as `native_restore_failed` without a gesture. Do not change game-area settings while a scan runs.
  - **What can move in between.** The frame is taken after the rows are read, seconds after the snapshot. The list can still be settling if the 720p snapshot was taken during the scroll's inertia (the settle wait is 0.3 s), and the row background has a light animation; the score panel itself is static. `match` measures it per row: the native field area-averaged down to the 720p field's size, Pearson correlation with the 720p pixels that were read (it ignores the small brightness and contrast differences of the two scaling paths and falls fast when the content moved; null when either side is flat). A low value never drops a row.
  - **Stop.** The stop is checked after a screen's last row and before its capture, and again before the screen limit, so a stop during the last row pays for no capture and ends as `user_stopped`, also at the last allowed screen.
  - **Failures.** One attempt per screen, never retried. No service, an unsupported service, a null frame, an exception (`error` with its text), a frame or geometry that does not match, a projection that has stopped (`capture_stopped`), and a capture that took longer than 1.5 s (`slow`; half the capture's own first-frame timeout, while a first frame normally arrives within a few display frames; the frame is closed unused) are logged on that screen's native lines with `native: null` and the reason, and the scan goes on with 720p only. A field that is not wholly inside the frame is `field_outside_frame` for that row. The 1.5 s bound is checked after the capture returns; it does not interrupt it. A capture whose first frame does not arrive within the capture's own 3 s ends with that exception and is logged as `error`, not `slow`.
- **What it never does.** No tap, refresh, `scrollToTop`, `click` or dialog: the scroll is its only gesture, so it cannot enter a quest or spend anything; the native capture is a capture, not a gesture. No reader result decides anything. It saves no screenshot: a 720p crop holds only the score field of one row (the "+NN" or "+NN/+MM" text on its panel), a native crop that field and the neighbourhood above, no friend name and no account data.
- **End reasons** (`endReason` in `summary.json`). `isAnyDialogOpen()` is "the SupportExtra tab is not in its region", which is also true on any screen other than the support list, so those two cannot be told apart and share one reason. How the real checks map to the reasons:

| Real state | `RealSupportScreen` answers | Reason |
|---|---|---|
| Another screen, or a dialog over the list | `isAnyDialogOpen` true | `not_support_list_or_dialog` (at the start, before reading a screen, before each scroll) |
| Support list, still loading | tab there, `isListLoaded` false | `list_not_loaded` (start only) |
| Support list with its "not found" text | tab there, `noSupportsPresent` true | `no_rows` (start, nothing written) |
| Only a guest row at the start (it has no confirm-setup button) | `someSupportsPresent` true, `SupportBoundsFinder.all()` empty | `no_rows` (start, nothing written) |
| A later screen with only such rows | `someSupportsPresent` true, `SupportBoundsFinder.all()` empty | `no_rows` (ends the scan, no gesture; the rows read so far and the summary stay) |
| Supports gone during the scan (reloading, or the "not found" text) | tab there, `someSupportsPresent` false | `rows_lost` (before reading a screen or before a scroll) |

The others: `low_space` (at the start before the engine, and before each screen), `engine_unavailable` (at the start, before a row, or after a read the engine answers as unavailable, which is logged), `user_stopped`, `screen_limit` (80 screens read; the last screen's native capture is taken first), `native_restore_failed` (the native capture could not put the 720p capture back; `detail` has the states, the screen's native lines say `restore_failed`, no further snapshot or gesture), and `bottom`: the rows of a screen, by their y and their crop's SHA-256, equal the previous screen's. The game can drop a swipe, so the first unchanged screen only leads to one more scroll (with the checks above); `bottom` needs a second unchanged screen in a row. An unchanged screen is never read or logged again, so the bottom of the list, and a list shorter than one screen, end after exactly two scrolls that changed nothing. A later screen that shows only rows without the "confirm support setup" button ends the scan as `no_rows`, not as `bottom`. If the list bounces at the bottom, the bottom screen can be read and logged again; `scan.jsonl` is the raw log, and the analysis groups rows by `cropSha256`. `summary.json` can also say `error`: an exception the scan did not expect, written to `detail` and rethrown, so the owner sees the app's usual error dialog and not the scan's result. The owner's result shows the reason, the screens, rows read, rows where the readers agree and differ, and the folder; a scan that did not start shows only the reason (and the engine's reason).
- **Folder.** The app-specific external files folder, which `adb pull` reads without root on Android 7 to 15 (the SAF folder the user picks may be anywhere): `/sdcard/Android/data/io.github.fate_grand_automata.rust/files/support-score-scan/<yyyyMMdd-HHmmss>/` (local time; a second scan in the same second gets `-2`). It is removed with the app, and it can be part of the device's Auto Backup (neither `backup_rules.xml` nor `data_extraction_rules.xml`, which applies on Android 12 and later, excludes it; it holds score-field crops and their immediate neighbourhood only). After the new folder is created, the oldest scan folders are deleted so that 20 remain with the new one, so a failed create deletes nothing; other files there are not touched. Without that storage the scan ends with `low_space`. The pull below brings each scan's `crops/` and `native/` folders along.

```bash
adb -s <serial> pull /sdcard/Android/data/io.github.fate_grand_automata.rust/files/support-score-scan build/support-score-scan
```

- **Disk.** A native crop is the 100x70 region at the display's scale, written by `Pattern.save` (an Android PNG of the gray values in RGB(A), like the 720p crops); the worst case below is the uncompressed size, 4 bytes a pixel. Rows per screen are at most 4, screens at most 80, so 320 crops a scan; the 720p crops and lines add about 2 MB.

  | 3040x1904 tablet | Capture, scale | Native crop | Worst a crop | Worst a scan | 20 scans |
  |---|---|---|---|---|---|
  | Full screen | 1280x802, about 2.375 | about 238x166 px | about 158 KB | 320 x 158 KB = about 51 MB + 2 MB = about 53 MB | about 1.06 GB |
  | Split screen (offsets 1037/97/0/11) | 1943x1217, about 1.565 | about 157x110 px | about 69 KB | 320 x 69 KB = about 22 MB + 2 MB = about 25 MB | about 500 MB |

  Real content compresses well below the worst case (an upscaled reference neighbourhood at 157x110 encodes to 10 to 11 KB; real native detail compresses less). A display at 3x 720p (3840x2160) would give 300x210 px, at most about 250 KB a crop and about 83 MB a scan. The 50 MB floor is kept: it is checked before every screen, and one screen writes at most about 0.67 MB on the tablet at full screen (4 x 158 KB native, 4 x about 7 KB 720p, 8 lines), about 1 MB at 3x 720p, so a scan never takes the device more than one screen below it. The 20-folder retention is kept.
- **`scan.jsonl`**, appended and flushed as the scan goes. Schema 2 has two kinds of line: one `row` line per row read, and after a screen's row lines, one `native` line per row of that screen. The native result cannot be on the row line: the row lines are written before the capture is tried, so that a capture that fails or kills the process cannot cost the reads. **Telling the versions apart:** read `schema` on each line; a schema 1 line has no `kind` and is always a row line, with exactly the fields of a schema 2 `row` line except `kind`. A schema 1 folder has no `native/` folder and its `summary.json` has `schema` 1 and no `native`. A tool that only knows schema 1 keeps working on schema 2 folders if it skips lines whose `kind` is `native`.

Row line (`kind` `row`):

| Field | Value |
|---|---|
| `schema` | 2 (1 in folders of the first format) |
| `kind` | `row` (absent in schema 1) |
| `time` | epoch milliseconds of the line |
| `server` | `GameServer.serialize()` (`Kr`, `Jp`) |
| `screen`, `row` | 0-based; rows top to bottom |
| `bounds` | `{x, y, w, h}` of the row, 1440p script coordinates |
| `crop`, `cropSha256` | `crops/sNNN-rM.png` and the SHA-256 of that file's bytes; rows seen on two screens are logged twice, group them by the hash |
| `kotlin` | `classScore`, `grandScore` (number or null = unknown), `display` (`ordinary`, `compound`, `unknown`), `checkGrand` (whether Grand was asked, true on JP only), `micros` (time of `read`) |
| `rust` | `status` (`read`, `engine_error`, `unavailable`), `classScore`, `grandScore` (number or null), `display`, `classReason`, `grandReason` (null for a value, else `blank`, `low_confidence`, `format`, `range`, `clipped`, `not_shown`, `crop_size`, `engine_error`, `unavailable`), `confidence` (the text's), `minCharConfidence` (its weakest character's), `text`, `detail` (the error text, else null), `micros` (the engine's own time, null when it did not read), `callMicros` (the whole call) |
| `agree` | class, Grand and display equal; an unknown equals only an unknown, whatever its reason |

Native line (`kind` `native`, schema 2 only), one per row line of a read screen, in the same order; join it to its row by `screen` and `row`. A screen cut short by a stop, the engine or an exception has none; a process killed between the row lines and the capture leaves none.

| Field | Value |
|---|---|
| `schema` | 2 |
| `kind` | `native` |
| `time` | epoch milliseconds of the line |
| `server` | as on the row line |
| `screen`, `row` | the row line this belongs to |
| `cropSha256` | the row line's `cropSha256` (the 720p crop), for grouping |
| `native` | null when there is no native crop for this row, else the object below |
| `native.crop`, `native.cropSha256` | `native/sNNN-rM.png` and the SHA-256 of that file's bytes |
| `native.frame` | `{w, h}`: the full-display frame, landscape |
| `native.scale` | `{x, y}`: native pixels per 720p capture pixel, frame size over capture size per axis (both always written; they differ by the capture's rounding) |
| `native.region` | `{x, y, w, h}`: the saved rectangle in the frame (the field grown by 20 px at 720p scale on each side, cut at the frame) |
| `native.field` | `{x, y, w, h}`: the score field (the 720p field mapped to the frame) inside the saved image |
| `native.clamped` | the sides of `region` cut by the frame edge: `left`, `top`, `right`, `bottom`; empty when none |
| `native.match` | Pearson correlation of the native field, area-averaged to the 720p field's size, with the 720p pixels that were read; null when either side is flat |
| `native.captureMicros` | the screen's capture time (same on every row of that screen) |
| `reason` | null with a native crop, else `no_service`, `unsupported`, `capture_stopped`, `null_frame`, `error`, `frame_size`, `geometry_mismatch`, `slow`, `restore_failed` (the whole screen) or `field_outside_frame` (this row) |
| `detail` | the exception or mismatch text for `error`, `unsupported`, `frame_size`, `geometry_mismatch` and `restore_failed`, the exception for a `capture_stopped` during the capture, else null |
| `captureMicros` | the screen's capture time, also when `native` is null |

- **`crops/sNNN-rM.png`**: the field the engine read, lossless, written after both reads and just before its line.
- **`native/sNNN-rM.png`**: the native crop of that row, lossless gray values, written just before its native line. Same name as its 720p crop.
- **`summary.json`**, written at every end once the folder exists (through a `.part` file and a rename): `schema` (2; 1 in the first format), `appVersion`, `deviceModel`, `androidVersion`, `server`, `folder`, `startTime`, `endTime` (epoch ms), `endReason`, `detail` (the engine's reason, the restore states for `native_restore_failed`, or the exception), `screens` (screens with at least one row logged), `rows`, `counts` (`agree`, `differ`; per row `kotlinOnly`: the Kotlin reader has a value and the engine none, `rustOnly` the reverse, `bothUnknown` neither; `kotlinClass62`, `rustClass62`, `kotlinGrand16`, `rustGrand16`), `engineLoadMicros`, `kotlinMicros` and `rustMicros` (`count`, `median` (the lower one), `max`), `engineUnavailableReason`, and `native` (schema 2): `taken` (screens with a native frame used), `failed` (screens without one, by reason, including `slow` and `restore_failed`), `frame` (`{w, h}` of the last frame used, null if none), `scale` (`{x, y}` of it), `captureMicros` (every capture attempt: `count`, `median`, `max`), `match` (rows with a value: `count`, `median` (the lower one), `min`).
- The serialisation, the scan and the folder rules are pure Kotlin in `scripts` (`SupportScoreScanner`, `ScoreScanLog`, `ScoreScanNative`, `DirectoryScoreScanStorage`) and tested on the JVM (`SupportScoreScanTest`); `OwnerTestScriptsTest` in `app` guards the seam and that this build has none of the `rusttest` classes, `ScoreScanNativeMapTest` the mapping, `ScoreScanNativeChecksTest` (scripts) the capture checks. The `rust` source set itself has no unit tests.

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
