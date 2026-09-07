# 审计报告：`1b0a027^..d1da0e8`（集成测试落地的三个 commit）

- 范围：`1b0a027` implement integrated testing、`b3da858` setup e2e test、`d1da0e8` fix e2e test（= 当前 HEAD）。审计对象是三者叠加后的**最终状态**，48 个文件，+5879/−41。
- 方法：纯静态阅读（源码、`git diff`/`git show`、IDEA 索引、Quarkus/ArC 依赖源码），**未构建、未运行任何测试**。行号以 HEAD 为准。
- 已排除：用户已知的 `JobResource.listTemplates` 增加 `projectService.require`（`JobResource.java:72`，有注释说明全局模板会匹配任意 id，合理）。
- 严重度：🔴 高 / 🟠 中 / 🟡 低 / ✅ 良好。每条给出位置与建议动作，供后续模型直接执行。

---

## 0. 结论摘要

1. **为迎合测试而改的生产逻辑**：除已知的 `listTemplates` 外，有一处**冗余**改动（`listJobs` 的 `require`），一处**依据错误**的配置（`%test` 关闭缓存），一处可见性放宽（`tick()`）且其配套解释有误。没有发现真正"绕过/削弱"业务逻辑的改动。
2. **最严重的问题不在测试里**：`application.yml` 在删除 dev 用 OIDC 密钥时，把**非密钥的 OIDC 行为配置**（`application-type: web-app`、scopes、`redirect-path`、`restore-path-after-redirect`）一起删了，且没有任何地方（代码/文档/CI）再记录它们。按当前仓库状态部署到 prod，浏览器登录流程大概率不工作。此外泄露的 Gitea client secret 仍在 git 历史里，需要轮换。
3. **测试代码**：整体设计（`DatabaseCleaner` / `Fixtures` / 真 PAT 认证链 / 时间窗断言 / 有界轮询）是扎实的。问题集中在：两处**空洞断言**、一处**文档与实现不符**（声称测了并发争用，实际全是串行）、worker→server 上报协议整段**未覆盖**、若干只断状态码的薄弱断言、少量低价值/可合并的测试。
4. **文档**：`agent-docs/` 新增/修改内容里有多处与代码或彼此矛盾（详见 §5），且把机器特有信息写进了仓库文档。

---

## 1. 为迎合测试而修改的程序逻辑

### 1.1 🟠 `JobResource.listJobs` 的 `projectService.require` 是冗余的

- 位置：`src/main/java/io/ib67/prts/project/resource/JobResource.java:98`
- 事实：同一方法在 `:103` 调用 `jobService.listVisible(projectId, depth)`，而 `JobService.listVisible`（`src/main/java/io/ib67/prts/project/JobService.java:62-65`）**在本次改动之前**就已经 `projectService.require(projectId)`。`JobResourceE2ETest.java:80-87 anAdminIsToldWhenTheProjectDoesNotExist` 对 `/job` 的 404 断言不依赖新加的这行。
- 影响：行为不变，但一次请求对不存在的项目查两遍；且和 `listTemplates` 那行不同，这行**没有注释说明为何存在**，读者会以为它承担了什么。
- 建议：删掉 `:98`。保留 `:72`（`listTemplates`）及其注释。`ProjectService` 注入仍需保留。

### 1.2 🟠 `%test` 关闭 `quarkus.cache` 的理由不成立，降低了测试保真度

