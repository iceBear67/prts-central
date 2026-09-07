# Project secrets

A project's secrets are named values sealed at rest and injected into a job at dispatch time. They are
`(project_id, name)`-keyed (`ProjectSecret`, `@EmbeddedId`) — the name is the handle every endpoint
addresses, so there is no surrogate id to publish. Where they go once resolved is
[job-spec.md](job-spec.md): `JobLauncher.prepare` attaches them to the *copy* of the spec it hands the
scheduler, never to the row.

## The envelope

`SecretCipher` is AES-GCM under one of `secret.keys`. A stored value is

```
<format>:<keyId>:<base64(iv || ciphertext || tag)>
```

so **which key opens a row is readable off the row**, and a key can be rotated without a schema change.
Sealing takes a `context` string — `SecretService.contextOf` makes it `projectId + "/" + name` — and
the context *and the envelope header* go in as GCM additional authenticated data. So a ciphertext only
opens under the identity it was sealed for: whoever can write the table cannot move a value onto
another project or name, nor relabel which key it names.

`scripts/secrets.py` reimplements this format to rotate keys offline, so **the two have to change
together**. `loadKeys` runs `@PostConstruct` on an eager `@Startup` bean: a malformed key set, or an
`active-key` that names no key, fails the boot rather than the first request.

## Config

`SecretConfig` (`@ConfigMapping(prefix = "secret")`): `keys` (base64 of 16/24/32 bytes),
`activeKey`, `maxValueLength` (4096), `maxDescriptionLength` (256).

`secret.keys` / `secret.active-key` have **no default outside `%dev`**, the same rule as
`worker.secret`: a deployment that forgets them fails to start rather than sealing secrets under a key
from this repository. Rotating means adding a key (`python scripts/secrets.py genkey`), pointing
`active-key` at it, then `python scripts/secrets.py rotate`; the old key may only be dropped once that
reports nothing left, since each row names the key it is sealed under.

## Reading them back

Nothing hands a value out. `SecretService.list` returns rows whose `cipherText` is still sealed, and
`SecretView` publishes name, description and timestamps only. The single plaintext path is
`SecretService.resolve(projectId)`, whose one caller is `JobLauncher`. `ProjectSecret.cipherText` is
`@ToString.Exclude`d so no log line can carry even the ciphertext, and `SecretCipher` never puts the
`GeneralSecurityException` message on the exception it throws — it would describe the value or the key.

## Endpoints

`/project/{projectId}/secret`. Listing takes `project:secret:read` / `ProjectRole.MEMBER` — whoever
writes a job has to know which names exist and what they are for — while create, update and delete take
`project:secret:manage` / `OWNER`.

A name must match `[A-Za-z_][A-Za-z0-9_]{0,63}`: secrets are destined for a job's environment, so the
name is held to that shape rather than to whatever survives a URL path.

`POST` conflicts rather than replacing, so a create can never silently drop the value a running job is
using; changing one is the `PATCH`, which takes `description` and/or `value`, each nullable for
"unchanged" and refused when both are. A blank description clears it (blank and absent are the same
row); a value is re-sealed under the same context.

## Personal access tokens

`secret.user` is a different thing that happens to live nearby — see
[authorization.md](authorization.md). It stores a bare SHA-256 digest, not a sealed value: the token is
256 bits from a CSPRNG, so there is nothing to guess offline, and determinism is the point —
`AccessTokenService.resolve` hashes what was presented and looks *that* up on an index rather than
reading a row to compare against.
