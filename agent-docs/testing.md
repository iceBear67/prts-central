# Testing Architecture

Tests are divided into three distinct execution tiers:

| Tier | Boots Quarkus | Needs Docker | Scope & Characteristics |
| --- | --- | --- | --- |
| **Tier A** (Pure) | No | No | Pure functions, algorithms, and value objects (`JobSpecTest`, `JobSpecOverrideTest`, `SecretCipherTest`, `TaskScopeTest`). |
| **Tier B** (Mocked) | No | No | Isolated beans with mocked collaborators (`JobLauncherTest`, auth mechanism tests, dispatcher tests). Executes in milliseconds. |
| **Tier C** (E2E) | Yes | Yes (Dev Services) | Integration tests tagged `@Tag("e2e")` (`*E2ETest.java`). Exercises persistence, transactions, and HTTP API over real containers. |

- **Unit Command**: `./gradlew test` runs Tiers A & B (standard CI/local fast path).
- **E2E Command**: `./gradlew e2eTest` runs all tiers including Tier C (requires Docker for Dev Services Postgres and LocalStack).

## Tier B Constraints & Patterns

- **Collaborator Injection**: All `@Inject` dependencies are package-private, allowing direct assignment in tests without CDI or reflection (`launcher.projectService = mock(...)`).
- **Panache Static Mocking**:
  - Hand-written entity static finders (`JobSpecTemplate.findVisibleFetched`, `ResourceClass.findByName`) can be mocked via `Mockito.mockStatic`. `ResourceClass.findByName` exists only to wrap `findByIdOptional` for exactly this reason — keep the wrapper when a mocked caller depends on it.
  - Inherited Panache static methods (`findById`, `persist`, `listAll`) fail outside Quarkus augmentation; beans relying on them must be tested in Tier C.
  - *Caution*: `mockStatic(Entity.class)` stubs all static methods, including Lombok-generated `builder()` and `$default$<field>()` (used by `@Builder.Default` fields like `Job.state` and `WorkerVolume.state`). Instantiate entity fixtures before opening a `mockStatic` block to avoid mock interference and `UnfinishedStubbingException`.
- **Transaction Stubbing**: Use `io.ib67.prts.testing.InlineTransactions` in a try-with-resources block to execute `QuarkusTransaction.requiringNew()` synchronously on the test thread.
- **Log Muting (`MutedLogs`)**: Tests driving expected-failure paths should wrap calls in `io.ib67.prts.testing.MutedLogs` to suppress noisy stack traces. Applies to Tier C as well.

## Tier C Constraints & Fixtures

- **Database Cleanup (`DatabaseCleaner`)**: Because services commit transactions via `requiringNew()`, standard `@TestTransaction` rollback does not roll back test writes. Tests use `DatabaseCleaner.clean()` (`TRUNCATE ... CASCADE`) in `@BeforeEach`.
- **Authentication in Tests (`Fixtures`)**:
  - Dev auto-login is disabled under `%test`.
  - Standard tests authenticate using real Personal Access Tokens generated via `Fixtures#actor` and `Fixtures.as(actor)`.
- **Project Ownership (`Fixtures#createProject`)**: `ProjectService.create` requires an owner. Tests should pass an explicit `Actor`; `createProject(name)` defaults to creating a throwaway user.
- **Worker Cleanup**: Active worker registrations in `WorkerService.activeWorkers` reside in-memory; tests interacting with WebSockets must disconnect workers and clear sessions in `@AfterEach`.
  - **An empty roster does not mean the disconnect is finished.** `WorkerService.unregisterWorker` removes the worker from `activeWorkers` *before* closing its ACP channel and calling `failJobsOf`, so waiting on `getActiveWorkers().isEmpty()` returns while those transactions are still committing — and the next test's `DatabaseCleaner.clean()` then deadlocks against them (`TRUNCATE` wants `AccessExclusiveLock`, the in-flight write holds `RowExclusiveLock`). Wait for the tail of the work as well, e.g. `Job.listOpenByWorker(workerId).isEmpty()`.
- **`@OnOpen` runs after the handshake returns.** A `@Blocking` `@OnOpen` has not necessarily executed when the client's `buildAsync(...).join()` completes, so a test that next touches a *different* connection races it. Prove the connection is live with a round-trip on it first (`AgentWebSocketE2ETest.Viewer.attached()`).
- **E2E Filtering**: Tier C test classes must follow the naming pattern `*E2ETest.java` to be excluded from standard `./gradlew test` runs.

## `%test` Profile Configuration

| Setting | Purpose |
| --- | --- |
| `worker.secret` / `secret.keys` | Provides dummy keys required by startup validators. |
| `quarkus.oidc.enabled: false` | Disables OIDC provider requirements during testing. |
| `schema-management.strategy: update` | Generates schema into the ephemeral Dev Services Postgres database. |
| `job.pending.interval: PT24H` | Freezes the background queue dispatcher so tests can trigger `tick()` manually. |

