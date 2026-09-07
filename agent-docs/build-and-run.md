# Build & run

**There is no JDK on the shell `PATH` in this environment**, so a bare `./gradlew ...` fails from Bash.
Prefer the IntelliJ MCP tools; they also reach the IDE index, which the sandbox cannot:

- Compile / check for errors: `mcp__idea__build_project` (optionally with `filesToRebuild`).
- Lint a file the way the IDE does: `mcp__idea__get_file_problems` / `mcp__idea__lint_files`.
- **Validate CDI wiring**: `build_project` only runs `javac`, which cannot see an unsatisfied
  injection point (`@Inject UriInfo`, a missing interceptor binding, ...). That is checked by Quarkus
  augmentation, so run the `prts-central [build]` run configuration through
  `mcp__idea__execute_run_configuration` — it reaches `quarkusBuild` in ~10s and fails loudly.
- Exercise changes at runtime: run the Gradle **`quarkusDev`** task (from the IDE, or ask the user to
  run `! ./gradlew quarkusDev`). Dev UI at <http://localhost:8080/q/dev/>.

Canonical Gradle commands, for reference: `./gradlew build`,
`./gradlew build -Dquarkus.package.jar.type=uber-jar`,
`./gradlew build -Dquarkus.native.enabled=true [-Dquarkus.native.container-build=true]`.

Gradle *does* run from Bash with an explicit JDK — `JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.9-jbr
./gradlew test --console=plain`. That is the way to run the test suite, which `build_project` does not
touch. `./gradlew test` covers tiers A and B and is the only suite run locally: `./gradlew e2eTest` adds
tier C, which needs containers and is left to CI (see [testing.md](testing.md), and read it before adding
a test).

## Local dependencies for `quarkusDev`

- **A Docker daemon must be reachable** — `%dev` sets neither a datasource URL nor an S3
  `endpoint-override`, so Dev Services starts **both** Postgres and LocalStack (the latter creating the
  `prts-artifacts` bucket). Setting either would be read as "something is already running" and suppress
  the container. On this machine the daemon is rootful and started by the user on demand; if it is down,
  ask them to start it.
- Postgres is pinned to `localhost:5432` with db/user/password all `prts`
  (`quarkus.datasource.devservices`) purely so a fixed `psql` invocation keeps working. The container
  dies with the dev process, so every boot starts on an empty schema; `reuse: true` plus
  `testcontainers.reuse.enable=true` in `~/.testcontainers.properties` would change that. Containers do
  survive a live reload — an already-running dev stack is worth reusing rather than restarting.
- **No OIDC provider is needed**, and no credential: `%dev` pins `quarkus.oidc.enabled` false and logs
  uncredentialed requests in as a seeded `ADMIN_OF_ALL` user, whose access token the boot log prints
  for clients that want to send one. A PAT is how the real auth chain gets exercised; there is no
  provider config in the repository. See [authorization.md](authorization.md).
- The `%dev` profile runs Hibernate with `schema-management.strategy: update`.
  `src/main/resources/import.sql` is entirely commented out — it is kept as **reference DDL**, and the
  entities are the source of truth. Many columns carry an explicit
  `columnDefinition = "varchar"` specifically to keep generated DDL aligned with that reference
  (Hibernate would otherwise emit `varchar(255)`); preserve that when adding columns.
- `update` only **adds** tables, columns and indexes — it never tightens nullability, rewrites a key or
  a check, drops a column, or adds a cascade. Such a mapping change needs hand-run DDL, so **the entity
  proves nothing about the running schema**: check `pg_constraint` / `information_schema.columns`. While
  the tables are empty, dropping them all and letting dev mode recreate is the cheapest repair.
- For DDL use `psql` (on the PATH: `PGPASSWORD=prts psql -h localhost -U prts -d prts`).
  `mcp__idea__execute_sql_query` times out on a multi-table `DROP` and truncates results to 10 rows
  silently — page with `mcp__idea__fetch_query_result`.
- `secret.keys` / `secret.active-key` and `worker.secret` are set **only under `%dev`**, deliberately —
  see [secrets.md](secrets.md).