- 位置：`src/main/resources/application.yml:88-90`；`agent-docs/testing.md:88`（表格行 `quarkus.cache.enabled: false`）
- 声称："`user-permissions` caches grants for 5 minutes, so a grant made mid-test would not be observed."
- 事实：`PermissionService`（`src/main/java/io/ib67/prts/user/PermissionService.java`）的 `grant`(:86-94) / `grantAll` / `revoke` / `revokeAll`×2 / `revokeAllInProject` 全部调用 `invalidateAfterCompletion(userId)`(:150-161)，在事务提交后失效该用户的缓存项。`Fixtures.grant`/`makeAdmin`/`join` 都走这些方法。**测试中途的授权本来就会被观察到。**
- 影响：(a) 生产中缓存是打开的，而所有 tier C 权限矩阵测试跑在"无缓存"的路径上——`grantsOf` → `cache.get(...)` 这条真实路径及其失效逻辑在 CI 中零覆盖；如果将来有人漏掉 `invalidateAfterCompletion`，测试不会发现。(b) 注释把一个不存在的约束写进了配置和文档。
- 建议：删除 `application.yml:88-90` 与 `testing.md` 对应行，让 CI 跑一遍确认全绿（预期全绿）。若有个别测试因缓存失败，那正是应当修的生产 bug，而不是关缓存的理由。可顺手加一个 tier C 用例：`grant` 后立刻通过 HTTP 验证权限生效、`revoke` 后立刻失效，把失效逻辑锁住。

### 1.3 🟡 `PendingJobDispatcher.tick()` 放宽为包私有；配套的 `ClientProxy.unwrap` 解释错误

- 位置：`src/main/java/io/ib67/prts/pending/PendingJobDispatcher.java:61`（`private` → 包私有，注释 "so a test can drive a single pass"）；`PendingJobServiceE2ETest.java:302-306`；`agent-docs/testing.md:71-73`
- 放宽可见性本身：为测试放宽到包私有是常规做法，有注释，可接受。
- 但 `unwrap` 的理由不成立：测试与文档都说"client proxy 只代理 public 方法，直接调会打到字段全 null 的实例"。查 `arc-processor-3.38.2` 源码 `io/quarkus/arc/processor/Methods.java:93-128 skipForClientProxy`，只跳过 `static`、`private`、`<init>`/`<clinit>`、`Object` 的非 `toString` 方法和部分 `final`；**包私有方法会被 ArC 生成的同包 `_ClientProxy` 正常委托**。`ClientProxy.unwrap` 无害，但写下的约束是虚构的。
- 建议：二选一——(a) 去掉 `unwrap`，直接 `dispatcher.tick()`，删掉两处解释；(b) 保留 `unwrap` 但把解释改成真实理由（例如"绕过拦截器/代理，直接驱动一次 pass"）。推荐 (a)。

### 1.4 🟡 `containsKey(null)` → `anyMatch(Objects::isNull)`：良性加固，但并非生产 bug

- 位置：`src/main/java/io/ib67/prts/agent/job/JobSpec.java:70-73`；`src/main/java/io/ib67/prts/agent/worker/WorkerScheduler.java`（`workersForVolumes`）
- 事实：`JobSpec` 紧凑构造器只做 `Objects.requireNonNullElse(volumes, Map.of())`（`:34`），**不**做 `Map.copyOf`。生产路径上 `volumes` 来自 Jackson（`LinkedHashMap`，且 UUID key 反序列化时 null 会先抛错）或 `JobSpecOverride.mergeMap`（`LinkedHashMap`），都允许 `containsKey(null)`。只有 Java 代码里手写 `Map.of(id, spec)`（正是测试 `Fixtures`/tier B 的构造方式）才会触发 NPE。
- 结论：改动正确且值得保留，但它修的是"测试构造方式暴露出的潜在 500"，注释里"would turn this 400 into a 500"的说法在真实请求路径上不会发生。不需要动代码；如果要精确，注释可改为"防御 `Map.of`/不可变 map 调用方"。（本条主要是纠正之前一版审阅中"`JobSpec` 会 `Map.copyOf` 规范化"的错误说法。）

### 1.5 说明：`JobLock.releaseBy` javadoc 与 d1da0e8 的测试改写

- `d1da0e8` 把 `JobLockE2ETest.releasingFreesEveryNameTheJobHolds`（一个 job 取两个名字再 `releaseBy`）改成 `aJobHoldsAtMostOneName`（`:146-155`），因为 `job_id` 是 `unique`（`JobLock.java:55`），原测试的前置状态在 schema 上就不可能。这个改写方向是对的——测试迁就了 schema 而非反过来。
- 但同一 commit 里有三处遗留，见 §3.2、§5.5。

