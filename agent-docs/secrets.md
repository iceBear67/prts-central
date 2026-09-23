# Project Secrets & Encryption

Project secrets are sensitive key-value pairs stored encrypted at rest and injected into jobs at dispatch time.

## Encryption Envelope & `SecretCipher`

Encrypted with AES-GCM (128, 192, or 256-bit keys) managed by `SecretCipher`:

```
<format>:<keyId>:<base64(iv || ciphertext || tag)>
```

- **Envelope Header & Key ID**: The envelope records the key ID (`v1`, `v2`) used during encryption, enabling online key rotation without database migration.
- **AAD Authentication**: Encryption and decryption bind `projectId + "/" + name` and the envelope header as Additional Authenticated Data (AAD), preventing cross-project remapping or header tampering.
- **Offline Rotation**: `scripts/secrets.py` implements the same cipher envelope for offline batch key rotation.

## Configuration (`SecretConfig`)

- `secret.keys`: Map of key identifiers to base64-encoded AES keys.
- `secret.active-key`: Key ID used for new write operations.
- `secret.max-value-length`: Max secret plaintext length (4096 bytes).
- `secret.max-description-length`: Max description length (256 bytes).
- *Profile Rule*: `secret.keys` and `secret.active-key` have defaults only in `%dev` and `%test`. Production deployments must specify them via environment variables.

## Access Boundaries

- **API Redaction**: Plaintext secrets are never exposed over HTTP. `SecretView` exposes name, description, and timestamps only.
- **Spec Injection**: Plaintext secrets are resolved only by `SecretService.resolve(projectId)` inside `JobLauncher.prepare()`, attaching them to the in-memory `JobSpec` passed directly to the worker via `CreateJob.secrets`.
- **Database Safety**: `job.spec` jsonb excludes secrets (`@JsonIgnore`).

## Personal Access Tokens (`secret.user`)

Located under `io.ib67.prts.secret.user`. Unlike project secrets, PATs are stored as plain SHA-256 digests (`sha256:<base64url>`) in `user_access_token`. Authentication hashes the incoming token to perform an indexed lookup.

