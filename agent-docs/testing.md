# Testing

`./gradlew test` runs tiers A and B: no containers, no Quarkus boot, a few seconds. `./gradlew e2eTest`
adds tier C, whose Postgres and LocalStack are started by Dev Services and so need a reachable Docker
daemon. Read the constraints below before adding a test — they decide which tier it can live in.

The artifact is `quarkus-junit`, **not** `quarkus-junit5` (renamed in Quarkus 3.x), and it pulls
**JUnit 6** (`junit-jupiter-6.1.2`). `quarkus-junit-mockito` supplies Mockito with the inline mock
maker, which is what makes `mockStatic` work.

## Three tiers

| Tier | Boots Quarkus | Needs containers | Holds |
| --- | --- | --- | --- |
| A — pure | no | no | value objects and pure functions |
| B — Mockito-isolated | no | no | beans whose collaborators are all injected |
| C — `@QuarkusTest` | yes | yes, via Dev Services | entity queries, cascades, locks, HTTP, auth chain |

A and B are plain JUnit: they never read `application.yml`, never start Postgres or LocalStack, and run
in milliseconds. Keep as much as possible in them.

**`@InjectMock` does not belong to tier B.** It requires `@QuarkusTest`, so it boots the app and needs
the stack — it is a tool for narrowing tier C, not for escaping it.

## What tier B can and cannot reach

- **Injecting collaborators needs no CDI and no reflection.** Every `@Inject` field in this codebase is
  package-private, so a test in the same package assigns them directly:
  `var launcher = new JobLauncher(); launcher.projectService = mock(ProjectService.class);`
  Keep it that way — making one private moves its owner out of tier B.
- **Panache statics split in two.** An entity's own hand-written finders (`JobSpecTemplate.findVisibleFetched`,
  `ResourceClass.findVisible`, `WorkerVolume.listByIds`, `JobLock.tryAcquire`) are declared on the entity
  and can be `Mockito.mockStatic`ed. The **inherited** ones (`findById`, `findByIdOptional`, `persist`,
  `delete`, `listAll`) cannot: without Quarkus augmentation they resolve to `PanacheEntityBase` and throw
  `implementationInjectionMissing()`. A method that calls one is tier C, full stop.
- **`QuarkusTransaction.requiringNew()` needs a real TransactionManager.** Tier B stubs it out with a
  shared helper (see below). This is unavoidable: `agent-docs/transactions.md` mandates the pattern, so it
  is everywhere.
- **Do not use `MockitoExtension` / `@Mock` without checking `mockito-junit-jupiter` against JUnit 6.**
  Plain `Mockito.mock(...)` needs no extension and sidesteps the question.

- **`mockStatic(SomeEntity.class)` also stubs the Lombok `builder()` static.** Build every entity fixture
  *before* opening the scope, or `builder()` returns null. `JobLauncherTest` keeps its fixtures in field
  initializers for this reason.

### The inline-transaction helper

`io.ib67.prts.testing.InlineTransactions` is an `AutoCloseable` that makes `requiringNew()` execute the
body on the spot; wrap the call under test in a try-with-resources. `mockStatic` is thread-local, which
is exactly the scope wanted.

## Tier C constraints

- **`@TestTransaction` does not roll back what this codebase writes.** `QuarkusTransaction.requiringNew()`
  suspends the caller's transaction and commits its own, so those writes survive the outer rollback. Clean
  up by truncating instead; reserve `@TestTransaction` for read-only or single-entity tests.
- **`@TestSecurity` yields no authenticated user.** It builds a `QuarkusPrincipal`, so
  `UserIdentityAugmenter` finds no issuer/subject, returns the identity untouched, and `UserContext.require()`
  throws 401. Authenticate through the real PAT chain instead — `AccessTokenService.issue(userId).token()`
  returns the plaintext, and `AccessTokenIdentityProvider` attaches the `User` attribute and the role. This
  also covers `@RequirePermission` end to end.
- **Dev auto-login is not in a test build.** `DevAuthMechanism` / `DevAdminSeeder` are
  `@IfBuildProfile("dev")`, so nothing under `%test` arrives pre-authenticated as an admin and the PAT
  chain above stays the only way in.
- **Presigning is local crypto.** `StorageService.presignGet`/`presignPut` never touch the network, so only
  `findObjectSize`, `deleteQuietly`, and real uploads need LocalStack.
- **A refusal often has no body.** `io.quarkus.security.ForbiddenException`, which
  `RequirePermissionInterceptor` throws, and the `NoSuchElementException` mapper both answer with a bare
  status; only a `jakarta.ws.rs.*Exception(String)` carries `{"message": ...}`. Assert the status, and
  check `message` only where a resource actually built one.
- **A package-private method is behind the proxy.** An injected `@ApplicationScoped` bean is a client
  proxy carrying only the public methods, so calling `PendingJobDispatcher.tick()` from a test needs
  `io.quarkus.arc.ClientProxy.unwrap(bean)`.
- **Some state outlives `DatabaseCleaner`.** `WorkerService.activeWorkers` is a field on an
  application-scoped bean, not a table, so a worker left registered stays schedulable in the next test
  class. Close what a test connected and wait for the roster to empty in `@AfterEach`.

## The `%test` profile

Several settings needed at startup used to live only under `%dev`, so the app could not boot in test
mode at all. `application.yml` now carries a `%test` block; each entry is load-bearing:

