# Runtime Feature Flags & Config Delivery (Java / Spring Boot)

一套可回滚的**运行时特性开关与配置下发**能力。核心目标：配置合并、发布、灰度判定与回滚在
并发访问下都保持**可解释、一致、原子**。纯内存实现，无外部服务、无真实凭据依赖。

- Java 21 · Spring Boot 3.4.x · 无数据库 / 无第三方中间件
- 包根：`com.github.xingjianluo.runtimefeatureflagsjava.featureflag`

---

## 1. 分层配置：优先级与生效时机

配置分为三层，自下而上优先级递增，**高层非空值覆盖低层同名键**：

| 优先级 | 层 (`ConfigLayer`) | 来源 | 生效时机 / 生命周期 |
|---|---|---|---|
| 0（最低） | `DEFAULT` | 代码内 `ConfigSchema` 声明的默认值与约束 | 始终存在，随代码发布 |
| 1 | `STARTUP` | 启动配置 `application.properties`（前缀 `featureflag.config.`） | **启动时读取一次并冻结**，进程存活期不变 |
| 2（最高） | `RUNTIME` | 运行时通过发布接口原子下发 | 每次发布产生新版本，可回滚 |

键的类型、默认值、是否必填、取值范围/枚举都集中声明在 `ConfigSchema`
（见 `config/FeatureFlagConfiguration`），合并与校验共用这一份事实来源。

**非法配置绝不静默采用**，按可区分类型拒绝：

- `MISSING_CONFIG`（必填键三层都缺失且无默认值）
- `TYPE_MISMATCH`（字符串无法转换为声明类型，如把 `abc` 配给 `LONG`）
- `INVALID_VALUE`（类型正确但违反约束：超出 `min/max`、不在 `allowedValues`、键未在 schema 声明）

`STARTUP` 层在 `LayeredConfigService` 构造时**立即校验**：启动配置非法会快速失败并中止
应用启动，而不是被忽略；`RUNTIME` 层在发布批次内逐条校验，非法即整批拒绝（见第 2 节）。
错误信息只描述规则与键名，**不回显原始值**，避免敏感输入泄露。

### 内置键

| 键 | 类型 | 默认值 | 约束 |
|---|---|---|---|
| `service.timeoutMs` | LONG | `1000` | [0, 60000] |
| `service.maxRetries` | INT | `3` | [0, 10] |
| `ui.theme` | STRING | `light` | light / dark / system |
| `cache.enabled` | BOOLEAN | `true` | — |
| `payment.mode` | STRING | （必填，无默认） | sandbox / production |

`application.properties` 中本地设置为 `featureflag.config.payment.mode=sandbox`。

---

## 2. 发布与回滚语义

### 版本快照（Version）

所有状态保存在不可变 `Version`（完整 flags 表 + 完整 runtime 配置表 + 元数据）中，由一个
`volatile` 引用指向。读取端一次调用只读这一个引用，因此**任何读取看到的都是一个完整版本**，
绝不会看到“改了一半”的批次。

### 原子发布（publish）

- 一个 `PublishBatch` 可同时包含若干 flag 与 config 条目，**整批生效**。
- 发布时先在当前版本的**私有拷贝**上完成全部校验与组装：
  - flag 的取值规则在 `RolloutRule`/`FeatureFlag` 不可变构造期即被强制（百分比必须 0..100，
    `forceEnabled` 与 `forceDisabled` 不可同时为真）；
  - config 原始字符串逐条交给 `LayeredConfigService.validateValue` 校验。
- **任一条目非法 → 抛 `InvalidBatchException`，活版本不被触碰**，版本号不前进，无部分更新。
- 全部合法才提交为一个新 `Version`，版本号单调 +1。
- 写操作在同一把锁内串行化；并发发布得到一个全序、无丢失更新的版本序列。

### 历史与回滚（rollback）

- 保留**有限**历史（默认最近 20 个版本，可配置）；超出后最旧版本被淘汰。
- 回滚 = 把某个历史版本的完整状态**复制为一个全新版本**重新发布：
  - 不重写历史，回滚动作本身可审计，也可再次回滚；
  - flags 与 config **整批**一起回到目标版本；
  - 目标版本已被历史淘汰时抛 `VersionNotFoundException`（可区分错误）。

### 可区分的判定状态（EvaluationStatus）

`evaluate(flagKey, subject)` 返回四态之一：

- `ENABLED`：开关存在、开启且主体被灰度规则选中；
- `DISABLED`：开关存在但主体未被选中（含主开关 `enabled=false`）；
- `UNKNOWN_FLAG`：当前版本中不存在，且历史上从未出现过；
- `ROLLED_BACK`：曾经存在，但当前版本中已因回滚（或被更早状态覆盖）而消失。

版本中维护 `everKnownKeys` 集合，使“从未存在”与“曾经存在后被回滚删除”可稳定区分。

---

## 2.5 安全发布：预检、认准版本提交、版本差异

在多人协作推线上配置的场景下，发布链路提供三层额外保障（均为**新增能力**，不改变任何既有用法）。

### 发布前预检（precheck / preflight）