---

## 2. 生产配置 / 代码的其他问题（非测试）

### 2.1 🔴 `application.yml` 删掉了 prod 也依赖的 OIDC 行为配置

- 位置：`src/main/resources/application.yml`（`%dev` 块，`:53-57`）；对照 `git show 1b0a027^:src/main/resources/application.yml` 第 ~47-62 行。
- 删掉的内容中，`auth-server-url` / `client-id` / `credentials.secret` 属于该删的密钥与环境值；但一起被删的还有：
  - `application-type: web-app` —— 没有它 Quarkus OIDC 默认是 `service`（只接受 Bearer），**浏览器授权码流程整体不存在**；
  - `authentication.scopes: [profile, email]` —— `UserIdentityAugmenter.java:56` 读 `email` claim、`:61` 读 `name`/`preferred_username`，缺 scope 时 ID token 里没有这些 claim，首登注册会失败（旧注释原话："these two populate the name/email claims that OidcUserProvisioner needs"）；
  - `authentication.redirect-path: /auth/callback` —— 必须与 Gitea 上注册的回调 URI 完全一致；
  - `authentication.restore-path-after-redirect: true`。
- 这些值原本写在 `%dev` 下，但 `%prod` 没有自己的 OIDC 块——也就是说旧状态下 prod 是否可用本来就靠环境变量；新状态下 `agent-docs/authorization.md:7-10` 明确写"a deployment supplies `auth-server-url`, `client-id` and the secret"，**只列了三个值**，把上述四项从任何可见记录里抹掉了。
- 建议：把这四项放回根级 `quarkus.oidc`（它们不是密钥，对所有 profile 都成立；`%dev`/`%test` 的 `enabled: false` 依然生效），并在 `authorization.md` 和 `build-and-run.md` 的部署段落写清楚哪些是环境提供、哪些在文件里。同时检查现有部署的环境变量是否已经含这四项。

### 2.2 🔴 泄露的 Gitea OAuth client secret 仍在 git 历史中

- `gto_5n2mulz7…`（client-id `df2b6625-…`，`https://git.sfclub.cc`）在 `1b0a027^` 及更早的所有提交里可见。从 HEAD 删除不等于撤销。
- 建议：在 Gitea 上轮换该 OAuth 应用的 secret（运维动作，不是代码改动）。是否重写历史由用户决定；若仓库不公开，轮换即可。

### 2.3 🟠 `%dev` 的 Dev Services Postgres 固定 5432，且 dev 现在必须有 Docker

- 位置：`application.yml:45-49`；`agent-docs/build-and-run.md:33`
- 旧的 `jdbc.url` 指向宿主机 `localhost:5432`（有一个真实 Postgres）。新配置让 Testcontainers 起一个容器并**映射到宿主机 5432**——如果那个宿主机 Postgres 还在跑，`quarkusDev` 启动直接端口冲突。注释只解释了"为了 `psql -h localhost -U prts prts` 还能用"。
- 另外 `build-and-run.md:33` 说 dev 需要 Docker daemon，而项目 CLAUDE.md 与记忆都记录着"本机 Docker 按需、由用户启动"。dev 环境的启动成本被这次改动抬高了，但没有在 commit 里说明是有意为之。
- 建议：确认用户意图。若保留 Dev Services，建议去掉 `port: 5432`（或改成非冲突端口如 `55432`）并把 `psql` 命令同步；若用户更想用宿主机 Postgres，恢复 `%dev` 的 `jdbc.url` 并在 `build-and-run.md` 写清两种方式。

### 2.4 🟠 `DevAdminSeeder` / `DevAuthMechanism` 在 CI 里从未真正启动过

