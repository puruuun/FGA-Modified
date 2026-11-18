use std::any::Any;
use std::panic::{AssertUnwindSafe, catch_unwind};

/// Runs `body` and renders its outcome as the `OK: ` / `ERR: ` text Kotlin receives.
///
/// A panic that unwinds into the JVM aborts the whole app, so every function Kotlin can call
/// goes through here.
pub fn run_guarded<F>(body: F) -> String
where
    F: FnOnce() -> Result<String, String>,
{
    // The closure's state is dropped right after a panic and never observed again, so a broken
    // invariant inside it cannot leak out.
    match catch_unwind(AssertUnwindSafe(body)) {
        Ok(Ok(detail)) => format!("OK: {detail}"),
        Ok(Err(detail)) => format!("ERR: {detail}"),
        Err(payload) => format!("ERR: panic: {}", panic_message(payload.as_ref())),
    }
}

fn panic_message(payload: &(dyn Any + Send)) -> &str {
    if let Some(message) = payload.downcast_ref::<&str>() {
        message
    } else if let Some(message) = payload.downcast_ref::<String>() {
        message
    } else {
        "non-text panic payload"
    }
}

#[cfg(test)]
mod tests {
    use super::run_guarded;

    #[test]
    fn ok_result_is_prefixed() {
        assert_eq!(run_guarded(|| Ok("fine".to_owned())), "OK: fine");
    }

    #[test]
    fn err_result_is_prefixed() {
        assert_eq!(run_guarded(|| Err("broken".to_owned())), "ERR: broken");
    }

    #[test]
    fn literal_panic_is_caught() {
        assert_eq!(run_guarded(|| panic!("boom")), "ERR: panic: boom");
    }

    #[test]
    fn formatted_panic_is_caught() {
        let value = 7;
        assert_eq!(
            run_guarded(|| panic!("value {value}")),
            "ERR: panic: value 7",
        );
    }

    #[test]
    fn non_text_panic_is_caught() {
        assert_eq!(
            run_guarded(|| std::panic::panic_any(42_u32)),
            "ERR: panic: non-text panic payload",
        );
    }
}