`POST /api/feature-flags/precheck`（或 store 层 `preflight(batch)`）接受与发布完全相同的请求体，
只对候选批次做**一次检查**，返回：

- `baseVersion`：本次预检基于的版本号（可用来“认准版本再提交”，见下）；
- `valid` + `issues`：整批能否通过。不合法条目按**类别**区分，不会混成一个笼统失败：
  - `FLAG_RULE_VIOLATION`（开关规则自相矛盾）
  - `UNKNOWN_CONFIG_KEY`（键未在 schema 声明）
  - `TYPE_MISMATCH`（值无法转换为声明类型）
  - `CONSTRAINT_VIOLATION`（违反 min/max 或枚举约束）
  - `MISSING_REQUIRED_VALUE`（必填键为空）
- `changes`：相对当前线上生效版本，每条键会发生什么——`ADDED`（新增）/
  `OVERWRITTEN`（覆盖）/ `UNCHANGED`（不变），并附**覆盖前后各自的脱敏摘要**
  （`sha256:<12位>` 或 `absent`），可比对差异但看不到原值。

预检是**纯只读**：跑完后线上状态不变、版本号不前进、灰度判定结果不受影响；
检查在单个不可变快照上完成，并发读者不会看到任何中间态。

### 认准版本再提交（optimistic concurrency）

发布与回滚都接受可选字段 `expectedBaseVersion`（一般填预检返回的 `baseVersion`）：

- 提供时：若从预检到真正提交之间已有别人提交、当前版本不再是该版本，**整批打回**，
  返回 `409 VERSION_CONFLICT`（store 层抛 `VersionConflictException`）——与普通校验失败
  （`422 INVALID_BATCH`）可明确区分；不会覆盖别人的改动，也不会只生效一部分。
- 不提供时：行为与之前完全一致，既有调用不受影响。

回滚同理：`rollback` 请求体加 `expectedBaseVersion` 即启用同样的冲突保护。

### 版本差异对比（diff）

`GET /api/feature-flags/diff?from=&to=` 对比两个**仍保留在历史中**的版本，
返回配置键与开关的逐键差异：`ADDED / OVERWRITTEN / UNCHANGED / REMOVED` +
两侧脱敏摘要。**只返回键名与摘要，不回显敏感原始值**。任一版本已被历史淘汰时返回
`404 VERSION_NOT_FOUND`，可与其他失败区分。

---

## 3. 灰度分流规则

判定对同一 `(flagKey, subject)` 是**确定性**的：使用 64 位 FNV-1a 哈希
（输入 `flagKey + '#' + subject`，无随机数、无进程状态），对 100 取模得到桶号 `0..99`。
因此同一主体对同一开关结果跨进程、跨重启稳定；不同开关以 key 为盐，桶位相互独立。

规则按以下**优先级，首个命中即决定**（保证可解释，结果中带 `reason` 与桶号依据）：

1. `forceDisabled=true` → 对所有人关闭；
2. `forceEnabled=true`  → 对所有人开启；
3. 主体在 `deny` 名单 → 关闭；
4. 主体在 `allow` 名单 → 开启；
5. 否则按 `bucket < percentage` 判定（`percentage` 取值 0..100）。

边界：`percentage=0` 无人通过；`percentage=100` 所有人通过；`percentage=1` 时
桶 0 选中、桶 1 不选中（阈值为左闭右开）。响应/日志中的 `explanation` 给出
`reason / flagKey / bucket / percentage` 作为判定依据。

### 审计与敏感信息

每次发布/回滚产生 `ChangeRecord`，支持按 key 查询。**只记录键、变更类型与脱敏摘要**：
config 值与 flag 结构都经 `ValueRedactor` 转为 `sha256:<12位>` 摘要（空值记 `absent`），
不可逆、不含原值；历史/版本接口也只返回键名，不返回配置原值。

---

## 4. HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/feature-flags/evaluate?key=&subject=` | 灰度判定（四态 + 判定依据） |
| GET | `/api/feature-flags/history` | 版本历史（仅键名，脱敏） |
| GET | `/api/feature-flags/changes[?key=]` | 变更记录（脱敏摘要） |
| GET | `/api/feature-flags/config` | 三层合并后的类型化配置快照 + 观测版本 |
| POST | `/api/feature-flags/precheck` | 只读预检：分类校验结果 + 逐键变更预览（脱敏摘要），不改变任何状态 |
| POST | `/api/feature-flags/publish` | 原子发布一批 flag/config（可选 `expectedBaseVersion` 认准版本） |
| POST | `/api/feature-flags/rollback` | 整批回滚到某个保留版本（可选 `expectedBaseVersion` 认准版本） |
| GET | `/api/feature-flags/diff?from=&to=` | 两个保留版本间的逐键差异（键名 + 脱敏摘要） |

发布请求示例：

```json
{
  "operator": "alice",
  "reason": "launch checkout rollout",
  "flags": [
    {"key": "checkout", "enabled": true, "percentage": 50,
     "allow": ["vip-1"], "deny": ["blocked-1"],
     "forceEnabled": false, "forceDisabled": false}
  ],
  "config": [
    {"key": "service.maxRetries", "value": "5"}
  ]
}
```

