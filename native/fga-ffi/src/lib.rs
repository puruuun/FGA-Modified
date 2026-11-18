//! The Kotlin boundary of the FGA Rust core.
//!
//! For now it only carries the stage 1-0 probes of design 0025: they show whether Rust code,
//! and the crypto and inference crates the later stages want, run on our test devices.

mod guard;
mod jni_exports;
pub mod probes;

pub use guard::run_guarded;
