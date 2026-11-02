# app/signing

`fga-lineage.bin` is the APK Signature Scheme v3 key rotation lineage. It links the public debug
key (`app/fgadebug.keystore`, alias `fgadebug`) to the owner's private release key. It holds public
certificates and signatures only, no private key material, so it is safe to commit.

The release build (`./gradlew :app:assembleBeta` / `:app:assembleCi`) needs this file and the
owner's key; see `docs/agents/releases-and-versioning.md` ("Release signing").

## Creating it

Run once, with the owner's keystore (alias `fga-release`). The new-signer password is typed at the
prompt:

```
apksigner rotate --out app/signing/fga-lineage.bin --old-signer --ks app/fgadebug.keystore --ks-key-alias fgadebug --ks-pass pass:123abc --new-signer --ks <owner jks> --ks-key-alias fga-release
```

Check it with `apksigner lineage --in app/signing/fga-lineage.bin` (two certificates).
Never commit a keystore (`*.jks`) or `signing.properties`; both are ignored.