- 位置：`src/main/java/io/ib67/prts/auth/DevAdminSeeder.java`、`DevAuthMechanism.java`（均 `@IfBuildProfile("dev") @IfBuildProperty(name="quarkus.oidc.enabled", stringValue="false")`）
- 事实：这两个 bean 只在 `dev` 构建 profile 下存在，`@QuarkusTest` 用 `test` profile 编译，所以 tier C 完全不包含它们；tier B 的 `DevAdminSeederTest` / `DevAuthMechanismTest` 全是 Mockito。也就是说"启动 quarkusDev → 播种 admin → 无凭据请求自动登录"这条链**只在开发者本机验证过**，CI 没有信号。
- 潜在风险点（静态看，逻辑本身没发现 bug）：
  - `DevAdminSeeder.seed()` 每次启动 `accessTokenService.issue(...)` 重发 token，会把用户上一个 PAT 作废（`issue` 是单 token 替换语义）。dev 前端如果缓存了旧 token 会在重启后失效。已在注释里说明，属设计选择。
  - `DevAuthMechanism.authenticate` 用 `AccessTokenAuthenticationRequest(token)` 走真实链，并且在有 `Authorization`/`X-Worker-Token` 头时让路——这个设计正确，避免了"永远是 admin"。
  - 两个 bean 都要求 `quarkus.oidc.enabled=false`，而 `%dev` 恰好如此；如果将来 dev 想接真 OIDC，只改 `enabled: true` 它们就自动消失，行为合理。
- 建议：不改代码；在 `build-and-run.md` 的 dev 段落注明"这条链无自动化测试，改动后需手动起一次 quarkusDev 验证"。

### 2.5 🟡 `WorkerAuthMechanism.HEADER` 抽常量、`WorkerWebSocket` 注释替换、`JobLock.releaseBy` javadoc

- 均为无行为改动的整理，✅ 无问题。

---

## 3. 测试代码：空洞、失实、缺口

### 3.1 🔴 空洞断言：`aViewerCanReadOneJob` 的 `createRequest nullValue()` 永远为真

- 位置：`src/test/java/io/ib67/prts/project/resource/JobResourceE2ETest.java:153-164`
- 注释说"JOB_CREATE is a MEMBER's, so a viewer is not handed the payload to re-run it"，意图是测 `requestFor(mayCreate, ...)`（`JobResource.java:149-150`）的权限分支。
- 但 `Fixtures.job(...)`（`Fixtures.java:125-138`）**从不设置 `templateId`/`createOverride`**，而 `Job.toRequest()`（`Job.java:127-129`）在 `templateId == null` 时返回 `null`。于是无论调用者是 VIEWER 还是 OWNER，`createRequest` 都是 `null`——断言与权限无关，也没有一个"MEMBER 能拿到 `createRequest`"的正向用例作对照。
- 建议：给 `Fixtures.job` 增加一个接受 `templateId`（可选 `override`）的重载；补一个 MEMBER/OWNER 读同一 job 时 `createRequest` 非空且字段正确的用例；现有 VIEWER 用例改用带 template 的 job，让 `nullValue()` 变成有意义的断言。同理检查 `listJobs`/`pending` 路径上 `createRequest` 的两个分支是否有正向覆盖。

### 3.2 🔴 文档与实现不符：`JobLockE2ETest` 并未测试并发争用