| Setting | Why |
| --- | --- |
| `worker.secret` | `WorkerConfig.secret()` has no default; config validation fails at startup |
| `secret.keys` / `secret.active-key` | `SecretCipher` is `@Startup`; `loadKeys()` throws |
| `quarkus.oidc.enabled: false` | no profile configures an `auth-server-url` any more, so the extension fails startup |
| `quarkus.cache.enabled: false` | `user-permissions` caches grants for 5 minutes; grants changed mid-test would not be seen |
| `schema-management.strategy` | also `%dev`-only, so the test database gets no tables |
| `job.pending.interval` | `PT1S` lets `PendingJobDispatcher` tick during tests; freeze it and drive `tick()` by hand |

The **absences** are load-bearing too: `%test` sets no datasource URL and no `quarkus.s3.endpoint-override`
on purpose. Either one would be read as "something is already running" and Dev Services would then start
nothing. Ports and credentials are whatever Testcontainers picks; nothing in a test may assume them.

## Running tier C

`./gradlew e2eTest` is the whole flow in one command (`test -Pe2e` is the same thing without the wrapper
task). Dev Services starts Postgres and LocalStack — `quarkus.s3.devservices.buckets` creates
`prts-artifacts` — and stops them with the JVM, so the only prerequisite is a Docker daemon the test JVM
can reach.

**That is CI's job** — `.github/workflows/ci.yml`, on every push to `master` and every pull request —
**not the developer machine's.** Here the daemon is rootful and the developer is
deliberately not in the `docker` group, so tier C cannot run without `sudo`; the standing decision is
that it does not run locally at all. Write the tests, run `./gradlew test` for tiers A and B, and let CI
execute the rest. Do not propose `sudo ./gradlew`, podman, or a compose file — rootless podman is a
proven dead end here (any bridge network makes netavark shell out to `nft`, which an unprivileged user
cannot run, and the containers are created but never start).

Because nothing is pinned, tier C must never assume a port or a URL — `%test` gets whatever
Testcontainers picks, which is also why a running `%dev` stack on 5432 does not collide with it.

Tier C classes are tagged `@Tag("e2e")` and gated from the single Gradle `test` task; a second `Test`
task would have to re-create the wiring the Quarkus plugin does for `test`. **The tag alone does not
gate them.** `excludeTags` is a post-discovery filter, and JUnit has already *loaded* the class by the
time it runs — which for a `@QuarkusTest` means the FacadeClassLoader boots the application and Dev
Services goes looking for a daemon, failing the build before a single tier A test reports. `test` also
carries `exclude '**/*E2ETest*'`, which is why the name suffix is mandatory and not a convention.

## Coverage

**Tier A — done.** `JobSpecTest` (normalization, redacted `toString`, Jackson round-trip with `secret`
excluded, `requireVolumesIn`), `JobSpecOverrideTest` (merge semantics, and that the authorizer sees only
user-supplied input), `SecretCipherTest` (round trip, wrong-context AAD failure, an old key id still
opening, truncated and non-base64 envelopes).

**Tier B — done.** `RequirePermissionInterceptorTest`, `WorkerAuthMechanismTest`,
`AccessTokenAuthMechanismTest`, `UserIdentityAugmenterTest` (provisioning and the concurrent-first-login
race), `PendingJobDispatcherTest`, `JobLauncherTest`. `JobLauncher.launch()` itself is out of reach —
`job.persist()` is inherited — so the test covers `authorize()`, which is where the gating lives.

**Tier C — 14 classes, 192 tests.** Two beans carry it: `io.ib67.prts.testing.DatabaseCleaner` (TRUNCATE,
since `@TestTransaction` cannot undo a `requiringNew()` commit) and `Fixtures` (actors with a PAT,
projects, memberships, and a row for every entity a test needs). `ProjectResourceE2ETest` is the worked
example — the permission matrix of one resource over the real PAT chain, `@BeforeEach` truncating.

| Area | Classes |
| --- | --- |
| Permission matrices — uncredentialed, wrong actor, each `ProjectRole`, `ADMIN_OF_ALL`, and the non-disclosure rule that a stranger cannot tell "not yours" from "does not exist" | `ProjectResourceE2ETest`, `JobResourceE2ETest`, `SecretResourceE2ETest`, `SubAccountResourceE2ETest`, `UserTokenResourceE2ETest`, `WorkerResourceE2ETest` |
| Entity finders, each covering what it includes *and* what it leaves out | `JobFinderE2ETest`, `JobSpecTemplateFinderE2ETest`, `ResourceClassFinderE2ETest`, `PendingJobFinderE2ETest` |
| `JobLock.tryAcquire`/release, including contention from parallel threads | `JobLockE2ETest` |
| `ProjectService.delete`, asserting every table `agent-docs/project-deletion.md` lists is emptied | `ProjectDeletionE2ETest` |
| `PendingJobService` claim, backoff and expiry, driving `tick()` by hand | `PendingJobServiceE2ETest` |
| The `/ws/worker` handshake and the register / report / disconnect protocol | `WorkerWebSocketE2ETest` |

Still to do: `JobLauncher.launch()` end to end (tier B only reaches `authorize()`); the artifact upload
path against LocalStack; `ProjectService`'s `Rows.BUSY` branch, reachable only by opening a job in the
window between `stopWork` and the row lock `deleteRows` takes, which a test cannot hold open; the worker
socket's `@OnError` reply, whose routing through websockets-next could not be
established without executing it; and re-registering a worker on a second connection, where the claim
worth testing — that closing the displaced connection leaves the live one registered — is a negative
with nothing on the client side to wait on.
