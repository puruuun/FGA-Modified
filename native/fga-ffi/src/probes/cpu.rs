//! Evidence for diagnosing a SIGILL, not a pass/fail check: BlueStacks once advertised SVE2 in
//! HWCAP2 without implementing it, and crates that pick code paths at runtime read the same bits.

pub fn cpu_probe() -> Result<String, String> {
    let mut parts = vec![format!("arch={}", std::env::consts::ARCH)];
    parts.extend(detected_features());
    parts.extend(hwcaps());
    Ok(parts.join(" "))
}

#[cfg(target_arch = "aarch64")]
fn detected_features() -> Vec<String> {
    use std::arch::is_aarch64_feature_detected as detected;
    vec![
        format!("neon={}", detected!("neon")),
        format!("aes={}", detected!("aes")),
        format!("sha2={}", detected!("sha2")),
        format!("sve={}", detected!("sve")),
        format!("sve2={}", detected!("sve2")),
    ]
}

#[cfg(target_arch = "x86_64")]
fn detected_features() -> Vec<String> {
    use std::arch::is_x86_feature_detected as detected;
    vec![
        format!("aes={}", detected!("aes")),
        format!("sha={}", detected!("sha")),
        format!("avx2={}", detected!("avx2")),
    ]
}

// Runtime detection for 32-bit ARM is unstable in std, so the raw HWCAP bits below are the
// evidence there.
#[cfg(not(any(target_arch = "aarch64", target_arch = "x86_64")))]
fn detected_features() -> Vec<String> {
    vec!["detection=unavailable".to_owned()]
}

#[cfg(any(target_os = "android", target_os = "linux"))]
fn hwcaps() -> Vec<String> {
    const AT_HWCAP: usize = 16;
    const AT_HWCAP2: usize = 26;
    const WORD: usize = std::mem::size_of::<usize>();

    let Ok(auxv) = std::fs::read("/proc/self/auxv") else {
        return vec!["auxv=unreadable".to_owned()];
    };
    let words: Vec<usize> = auxv
        .as_chunks::<WORD>()
        .0
        .iter()
        .map(|bytes| usize::from_ne_bytes(*bytes))
        .collect();
    let mut found = Vec::new();
    for [key, value] in words.as_chunks::<2>().0 {
        match *key {
            AT_HWCAP => found.push(format!("hwcap=0x{value:x}")),
            AT_HWCAP2 => found.push(format!("hwcap2=0x{value:x}")),
            _ => {}
        }
    }
    found
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
fn hwcaps() -> Vec<String> {
    Vec::new()
}
