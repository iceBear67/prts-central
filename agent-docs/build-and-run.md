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
- **Database Schema**:
  - `%dev` uses `schema-management.strategy: update`.
  - `src/main/resources/import.sql` serves as reference DDL (commented out).
  - Explicit `columnDefinition = "varchar"` is maintained across entity string fields to align with reference DDL.
  - `update` only adds tables/columns; schema drops or migrations require manual DDL via `psql`: `PGPASSWORD=prts psql -h localhost -U prts -d prts`.
- **Default Secrets**: `secret.keys`, `secret.active-key`, and `worker.secret` have defaults only under `%dev` and `%test` profiles. Production requires explicit environment variables.

