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
  - Mock worker (`worker-mock/`, a subproject with no dependency on this project's classes):
    `./gradlew :worker-mock:test` (see [../worker-mock/README.md](../worker-mock/README.md))
- **IDE & Validation**:
  - `mcp__idea__build_project`: Incremental compilation via IntelliJ index.
  - CDI Augmentation Check: Run configuration `prts-central [build]` verifies CDI injection points, interceptor bindings, and build-time augmentation.

## Local Development Mode (`quarkusDev`)

Run via `./gradlew quarkusDev`. Dev UI available at `http://localhost:8080/q/dev/`.

### Dev Services & Containers
- **Docker Daemon**: Must be running. Dev Services spins up Postgres (`localhost:5432`, user/pass/db: `prts`) and LocalStack (S3 bucket `prts-artifacts`).
- **OIDC & Dev Auth**: `%dev` disables OIDC (`quarkus.oidc.enabled: false`) and enables `DevAuthMechanism` / `DevAdminSeeder` to auto-authenticate uncredentialed requests as `ADMIN_OF_ALL`.
  - *Note*: Dev auth beans are `@IfBuildProfile("dev")` and not executed in test suites. Verify manually via `GET /api/project` -> 200 after modifying auth wiring.
- **Example Data (`ExampleDataSeeder`)**: In `%dev`, populates an empty database (`Project.count() == 0`) with sample projects, workers, resource classes, templates, tasks, volumes, secrets, jobs, logs, and artifacts.
  - Can be disabled via `dev.seed-examples: false`.
  - Uses native SQL to backdate non-updatable `@CreationTimestamp` columns.
  - Uploads placeholder S3 objects to LocalStack for seeded artifacts; logs a warning if LocalStack is unavailable.
  - Dev-only beans are `@IfBuildProfile("dev")` and excluded from test suites.
- **Mock Worker (`MockWorkerRunner`)**: In `%dev`, a worker from [`worker-mock/`](../worker-mock/README.md) connects back to dev mode itself, so a job created from the UI is placed, run and finished instead of queueing forever. It registers under a fixed id, reports more capacity than any seeded resource class, and accepts every volume.
  - `dev.mock-worker.script` is what a job gets: `demo` (log lines, an artifact, then SUCCESS), `succeed`, `fail`, `hang` (runs until cancelled), `agent` (attaches an echoing ACP agent and stays running). A job overrides it with the label `prts.mock`.
  - Disable with `dev.mock-worker.enabled: false`; `id`, `name`, `url` and `resources.*` tune the rest.
  - The module is on the dev classpath only — `compileOnly` plus `quarkusDev` in `build.gradle` — so it never reaches a production build. Changing that wiring needs a dev mode restart, not a live reload.
- **Database Schema**:
  - `%dev` uses `schema-management.strategy: update`.
  - Explicit `columnDefinition = "varchar"` is maintained across entity string fields to prevent Hibernate from defaulting to `varchar(255)`. Length constraints are enforced at the DTO layer via `@Size` (see [http-surface.md](http-surface.md)).
  - `update` only adds tables/columns; schema drops or migrations require manual DDL via `psql`: `PGPASSWORD=prts psql -h localhost -U prts -d prts`.
- **Default Secrets**: `secret.keys`, `secret.active-key`, and `worker.secret` have defaults only under `%dev` and `%test` profiles. Production requires explicit environment variables.

