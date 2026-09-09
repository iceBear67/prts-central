# Authorization Architecture

PRTS-Central uses layered authentication mechanisms and interceptor-based permission checks.

## Authentication Mechanisms

Configured via `quarkus.http.auth.permission` in `application.yml`:

| Mechanism | Credential / Route | Priority | Behavior |
| --- | --- | --- | --- |
| **Personal Access Token (PAT)** | `Authorization: Bearer prts_...` on `/api/*` | 1500 | Authenticates against hashed token in `user_access_token`. Builds standard identity with `User.class.getName()` attribute matching OIDC. Unrecognized token returns 401. |
| **Dev Auto-Login** | Uncredentialed on `/api/*` (dev mode only) | 1200 | Gated by `@IfBuildProfile("dev")` and `quarkus.oidc.enabled: false`. Authenticates as seeded `ADMIN_OF_ALL` user created by `DevAdminSeeder`. |
| **OIDC Auth Code** | Browser redirect / callback on `/api/*` | 1001 | Authorization-code flow against Gitea. `UserIdentityAugmenter` maps `(issuer, subject)` to local `User` record; provisions on first login via `UserService.provision`. Disabled in `%dev` and `%test`. |
| **Worker Auth** | `X-Worker-Token` on `/ws/worker` | Dedicated | Validates header against `worker.secret`. Grants shared `"worker"` principal. |

`HttpAuthenticationMechanism`, `IdentityProvider` and `SecurityIdentityAugmentor` all run on the IO
thread. Their database work belongs inside `context.runBlocking` (`AccessTokenIdentityProvider`,
`UserIdentityAugmenter`), and **no bean they inject may query from its constructor or `@PostConstruct`** —
dereferencing a client proxy is what creates the bean, so that construction would run on the IO thread
too. `DevAdminSeeder` seeds from a `StartupEvent` observer for this reason.

### Token Storage & Lifecycle
- **Storage**: Stored as un-salted `sha256:<base64url>` in `user_access_token` (one row per user). Lookup queries by hashed token directly.
- **Issuing & Rotation**: `AccessTokenService.issue(userId)` updates the existing row in place. Generating a new token immediately invalidates the previous token.
- **Revocation**: Re-issuing acts as invalidation. No standalone revoke endpoint exists.

## Authorization Layers

### 1. Permission Model (`Perm`)
- Typed enum representing `(permission, isGlobal)`. String representations match database entries in `user_permission`.
- Global permissions are `admin:all` and `project:create`; every other permission is scoped to a project. `Perm.ADMIN_OF_ALL` bypasses all `@RequirePermission` checks except a ban (below).
- Rows are keyed `(userId, permission, projectId)`. Cached per user in Caffeine (5 min TTL), invalidated on transaction commit upon grant/revoke.
- **`Reserved.ID` (`00000000-0000-0000-0000-000000000000`)**: Sentinel UUID used in non-null PK columns for global scope (`Permission.GLOBAL`, `ResourceClass.GLOBAL`). Nullable columns use SQL `NULL` instead.

### 2. `@RequirePermission` Interceptor
CDI interceptor binding (`RequirePermissionInterceptor`) evaluated on annotated methods:
- `defaultValue = true`: Method permits access if caller has no specific grant.
- `defaultRole = ProjectRole`: Minimum project role required to satisfy permission check (`ProjectRole.NONE` disables role fallback).
- `allowAdmin = true` (default): Grants automatic access to holders of `ADMIN_OF_ALL`.
- **Project Resolution**: Resolves project ID from `@ProjectId UUID` method argument; falls back to `{projectId}` HTTP path parameter (`RequirePermissionInterceptor.PROJECT_PATH_PARAM`). Throws `IllegalStateException` if neither is available.

### 3. Global Permission Bans
`permission.banned` (`PermissionConfig`) disables permissions system-wide. `PermissionService` resolves
the list once during initialization (`@PostConstruct`), so changes require updating configuration and restarting.
- **Denies the permission, not the endpoint**: A ban overrides explicit user grants, role fallbacks
  (`defaultRole`), and `allowAdmin`. Endpoints with `defaultValue = true` remain accessible to callers without
  grants. For example, banning `project:member:manage` prevents member removal by managers without preventing
  users from removing themselves.
- **`admin:all` and unknown identifiers cannot be listed**: Specifying `admin:all` or an unrecognized
  permission identifier fails application startup.
- `PermissionService.allows` treats banned permissions as denied so endpoint checks and view gating (`JobAccess`)
  remain consistent. `PermissionService.has` continues to check database records directly for administration
  and lookup (`listUsersWith`).
- `GET /api/admin/permission` returns all permissions along with their ban status.

### 4. Project Membership & Roles
- Persisted in `user_to_project.role` as enum ordinals (0 = `OWNER`, 1 = `MEMBER`, 2 = `VIEWER`, 3 = `NONE`).
- Checked via `UserService.hasAtLeast(user, project, role)` comparing ordinals (`role.ordinal() <= required.ordinal()`).
- **Do not reorder existing `ProjectRole` constants.**
- `UserService.transferOwnership` promotes the target user to `OWNER` before demoting the caller to `MEMBER`
  to ensure continuous project ownership. If the caller is not a project member (such as an administrator
  or user with `project:transfer`), only the target member is updated.
- The last remaining user with `admin:all` cannot have that permission revoked (`UserService.requireAnotherAdmin`
  throws 409 Conflict), consistent with the last-owner rule.

## Sub-Accounts

Sub-accounts are non-human API accounts owned and scoped to a specific project (`sub_account` table linking `prts_user` and `project`).
- **No External Identity**: No `oauth_identity` or `UserToProject` rows exist for sub-accounts. They cannot log in via OIDC and have no project role.
- **Token Management**: Managed exclusively by project owners via `/api/project/{projectId}/subaccount/{userId}/token`. Sub-accounts cannot read or rotate their own tokens.
- **Scoped Permissions**: Assigned explicit project-scoped permissions via `UserService.setPermissions`.
- **Constraints**:
  - Cannot be granted `project:subaccount:manage` (keeps sub-account hierarchy flat).
  - Cannot be granted global permissions (`admin:all`).
  - Cannot be linked to external identities (`UserService.linkIdentity` rejects sub-accounts with 409).
  - Cannot hold project membership roles.