- 位置：`agent-docs/testing.md:142`（"including contention from parallel threads"）；`JobLockE2ETest.java:24`（javadoc "under contention"）
- 事实：该类没有任何 `Thread`/`Executor`/`CountDownLatch`（grep 为空），所有 `acquire` 都是串行 `inTx`。`JobLock.tryAcquire`（`JobLock.java:69-96`）真正有并发风险的分支——两个首次获取者同时 `findById(key, PESSIMISTIC_WRITE)` 得到 null 后同时 `persistAndFlush` 撞主键，由 `WorkerScheduler.acquireLock` 把异常转成 `false`——完全未覆盖。
- 建议：要么补一个真正的两线程用例（两个 `requiringNew` 事务用 latch 对齐后同时对同名 lock 首次获取，断言恰好一个成功、另一个拿到 `false` 或被 `acquireLock` 吞掉），要么把文档和 javadoc 改成"sequential takeover semantics"。不要让文档继续声称有并发覆盖。
- 同一文件遗留：
  - `:141-145` 新 javadoc 仍引用 `releaseBy` 的旧措辞 "any lock"，而 `d1da0e8` 自己已把 `JobLock.java:99` 改成 "at most one"。同一 commit 内自相矛盾，改注释。
  - `:152 assertThrows(RuntimeException.class, ...)` 接受任何运行时异常（NPE、事务包装异常、约束冲突都算）。建议收窄为 Hibernate/JPA 的约束冲突类型，或至少断言 cause 链里含 `ConstraintViolationException`。
  - `:93-102 everyTerminalStateCountsAsFinished` 完全包含 `:82-91` 的 `SUCCESS` 用例；`:50-56` 是 `:59-66` 的子集。可合并（低优先级）。

### 3.3 🟠 覆盖缺口：worker → server 上报半边协议没有 tier C 测试

- `WorkerWebSocketE2ETest` 只测了 `Register` / `ResourceReport` / disconnect。`JobStateUpdate`（`JobService.applyState` 的"服务端权威、终态忽略"规则）、`UpdateJobLog`、`UploadArtifactRequest`、`JobCreated` 在 `src/test` 下无任何引用。
- 连带后果：`agent-docs/job-lifecycle.md` 列出的 `JobLock` 释放点里，除 `releaseBy` 自身（`JobLockE2ETest.java:118-139`）外一个都没被端到端验证；`JobResourceE2ETest.java:298-345` 打了 cancel 端点但对 lock 无断言。
- `testing.md:145` 把 WebSocket 那行写成 "register / report / disconnect"，"report" 读起来像 job 上报，实际只有 `ResourceReport`；`:147-153` 的 "Still to do" 也没列这块。
- 建议：至少补 `JobStateUpdate`（含终态后再收到 RUNNING 被忽略）和 `UpdateJobLog` 两个用例；文档 "Still to do" 补上其余项。

### 3.4 🟠 断言薄弱（只断状态码 / 断框架默认文案 / 硬编码配置值）

| 位置 | 问题 | 建议 |
| --- | --- | --- |
| `SecretResourceE2ETest.java:192-203 theSameNameInAnotherProjectIsFree` | 只断 `200`，没验证第二个项目里确实出现了 `TOKEN` 且第一个项目的值未变 | 加 GET 列表断言 |
| `SubAccountResourceE2ETest.java:126-134 anExplicitGrantManagesWithoutTheOwnerRole` | 只断 `201` | 断 body 里 `name`/`userId`，或随后 GET 列表 |
| `WorkerResourceE2ETest.java:88-97` | 断 `message == "HTTP 404 Not Found"`，这是 JAX-RS 无参 `NotFoundException` 的默认文案，测的是框架而非本项目 | 只断状态码；`testing.md:66-70` 自己也这么建议 |
| `JobResourceE2ETest.java:361, 373` | 硬编码 `length == 20`，即 `job.log.max-page-size` 的当前值 | 注入 `JobConfig` 读取，或在 `%test` 显式设一个值并注释来源 |
| `ProjectDeletionE2ETest.java:192-213` | 只断 13 张表清空；`stopWork` 对 `RUNNING` job 做了什么、`deleteObjects` 是否到达 S3 都没断（`fixtures.artifact` 不创建对象，`deleteQuietly` 是 no-op） | 可接受为"删除测试"，但把 S3 路径明确记入 "Still to do" |
| `ProjectResourceE2ETest`（被 `testing.md:136` 称为 "worked example"） | 只有 GET，没有 `MEMBER` 角色，没有 admin 对不存在项目的 404 | 补齐，或别称它为范例 |
| 各 `*ResourceE2ETest` 的"删除后 GET" | 多处删除后再 GET 没断状态码 | 补 `404` |
| `WorkerWebSocketE2ETest.java:212-229` | 对 `elsewhere` 的否定断言在正向 `await` 之后立刻读取，依赖 `failJobsOf` 是单条 select+update 的实现细节（注释 `:226-227` 承认） | 可接受，留注释即可 |

