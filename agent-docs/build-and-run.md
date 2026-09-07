# Build & run

**There is no JDK on the shell `PATH` in this environment**, so `./gradlew ...` fails from Bash. Use
the IntelliJ MCP tools instead:

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

`src/test/java` exists but is **empty** — there are no tests yet. `quarkus-junit` and `rest-assured`
are already on the test classpath, so a new test is `@QuarkusTest` + RestAssured;
run one with `./gradlew test --tests 'io.ib67.prts.SomeTest'`.

## Local dependencies for `quarkusDev`

- **PostgreSQL** is *not* provided by Dev Services: `application.yml` hardcodes
  `jdbc:postgresql://localhost:5432/?user=postgres&password=12345aa`, and a URL being set is exactly
  what stops Dev Services from starting a container. A local Postgres must be reachable.
- **S3** *is* provided by Dev Services (LocalStack), which creates the `prts-artifacts` bucket.
- The `%dev` profile runs Hibernate with `schema-management.strategy: update`.
  `src/main/resources/import.sql` is entirely commented out — it is kept as **reference DDL**, and the
  entities are the source of truth. Many columns carry an explicit
  `columnDefinition = "varchar"` specifically to keep generated DDL aligned with that reference
  (Hibernate would otherwise emit `varchar(255)`); preserve that when adding columns.
- `update` only **adds** tables, columns and indexes — it never tightens nullability, rewrites a key or
  a check, drops a column, or adds a cascade. Such a mapping change needs hand-run DDL, so **the entity
  proves nothing about the running schema**: check `pg_constraint` / `information_schema.columns`. While
  the tables are empty, dropping them all and letting dev mode recreate is the cheapest repair.
- For DDL use `psql` (on the PATH: `PGPASSWORD=12345aa psql -h localhost -U postgres -d postgres`).
  `mcp__idea__execute_sql_query` times out on a multi-table `DROP` and truncates results to 10 rows
  silently — page with `mcp__idea__fetch_query_result`.
- `secret.keys` / `secret.active-key` and `worker.secret` are set **only under `%dev`**, deliberately —
  see [secrets.md](secrets.md).
