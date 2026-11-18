# Rust core (`native/`)

In force since 2026-11-18, when the owner approved [design 0025](../design/0025-rust-engine-migration.md). "Still to apply" at the end lists the parts that wait for later work.

Read this before touching anything under `native/`, the Kotlin bindings to it, or the Gradle and CI steps that build it.

The owner does not read Rust (owner statement, 2026-11-18). So the rules here are enforced by tools and CI wherever possible, and reports to the owner describe behaviour, not code.

## Layout

`native/` is one Cargo workspace. Rust code lives nowhere else.

| Crate | Holds | `unsafe` |
| --- | --- | --- |
| `fga-sensing` | frame handling, template matching, classifiers | forbidden |
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

In the app, Gradle builds the library only when asked:

```bash
./gradlew -Pfga.native=true -PfgaDeviceTests=true :app:assembleDeviceTest :app:assembleDeviceTestAndroidTest
py -3 tools/device-qa/run.py --build --native --suite nativeProbe
```

`-Pfga.native=true` registers `cargoNdkBuild`, which runs the `cargo ndk` build above into `app/build/generated/rustJniLibs` and adds it to every variant's native libraries. Without the property no task looks at `native/`, and the APKs carry no Rust library. The `nativeProbe` device suite runs the probe tests in `app/src/androidTest`; on an APK built without the library they report themselves skipped and their results are logged under the logcat tag `FgaNativeProbe`.

CI runs the same set in `.github/workflows/native-ci.yml`, only when `native/` or that workflow changes. It also fails when the arm64 library is not 16 KB aligned; check that locally with `llvm-readelf -lW` from the NDK (every `LOAD` line must end in `0x4000`).

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

On top of the checks in `AGENTS.md` ("What the planner's PR review checks" and the verification agent rules):

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