### 3.5 🟡 低价值 / 可合并的测试

- Tier B `DevAdminSeederTest`：`thereIsNoTokenBeforeSeeding`(:61)、`theTokenIsRerolledEveryBoot`(:99)、`theAdminGrantIsGlobalAndUnconditional`(:88) 基本是在断言 mock 的 stub 被调用；`PendingJobDispatcherTest.theBatchSizeComesFromConfig` 同类。保留无害，但不要再加同类。
- `InlineTransactionsTest.java`：3 个用例测的是测试辅助类自身。可以留（它保护的是所有 tier B 的地基），但归类为 infra 测试。
- `ResourceClassFinderE2ETest.java:95-101`：只打到 `ResourceClass.deleteByProject` 的 `IllegalArgumentException` 守卫（`:89-94`），在任何 Panache 调用之前就抛了——为它启动整个 Quarkus 是浪费，应下沉到 tier A。
- 权限矩阵里大量 401/"non-disclosure 404" 用例是复制粘贴（`JobResourceE2ETest`、`SecretResourceE2ETest`、`SubAccountResourceE2ETest`、`UserTokenResourceE2ETest`），结构完全一致，可用 `@ParameterizedTest` + `MethodSource` 收敛。非必须。
- `SubAccountResourceE2ETest` 的跨项目子账号用例只测 GET；`SecretResourceE2ETest` 的 presign 断言弱（只看有 URL）。

### 3.6 🟡 Tier B 中的小失真

- `AccessTokenAuthMechanismTest.java:108-112`：注释写 "OIDC (1000)"，实际 Quarkus OIDC 机制默认优先级是 `1001`（`quarkus-oidc-3.38.2` 源码 `io/quarkus/oidc/runtime/OidcConfig.java:52`：`@WithDefault((HttpAuthenticationMechanism.DEFAULT_PRIORITY + 1) + "")`，由 `OidcAuthenticationMechanism.java:46` 读入）。断言 `> 1000` 对 1500 仍成立但界不严；改注释并把界改成 `> 1001`。
- `DevAuthMechanismTest.java:90-94`：硬编码 `1500`，应引用 `AccessTokenAuthMechanism.PRIORITY`（若无此常量则抽一个），否则改了一边另一边不会报。
- `testing.md` 的 tier B 清单（`:129-131`）漏列 `DevAdminSeederTest`、`DevAuthMechanismTest`。
- 未测：`WorkerScheduler`（tier A/B 可测）、类级 `@RequirePermission` 在 `RequirePermissionInterceptorTest` 中无用例。

### 3.7 🟡 fixture 状态真实性

- `Fixtures.job` 的 `worker` 传 `UUID.randomUUID()` 但不建 `Worker` 行：`Job.worker` 是无 FK 的裸 UUID（`Job.java:93`），合法。
- `JobLockE2ETest.java:93-102` 让已终态的 job 去 acquire：生产不会发生，但形成的行等价于"崩溃未释放"状态，仍在测 takeover 分支（`JobLock.java:81-87`）。可接受，建议注释说明。
- `Fixtures.queued` 绕过 `PendingJobService.enqueue`：有注释（需要 `UserContext`），合理。
- `PendingJobServiceE2ETest.java:33` javadoc "No worker is ever connected in a tier C run" 为假（`WorkerWebSocketE2ETest` 会连），实际靠 `:295` 的 `!hasSchedulableWorker()` 前置断言兜底。改注释。
- `PendingJobFinderE2ETest.java:135` javadoc 说 "cancelled"，`:139` 设的是 `EXPIRED`。改注释。

---

## 4. 构建 / CI / 仓库卫生

