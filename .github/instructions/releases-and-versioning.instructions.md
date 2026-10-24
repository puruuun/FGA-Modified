---
applyTo: "version.properties,docs/releases/**,wiki/releases/**,app/build.gradle.kts"
description: Semantic versioning (patch vs minor bumps) and the beta-first APK testing workflow before stable releases.
---

Follow Semantic Versioning (`X.Y.(Z+1)` for refinements/fixes/syncs, `X.(Y+1).0` for new features)
and build/test a Beta APK (`beta.*` in `version.properties`, `./gradlew :app:assembleBeta`) on
BlueStacks/tablet before promoting `stable.*` and publishing a GitHub Release.

Read [`docs/agents/releases-and-versioning.md`](../../docs/agents/releases-and-versioning.md).
