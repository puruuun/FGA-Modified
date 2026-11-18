---
applyTo: "native/**"
description: Rules for the Rust core - unsafe, panics, dependencies, test data and what a Rust PR must show.
---

The owner does not read Rust, so the rules are enforced by tools: no `unsafe` outside `fga-ffi`, no
`unwrap` outside tests, and every Kotlin-callable function catches panics.

Read [`docs/agents/rust-native.md`](../../docs/agents/rust-native.md).