回滚请求：`{"targetVersion": 0, "operator": "bob", "reason": "undo"}`。

发布/回滚均可选带 `expectedBaseVersion`（通常取预检返回的 `baseVersion`）：
`{"operator": "bob", "expectedBaseVersion": 3, "flags": [...]}`。

错误统一返回 `{ "error": "feature_flag_error", "type": ..., "message": ... }`，按类型映射：

| type | HTTP 状态 | 触发 |
|---|---|---|
| `INVALID_BATCH` | 422 | 发布/回滚批次校验失败（整批拒绝） |
| `VERSION_CONFLICT` | 409 | 认准的基准版本已过期（整批拒绝，不覆盖他人改动） |
| `TYPE_MISMATCH` | 400 | 取值类型不符 |
| `INVALID_VALUE` | 400 | 取值非法 / 键未声明 |
| `MISSING_CONFIG` | 404 | 必填配置缺失 |
| `VERSION_NOT_FOUND` | 404 | 回滚/对比目标版本不存在或已淘汰 |
| `BAD_REQUEST` / `MALFORMED_JSON` | 400 | 参数非法 / 请求体格式错误 |

---

## 5. 兼容范围

- 运行时：Java 21、Spring Boot 3.4.x、Servlet Web（spring-boot-starter-web）。
- 存储为**进程内内存态**：发布与历史不跨进程持久化，重启回到 bootstrap 版本 + 启动配置；
  适合作嵌入式特性开关/灰度判定与单机演示。需要跨实例一致时，可在存储外层接入持久化或
  广播（本版本不包含）。
- 无破坏性的向后兼容：读取接口为只读；发布为 upsert 语义；回滚总是新增版本而非覆盖。
- 安全发布为**纯增量**：`expectedBaseVersion` 在发布/回滚请求中均可选，缺省时行为与上一版
  完全一致；`/precheck`、`/diff` 为新端点，不影响既有端点；store 层 `publish(batch)` /
  `rollbackTo(...)` 旧签名保留并委托到新重载。

---

## 6. 本地验证

无需任何外部服务或真实凭据：

```bash
# 编译
mvn -o compile

# 运行全部单测（并发发布/回滚、非法批次、分桶边界、未知开关、分层合并、端到端 HTTP）
mvn -o test

# 启动后手工验证
mvn -o spring-boot:run
curl "http://localhost:8080/api/feature-flags/evaluate?key=checkout&subject=u1"

# 预检（只读，返回分类问题与逐键预览，含 baseVersion）
curl -X POST http://localhost:8080/api/feature-flags/precheck \
  -H 'Content-Type: application/json' \
  -d '{"operator":"bob","flags":[{"key":"checkout","percentage":50}]}'

# 认准版本发布（把 precheck 返回的 baseVersion 填进来）
curl -X POST http://localhost:8080/api/feature-flags/publish \
  -H 'Content-Type: application/json' \
  -d '{"operator":"bob","expectedBaseVersion":0,"flags":[{"key":"checkout","percentage":50}]}'

# 版本差异（只返回键名与脱敏摘要）
curl "http://localhost:8080/api/feature-flags/diff?from=0&to=1"
```

测试（均在日志中打印输入与判定依据）：

- `StoreConcurrencyTest`（7）：16 线程并发发布的单调/无丢失更新、读端永不出现“半个批次”、
  非法批次整批拒绝、强制冲突、回滚整批恢复、过期版本回滚报错、审计记录不含原值；
- `SafeReleaseTest`（6）：预检逐键预览（新增/覆盖/不变）且状态零副作用、预检按
  `TYPE_MISMATCH / CONSTRAINT_VIOLATION / UNKNOWN_CONFIG_KEY / MISSING_REQUIRED_VALUE`
  分类、认准过期版本发布/回滚被 `VersionConflictException` 整批拒绝且与
  `InvalidBatchException` 可区分、重新认准后整批成功、无认准的旧调用行为不变、
  版本 diff 的增删改/脱敏断言、已淘汰版本 diff 报 `VersionNotFoundException`；
- `SafeReleaseApiTest`（3）：`/precheck` 分类与预览且版本不前进、过期 `expectedBaseVersion`
  发布返回 409 `VERSION_CONFLICT` 且未生效、重新认准后成功、`/diff` 脱敏输出与
  已淘汰版本 404 `VERSION_NOT_FOUND`；
- `RolloutEvaluationTest`（5）：分桶稳定性、`0/1/100` 与桶边界、名单与强制覆盖优先级、
  四态可区分、回滚态 vs 未知态；
- `LayeredConfigTest`（6）：三层优先级与生效时机、经 store 的运行时覆盖与回滚、
  `MISSING/TYPE_MISMATCH/INVALID_VALUE` 三类错误、启动期非法值快速失败、错误不回显原值；
- `FeatureFlagApiTest`（3）：经 MockMvc 的发布/判定/回滚/历史/变更/配置端到端、
  非法批次 422 且无部分生效、错误码区分、脱敏断言。

共计 31 个测试。
