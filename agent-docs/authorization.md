# Authorization: two mechanisms, layered checks

## Mechanisms

See `application.yml` `quarkus.http.auth.permission`.

- **Humans**: OIDC authorization-code flow against a Gitea instance, configured entirely from the
  environment — `application.yml` holds no `quarkus.oidc.*` outside the `enabled: false` that `%dev` and
  `%test` pin, so a deployment supplies `auth-server-url`, `client-id` and the secret the way it
  supplies `DB_URL`. `UserIdentityAugmenter` resolves
  `(issuer, subject)` to a local `User` via `OAuthIdentity` and stashes it as an identity attribute;
  `UserContext.get()` reads it back. **First login registers**: it reads `iss`/`sub` and the
  `email` / `name` / `preferred_username` claims off the ID token and calls `UserService.provision`.
  `sub` is the key half, not the principal name, which providers let users change. A token without an
  `email` is logged and left unregistered, so it still arrives as an identity with no local user — and
  every `@RequirePermission` refuses that with a 401. A brand-new account holds no permission and no
  membership, so it can log in and see nothing until someone grants it something. Two identities with
  the same address stay two users; linking is explicit (`UserService.linkIdentity`), since automatic
  linking would trust an unverified address.
- **Workers**: `WorkerAuthMechanism` compares an `X-Worker-Token` header to `worker.secret`, set **only
  under `%dev`** so a deployment without one fails to start. All workers share that secret and one
  `"worker"` identity, and the protocol trusts them: nothing checks that a `JobStateUpdate` or
  `UpdateJobLog` names a job that worker was given (the artifact path does, via `lockAssignedOpen`).
  Deliberate, not an oversight.
- **Personal access tokens**: `Authorization: Bearer prts_…`. `AccessTokenAuthMechanism` (priority
  `1500`, above quarkus-oidc's `1001`, so a bad token answers 401 rather than OIDC's login 302) hands
  the token to `AccessTokenIdentityProvider`, which builds **the same identity an OIDC login produces**
  — same principal, same role, and the `User.class.getName()` attribute. That attribute is the whole
  equivalence: `UserContext` and every `@RequirePermission` read it and cannot tell the two apart, so
  nothing downstream knows which mechanism ran, and `UserIdentityAugmenter` needs no change (a PAT
  principal is no `JsonWebToken`, so it returns at its `issuer == null` exit). Stored as
  `sha256:<base64url>` under a unique index — the lookup hashes what was presented and indexes on it,
  which is why there is no salt. One row per user (`user_access_token`, keyed *by* the user), and
  `AccessTokenService.issue` rerolls **in place**: reusing a PK through delete + insert would trip the
  constraint, since Hibernate orders inserts first. No `application.yml` change — `/api/*` is
  `authenticated` with no mechanism pinned, while `/ws/worker` pins `worker-token`, so a PAT cannot
  reach it. There is no revoke, only the `PUT` that reissues: a token nobody knows is a locked-out
  account, not a safer one, and a sub-account has no other credential.
- **Dev auto-login**: `DevAuthMechanism` (priority `1200`, under the PAT mechanism's `1500`, so a
  presented credential still decides) logs a request carrying neither `Authorization` nor
  `X-Worker-Token` in as the `ADMIN_OF_ALL` user `DevAdminSeeder` mints at startup — so `%dev` needs no
  provider and no client secret in the repository. It authenticates by handing the seeded token to the
  PAT chain rather than building an identity, which keeps the two indistinguishable and inherits the
  provider's `runBlocking`. Both beans are gated on `@IfBuildProfile("dev")` **and**
  `quarkus.oidc.enabled == false`, both build-time, so neither is built into `%prod` at all. `%dev`
  carries no provider config — it pins OIDC off and leaves the real chain to a PAT — so the second
  condition only bites if someone switches OIDC on to debug the browser flow, which is exactly when
  auto-login would otherwise answer before the login redirect could.

## Layers

- `Perm` — a typed enum of `(permission, global)`. The string is what lands in `user_permission`, so
  it is schema, not a label. `Perm.ADMIN_OF_ALL` is the only global one and still bypasses everything.
