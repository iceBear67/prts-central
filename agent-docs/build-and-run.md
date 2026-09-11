# Build & Run Guide

## Prerequisites & Compilation
- **Java**: Requires JDK 21+ (`export JAVA_HOME=...`).
- **Standard Builds**:
  - Dev/build: `./gradlew build`
  - Uber-jar: `./gradlew build -Dquarkus.package.jar.type=uber-jar`
  - Native: `./gradlew build -Dquarkus.native.enabled=true`
- **Testing**:
  - Fast local tests (Tiers A & B): `./gradlew test`
  - Container-backed tests (Tier C): `./gradlew e2eTest` (see [testing.md](testing.md))
- **IDE & Validation**:
  - `mcp__idea__build_project`: Incremental compilation via IntelliJ index.
  - CDI Augmentation Check: Run configuration `prts-central [build]` verifies CDI injection points, interceptor bindings, and build-time augmentation.

## Local Development Mode (`quarkusDev`)

Run via `./gradlew quarkusDev`. Dev UI available at `http://localhost:8080/q/dev/`.

### Dev Services & Containers
- **Docker Daemon**: Must be running. Dev Services spins up Postgres (`localhost:5432`, user/pass/db: `prts`) and LocalStack (S3 bucket `prts-artifacts`).
- **OIDC & Dev Auth**: `%dev` disables OIDC (`quarkus.oidc.enabled: false`) and enables `DevAuthMechanism` / `DevAdminSeeder` to auto-authenticate uncredentialed requests as `ADMIN_OF_ALL`.
  - *Note*: Dev auth beans are `@IfBuildProfile("dev")` and not executed in test suites. Verify manually via `GET /api/project` -> 200 after modifying auth wiring.
- **Example Data (`ExampleDataSeeder`)**: On startup, `%dev` fills a database holding **no project** with
  three example projects (one archived), workers, resource classes, templates, tasks, volumes, secrets,
  a sub-account, a job history with logs and artifacts, queue entries and notifications — all owned by or
  shared with the dev user, so `GET /api/project` is not empty on a first run.
  - Guarded on `Project.count() == 0`, so a live reload never stacks a second copy on top of your own data.
  - Set `dev.seed-examples: false` to turn it off. Removing the data means deleting the projects; the
    next startup will seed again only once none is left.
  - Backdates `created_at` with native SQL — the column is `@CreationTimestamp` and `updatable = false`.
  - Artifacts get a placeholder object put into the dev bucket so their download links resolve; if
    LocalStack is unreachable the rows are still seeded and a warning is logged.
  - Dev-only beans are `@IfBuildProfile("dev")` and never run in test suites; verify manually.
- **Database Schema**:
  - `%dev` uses `schema-management.strategy: update`.
  - Explicit `columnDefinition = "varchar"` is maintained across entity string fields.
  - `update` only adds tables/columns; schema drops or migrations require manual DDL via `psql`: `PGPASSWORD=prts psql -h localhost -U prts -d prts`.
- **Default Secrets**: `secret.keys`, `secret.active-key`, and `worker.secret` have defaults only under `%dev` and `%test` profiles. Production requires explicit environment variables.

