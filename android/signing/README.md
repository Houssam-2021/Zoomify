# Signing lineage (public, not a secret)

`zoomify-v1.1.lineage` is the APK Signature Scheme v3 key-rotation proof copied unchanged out of the published
Zoomify v1.1 APK (SHA-256 `ebca94a4…597a`). It is public data: every copy of v1.1 contains it.

- Signer 1: the original key, `1a9f7202…c12c`. **Compromised.** It was committed publicly on 2026-11-02.
  It keeps *installed data* (so 1.0 installs update in place) and has **no rollback**.
- Signer 2: the release key, `7296cdbf…cccf`. Its private key is only in the `ZOOMIFY_KEYSTORE_*` repository
  secrets and the owner's offline backup.

CI signs every release with the new key plus this lineage (`apksigner sign --lineage`). No old private key is needed.
Never commit any keystore here: `.gitignore` blocks them.
