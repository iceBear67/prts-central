# Testing Architecture

Tests are divided into three distinct execution tiers:

| Tier | Boots Quarkus | Needs Docker | Scope & Characteristics |
| --- | --- | --- | --- |
| **Tier A** (Pure) | No | No | Pure functions, algorithms, and value objects (`JobSpecTest`, `JobSpecOverrideTest`, `SecretCipherTest`, `ResourceClassTest`). |
| **Tier B** (Mocked) | No | No | Isolated beans with mocked collaborators (`JobLauncherTest`, auth mechanism tests, dispatcher tests). Executes in milliseconds. |
| **Tier C** (E2E) | Yes | Yes (Dev Services) | Integration tests tagged `@Tag("e2e")` (`*E2ETest.java`). Exercises persistence, transactions, and HTTP API over real containers. |

- **Unit Command**: `./gradlew test` runs Tiers A & B (standard CI/local fast path).
- **E2E Command**: `./gradlew e2eTest` runs all tiers including Tier C (requires Docker for Dev Services Postgres and LocalStack).

## Tier B Constraints & Patterns

- **Collaborator Injection**: All `@Inject` dependencies are package-private, allowing direct assignment in tests without CDI or reflection (`launcher.projectService = mock(...)`).
- **Panache Static Mocking**:
  - Hand-written entity static finders (`JobSpecTemplate.findVisibleFetched`, `ResourceClass.findVisible`) can be mocked via `Mockito.mockStatic`.
  - Inherited Panache static methods (`findById`, `persist`, `listAll`) fail outside Quarkus augmentation; beans relying on them must be tested in Tier C.
  - *Caution*: `mockStatic(Entity.class)` stubs **every** static the class has, including ones Lombok generates. That is `builder()`, and — for any entity with a `@Builder.Default` field, such as `Job.state` and `WorkerVolume.state` — the `$default$<field>()` the no-arg constructor calls, so even `new Entity()` interacts with the mock. Always construct entity test fixtures *before* opening a `mockStatic` block, including inside `thenReturn(...)` arguments: an exception thrown while evaluating one leaves the stubbing unfinished, and the `UnfinishedStubbingException` raised at close hides the real cause.
- **Transaction Stubbing**: Use `io.ib67.prts.testing.InlineTransactions` in a try-with-resources block to execute `QuarkusTransaction.requiringNew()` synchronously on the test thread.

## Tier C Constraints & Fixtures

- **Database Cleanup (`DatabaseCleaner`)**: Because services commit transactions via `requiringNew()`, standard `@TestTransaction` rollback does not roll back test writes. Tests use `DatabaseCleaner.clean()` (`TRUNCATE ... CASCADE`) in `@BeforeEach`.
- **Authentication in Tests (`Fixtures`)**:
  - Dev auto-login is disabled under `%test`.
  - Standard tests authenticate using real Personal Access Tokens generated via `Fixtures#actor` and `Fixtures.as(actor)`.
- **Project Ownership (`Fixtures#createProject`)**: `ProjectService.create` takes an owner, so every fixture project has one. Pass the `Actor` that should own it; the `createProject(name)` overload registers a throwaway `nobody` user instead, which counts towards `user` and `user_to_project` rows.
- **Worker Cleanup**: Active worker registrations in `WorkerService.activeWorkers` reside in-memory; tests interacting with WebSockets must disconnect workers and clear sessions in `@AfterEach`.
- **E2E Filtering**: Tier C test classes must follow the naming pattern `*E2ETest.java` to be excluded from standard `./gradlew test` runs.

## `%test` Profile Configuration

| Setting | Purpose |
| --- | --- |
| `worker.secret` / `secret.keys` | Provides dummy keys required by startup validators. |
| `quarkus.oidc.enabled: false` | Disables OIDC provider requirements during testing. |
| `schema-management.strategy: update` | Generates schema into the ephemeral Dev Services Postgres database. |
| `job.pending.interval: PT24H` | Freezes the background queue dispatcher so tests can trigger `tick()` manually. |