- 🟡 `.gitignore` 新增了 `.claude`，但 `.claude/settings.local.json`（`{"permissions":{"allow":["mcp__idea__build_project"]}}`）在 `1b0a027` 里被提交，至今仍被跟踪（`git ls-files .claude` 可见）。`git rm --cached .claude/settings.local.json`。
- 🟡 `build.gradle:52-54` 给 `compileTestJava` 加了 `-parameters`，没有找到需要它的测试（REST/JSON 参数名依赖只在 main）。删掉或加注释说明用途。
- 🟡 `build.gradle:59-60` `e2eRequested` 靠 `taskNames` 精确匹配 `e2eTest`：Gradle 任务缩写（`./gradlew e2eT`）、IDE 里直接跑单个 `*E2ETest` 类（taskNames 是 `test`）都不会触发，后者会因为 `exclude '**/*E2ETest*'` 直接"0 tests"。至少在 `testing.md` 写明 IDE 单跑需要 `-Pe2e`。
- 🟡 `test-reports.zip` 在工作区未跟踪，疑为 CI 产物下载后遗留。加入 `.gitignore` 或删除。
- ✅ `.github/workflows/ci.yml`：temurin 21、`setup-gradle@v4`、`./gradlew e2eTest --console=plain --stacktrace`、报告上传、concurrency cancel-in-progress——没有问题。
- ✅ `quarkus-junit-mockito` 依赖：tier B 的 `@InjectMock` 需要它，合理。`testing.md` 里对 `MockitoExtension`/JUnit 6 的谨慎措辞可以放宽——`mockito-junit-jupiter 5.21.0` 已在 classpath。

---

## 5. 文档不一致（`agent-docs/` 与 `CLAUDE.md`）

1. 🟠 `agent-docs/authorization.md:7-10`：部署只需提供 "auth-server-url, client-id and the secret"——不完整，见 §2.1。
2. 🟠 `agent-docs/testing.md:142`：并发争用声明为假，见 §3.2。
3. 🟠 `agent-docs/testing.md:88` 与 `application.yml:88`：关缓存理由为假，见 §1.2。
4. 🟡 `agent-docs/testing.md:71-73` 与 `PendingJobServiceE2ETest.java:302-305`：client proxy 只代理 public 方法的说法为假，见 §1.3。
5. 🟡 `agent-docs/testing.md:90`："`PT1S` lets `PendingJobDispatcher` tick during tests; freeze it" 读起来像 `%test` 值是 `PT1S`，实际 `%test` 覆盖为 `PT24H`（`application.yml:97-100`）。改成"根配置 `PT1S`，`%test` 覆盖为 `PT24H`"。
6. 🟡 `agent-docs/testing.md:133`："14 classes, 193 tests" 硬编码计数（`d1da0e8` 已经从 192 改到 193 一次）。删掉数字，只保留类名表。
7. 🟡 `agent-docs/build-and-run.md:59` 与 `agent-docs/secrets.md:32`：`secret.keys`/`worker.secret` "only under `%dev`"——现在 `%test` 也有（`application.yml:91-96`）。改为 "`%dev` 和 `%test`"，并说明 test 用的 key 是一次性的。
8. 🟡 `CLAUDE.md`（"There is no JDK on the shell PATH, so `./gradlew` fails from Bash"）与 `agent-docs/build-and-run.md`（`JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.9-jbr ./gradlew test`）互相矛盾；后者路径在本机存在。两处统一为"通过 `JAVA_HOME=... ./gradlew` 可跑 tier A/B；IDE MCP 也可"。
9. 🟡 `build-and-run.md` 把机器特有信息（sdkman 路径、root shell、docker group、"Do not ask for sudo"）写进了仓库文档；这些属于开发者本机/`CLAUDE.local.md`/记忆，不该进 `agent-docs/`。搬走。
10. 🟡 `testing.md` 的 "Coverage" 与 "Still to do"（`:128-153`）偏长且会过期，与"文档保持精简"的项目偏好相悖；建议只保留表格和一行 "Still to do" 链接到 `TODO.md`，把待办移过去。
11. 🟡 `testing.md:145` WebSocket 行的 "report" 措辞见 §3.3；tier B 清单漏项见 §3.6。

