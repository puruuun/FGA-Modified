//! Stage 1-0 probes. Each returns plain text so the JNI layer only converts, and each can run
//! as a host test where its crates build.

mod cpu;
mod hex;

#[cfg(not(all(windows, target_env = "gnu")))]
mod crypto;
#[cfg(not(all(windows, target_env = "gnu")))]
mod inference;
#[cfg(not(all(windows, target_env = "gnu")))]
mod tls;

pub use cpu::cpu_probe;

/// The text a probe returns when its crates are not built for this target.
#[cfg(all(windows, target_env = "gnu"))]
const NOT_BUILT_HERE: &str = "not built on windows-gnu hosts: the crate needs a C compiler";

pub fn ping() -> Result<String, String> {
    Ok(format!(
        "fga-ffi {} on {}",
        env!("CARGO_PKG_VERSION"),
        std::env::consts::ARCH
    ))
}

/// Proves the panic guard on the device: the caller must see an error, not a crash.
#[expect(clippy::panic, reason = "this probe exists to panic")]
pub fn panic_probe() -> Result<String, String> {
    panic!("deliberate panic from panicProbe")
}

pub fn ring_probe() -> Result<String, String> {
    #[cfg(not(all(windows, target_env = "gnu")))]
    return crypto::ring_checks();
    #[cfg(all(windows, target_env = "gnu"))]
    return Err(NOT_BUILT_HERE.to_owned());
}

pub fn rustls_probe() -> Result<String, String> {
    #[cfg(not(all(windows, target_env = "gnu")))]
    return tls::handshake_in_memory();
    #[cfg(all(windows, target_env = "gnu"))]
    return Err(NOT_BUILT_HERE.to_owned());
}

pub fn tract_probe() -> Result<String, String> {
    #[cfg(not(all(windows, target_env = "gnu")))]
    return inference::tiny_model();
    #[cfg(all(windows, target_env = "gnu"))]
    return Err(NOT_BUILT_HERE.to_owned());
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::run_guarded;

    #[test]
    fn ping_names_version_and_arch() {
        let text = run_guarded(ping);
        assert!(text.starts_with("OK: fga-ffi 0.1.0 on "), "{text}");
        assert!(text.ends_with(std::env::consts::ARCH), "{text}");
    }

    #[test]
    fn panic_probe_comes_back_as_error() {
        assert_eq!(
            run_guarded(panic_probe),
            "ERR: panic: deliberate panic from panicProbe"
        );
    }

    #[test]
    #[cfg(not(all(windows, target_env = "gnu")))]
    fn ring_probe_passes() {
        let text = run_guarded(ring_probe);
        assert!(text.starts_with("OK: "), "{text}");
    }

    #[test]
    #[cfg(not(all(windows, target_env = "gnu")))]
    fn rustls_probe_passes() {
        let text = run_guarded(rustls_probe);
        assert!(text.starts_with("OK: "), "{text}");
    }

    #[test]
    #[cfg(not(all(windows, target_env = "gnu")))]
    fn tract_probe_passes() {
        let text = run_guarded(tract_probe);
        assert!(text.starts_with("OK: "), "{text}");
    }

    #[test]
    #[cfg(all(windows, target_env = "gnu"))]
    fn probes_left_out_of_this_host_say_so() {
        for probe in [ring_probe, rustls_probe, tract_probe] {
            assert_eq!(run_guarded(probe), format!("ERR: {NOT_BUILT_HERE}"));
        }
    }

    #[test]
    fn cpu_probe_reports_arch() {
        let text = run_guarded(cpu_probe);
        assert!(text.starts_with("OK: arch="), "{text}");
    }
}
