# 0025 1-0단계: Rust 시험 라이브러리 실행 확인

- 설계: [0025](../design/0025-rust-engine-migration.md) 1-0
- 이슈와 PR: [#500](https://github.com/puruuun/FGA-Modified/issues/500), [#501](https://github.com/puruuun/FGA-Modified/pull/501) (병합 `4d5fa330`, 2026-11-18)
- 상태: **MuMu 확인 완료. 블루스택, 태블릿, 32비트는 아직 확인하지 못했습니다.**

## 누가 무엇을 했는지

| 일 | 에이전트 | 비고 |
|---|---|---|
| 구현 | Opus 5.5 (중간) | 기반과 `fga-ffi` 작업이라 Opus |
| 검증 1 | Sonnet 5.5 (새 세션) | 테스트와 빌드를 직접 실행. 통과 |
| 검증 2 | Gemini (읽기 전용) | 1차 반려, 수정 뒤 재검증 통과 |
| 검토, 병합, 기기 실행 | 설계자 (Rust 세션) | |

검토 내용과 반려 사유는 [PR 댓글](https://github.com/puruuun/FGA-Modified/pull/501#issuecomment-6971576992)에 있습니다.

## 들어간 것

- `native/` 워크스페이스와 `fga-ffi` 크레이트. `unsafe` 블록은 없습니다.
- 시험 함수 6개. 모두 패닉 처리 한 곳(`guard.rs`)을 거칩니다.
- 빌드 속성 `fga.native`(기본 꺼짐)와 Native CI.
- 기기 검사 스위트 `nativeProbe`. 기기 검사용 빌드에만 들어가고 실제 앱 소스는 바뀌지 않았습니다.

## 기기 실행 결과

명령: `py -3 tools/device-qa/run.py --build --native --suite nativeProbe --serial <기기>`

### MuMu Player (`127.0.0.1:16416`, Android 15, ABI 목록 `x86_64,arm64-v8a,x86`, ARM은 변환 실행)

2026-11-18, 커밋 `4d5fa330`. 6개 모두 기대대로 동작했습니다.

| 시험 | 결과 문자열 | 소요 |
|---|---|---|
| ping | `OK: fga-ffi 0.1.0 on aarch64` | 7ms |
| cpuProbe | `OK: arch=aarch64 neon=true aes=true sha2=true sve=false sve2=false auxv=unreadable` | 1ms |
| panicProbe | `ERR: panic: deliberate panic from panicProbe` (기대한 결과) | 6ms |
| ringProbe | `OK: ring: sha256 aes256gcm chacha20poly1305 ed25519` | 22ms |
| rustlsProbe | `OK: TLSv1_3 TLS13_AES_256_GCM_SHA384 in 2 rounds, both messages intact` | 25ms |
| tractProbe | `OK: conv+dense output [0.18750018, 0.3443752, -1.7606249] within 0.0001 (worst 2.3841858e-7)` | 46ms |

- 이 기기는 SVE를 지원한다고 알리지 않습니다. OpenCV를 죽였던 조건(SVE2를 알리지만 구현하지 않음)은 여기서 재현되지 않습니다.
- `/proc/self/auxv`는 읽히지 않아 hwcap 값은 얻지 못했습니다.

### 아직 실행하지 못한 곳

| 대상 | 이유 | 필요한 것 |
|---|---|---|
| 블루스택 (`127.0.0.1:5555`) | 연결 거부. 꺼져 있었습니다 | 소유자가 블루스택을 켠 뒤 같은 명령 실행 |
| 소유자 태블릿 (Legion Y700) | 연결되어 있지 않았습니다 | 소유자가 연결 |
| 32비트 `armeabi-v7a` | MuMu는 64비트 ARM만 변환합니다. 빌드와 clippy는 통과했지만 실행된 적이 없습니다 | 32비트 ARM을 실행하는 기기 |

## 그 밖의 확인

| 항목 | 결과 | 확인한 곳 |
|---|---|---|
| `fga.native` 끔: 전체 단위 테스트, `:app:assembleCi` | 통과. APK에 `libfga_ffi.so` 없음, 로그에 cargo 없음 | 구현자, 검증 1 |
| `fga.native` 켬: 기기 검사용 APK | 두 ABI의 `libfga_ffi.so` 포함. arm64 LOAD 정렬 `0x4000`. 의존 라이브러리는 libdl과 libc뿐 | 구현자, 검증 1 |
| 패닉 처리를 끄면 테스트가 실패하는지 | 4개 실패 (`guard::tests::*_panic_is_caught` 3개, `probes::tests::panic_probe_comes_back_as_error`) | 구현자, 검증 1 |
| NaN 비교를 예전 식으로 되돌리면 테스트가 실패하는지 | `probes::compare::tests::nan_in_actual_fails` 실패 | 구현자 |
| 기존 기기 검사 스위트의 동작 | 변화 없음 (줄 단위 비교) | 검증 1, 검증 2 |
| CI | Main CI, Native CI, PR Build, Android Lint 통과 (`4d5fa330`) | 설계자 |
| 리눅스에서 ring, rustls, tract 시험 | 통과 (Native CI의 `cargo test`) | 설계자 |

## 크기

기기 검사용 APK 안의 `libfga_ffi.so`: arm64-v8a 9.6MB, armeabi-v7a 5.6MB. 실제 앱 APK는 바뀌지 않았습니다. 대부분 `tract`로 보이지만 따로 재지는 않았습니다. 2A단계에서 추론 방식을 정할 때 크기를 재서 비교합니다.

## 결정과 남은 일

- **TLS 암호화 라이브러리: `ring`.** `aws-lc-rs`는 소유자 PC에서 안드로이드용 빌드가 되지 않아(`dlltool` 없음, cmake 필요) 후보에서 뺐고, `ring`은 MuMu에서 동작했습니다. 블루스택과 태블릿 결과가 다르면 다시 봅니다.
- **소유자 PC의 도구 제약이 예상보다 큽니다.** C 컴파일러뿐 아니라 `dlltool`이 없어 `windows-sys` 0.61 계열을 쓰는 크레이트가 PC에서 빌드되지 않습니다. 이번에는 `Cargo.lock`에서 `winapi-util`을 0.1.9로 내려 우회했고, `cargo-deny`는 PC에 설치하지 못해 CI에서만 돕니다. 2단계 전에 Visual Studio Build Tools 설치를 소유자에게 묻습니다.
- 다음 Rust PR에서 같이 고칠 것: Native CI의 안드로이드 clippy에 `--platform 24` 추가, `compare.rs` 테스트 보강, `run.py` 사용법 표기.