- `Permission` rows are keyed `(userId, permission, projectId)`, read through `PermissionService`
  (Caffeine-cached per user for 5 min, invalidated after transaction completion on grant/revoke).
  A project-scoped grant matches **only** its own project — nothing widens it, which is what
  `ADMIN_OF_ALL` is for. Global grants use the reserved `Permission.GLOBAL` id, because the project is
  part of the primary key and Postgres cannot key on null.
- **There is one reserved id, `Reserved.ID`** (all-zero), and `Permission.GLOBAL` / `ResourceClass.GLOBAL`
  both point at it so two key columns cannot drift apart. Its scope is exactly that: **primary-key
  columns**, where null is not available. A column that may simply be absent says so with null; one that
  must name something real rejects the caller that has nothing to name (`PendingJobService.enqueue`
  throws) — substituting the sentinel there only renames the third state, and puts the same value in
  two different key columns, where a swapped argument stops looking wrong.
- `@RequirePermission` — a CDI **interceptor binding**, so it works on any bean method, not just
  endpoints. `defaultValue = true` means "allowed even without the permission"; `defaultRole` lets a
  project role stand in for the permission (`ProjectRole.NONE` turns it off); `allowAdmin` defaults to
  true. A project-scoped `Perm` or a `defaultRole` needs a project to check against: the interceptor
  takes a `@ProjectId`-annotated `UUID` argument when there is one, and otherwise reads the
  `projectId` path variable of the current request (`RequirePermissionInterceptor.PROJECT_PATH_PARAM`)
  — **so a project-scoped endpoint must spell its path variable exactly `projectId`**. That fallback
  is what lets checks deep inside a request, like the spec-override gating, take no project argument
  at all. With neither available it throws `IllegalStateException`: a wiring bug, not a denial.
- Authorization lives at the endpoint, not in the services: `JobLauncher` assumes its caller was gated
  and only enforces rules about the spec itself (volume access and the resource class, checkable only
  once template and override are merged). It does not even pick the per-field override gate — the
  `JobSpecOverrideAuthorizer` is a parameter, so `JobResource` passes the real one and
  `PendingJobDispatcher` the pass-through. Services take plain arguments or domain values
  (`JobRequest`), never wire DTOs; request-shape validation stays in the resource.
- Project membership via `UserToProject` + `ProjectRole`. **`ProjectRole` is persisted as its
  ordinal** in `user_to_project.role`, a `smallint` that Hibernate guards with a check it derives from
  the enum itself (`CHECK (role >= 0 AND role <= 3)`) — never reorder or remove constants, and
  remember that adding one only widens the check on a freshly created schema. `OWNER` is ordinal 0,
  which is why `UserService.hasAtLeast` compares with `<=`.

## Sub-accounts

Sub-accounts are `prts_user` rows a project owns (`sub_account`, keyed by the user, cascading from
both the user and the project), minted by an owner through `/project/{projectId}/subaccount`. They
have **no `oauth_identity` row** and so no way to log in, and **no `UserToProject` row** either — they
are not on the roster, never count toward the last-owner rule, and no `defaultRole` ever stands in for
a permission they lack. They hold an explicit set of project-scoped `Perm`s and nothing else, set
through `UserService.setPermissions` — the counterpart of `.grant` for what a role cannot express,
and general rather than sub-account-specific: it replaces what the user held **in that project**,
refuses a `global()` perm whoever holds it (`Permission.idOf` would scope it to `GLOBAL`, leaving
the project being set), and refuses `project:subaccount:manage` to a sub-account, which is what
keeps the hierarchy flat. `SubAccountService` adds only its containment check, as it does for
`delete`. Three things guard the "cannot be linked to a person" property: `UserService.linkIdentity`
and `.grant` both refuse a sub-account (409), and `User.findByEmail` refuses a blank address, which is
what a sub-account's is. Permissions cross the wire as the `Perm` **strings** (already schema), parsed
by `Perm.byPermission` in the resource so an unknown one gets a message rather than a bodiless 400.

## Which permission gates what

See [http-surface.md](http-surface.md) for the endpoint-by-endpoint mapping, and
[job-spec.md](job-spec.md) for the per-field override gating idiom.