---

## 6. 做得好、不要动的部分

- `DatabaseCleaner`：对 `pg_tables` 拼 `TRUNCATE ... RESTART IDENTITY CASCADE` 一条语句清库，正确解决了 `@TestTransaction` 回滚不了 `requiringNew()` 提交的问题，文档解释到位。
- `Fixtures`：真实 PAT 认证而非 `@TestSecurity`，让 `@RequirePermission` → `PermissionService` → `UserIdentityAugmenter` 整条链被端到端覆盖；`inTx` 与 `JobLauncher` 调用实体静态方法的方式一致；`attach()` 处理 detached 行的做法正确。
- `WorkerWebSocketE2ETest`：`@AfterEach` 等待 roster 清空 + `PendingJobServiceE2ETest:295` 用前置断言而非假设，规范地处理了 `WorkerService.activeWorkers` 这类跨 `DatabaseCleaner` 的应用态；`Thread.sleep` 只出现在有界 `await` 里（10 s / 20 ms）。
- 时间断言：截断到毫秒的双侧窗口（`PendingJobServiceE2ETest:315-333`、`PendingJobFinderE2ETest:201-223`）与 Postgres `timestamp(6)` 往返一致，且恰好测到了 `expireOverdue` 的严格 `<` 边界。
- `ProjectDeletionE2ETest.fill()` 给每张表都种了行（含手工删除的 `user_permission`、`resource_class`、子账号用户），13 张表的枚举与 `project-deletion.md` 对得上。
- `anEntryCancelledMidAttemptStaysCancelled` 与具体的 409 断言测的是 `inFlight` 守卫这类真正有意思的分支。
- `*E2ETest` 强制后缀及其原因（Quarkus `FacadeClassLoader` 在 JUnit 发现阶段就启动应用，`excludeTags` 来不及）在 `testing.md:114-119` 记录准确。
- `DevAuthMechanism` 用 `@IfBuildProfile("dev")` 而不是运行时判断，保证 test 构建里根本没有自动登录 bean，权限矩阵不可能"以 admin 身份通过"。
- `SecretCipherTest` 用真实加解密而非 mock；tier B 的 `mockStatic` 使用全部 try-with-resources 收尾，无泄漏。
- `.github/workflows/ci.yml` 干净。

---

## 7. 建议的处理顺序

1. **§2.1** 恢复四项 OIDC 行为配置到根级 `quarkus.oidc`，更新 `authorization.md`/`build-and-run.md` 部署段。**§2.2** 提醒用户轮换 Gitea secret（不要自行操作）。
2. **§1.2** 删 `%test` 的 `cache.enabled: false` 及文档行；补 grant/revoke 立即生效的 tier C 用例。
3. **§1.1** 删 `JobResource.java:98`。
4. **§3.1** `Fixtures.job` 支持 `templateId`；补 `createRequest` 正/反两个用例。
5. **§3.2** `JobLockE2ETest`：补真实并发用例或改文档；修 `:141-145` 注释；收窄 `:152` 异常类型。
6. **§3.3** 补 `JobStateUpdate` / `UpdateJobLog` 的 WebSocket 用例；更新 `testing.md` 的 "Still to do"。
7. **§1.3** 去掉 `ClientProxy.unwrap` 及两处错误解释（或改为真实理由）。
8. **§2.3** 与用户确认 dev 数据库策略（Dev Services 端口 vs 宿主机 Postgres）。
9. **§3.4 / §3.5 / §3.6 / §3.7** 断言加强与注释修正，按表逐条。
10. **§4 / §5** 仓库卫生与文档统一：`git rm --cached .claude/settings.local.json`、`-parameters`、`test-reports.zip`、`testing.md` 瘦身与计数删除、`build-and-run.md` 机器特有内容外迁、`CLAUDE.md` 与 `build-and-run.md` 统一说法。

以上所有改动完成后跑一次 CI（`e2eTest`）；本地只跑 `./gradlew test`（tier A/B）。
