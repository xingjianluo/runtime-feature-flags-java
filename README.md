# Runtime Feature Flags & Config Delivery (Java / Spring Boot)

一套可回滚的**运行时特性开关与配置下发**能力。配置合并、整批发布、灰度判定与回滚在并发访问下都具有**可解释且一致**的行为：缺失、类型不符、取值非法不会被静默采用；任何读取只能看到完整一致的版本；同一主体的灰度结果稳定可复现。

- Java 21 · Spring Boot 3.4.x · 无外部中间件依赖（内存版本存储，无需数据库 / Redis / 真实凭据）

---

## 1. 配置优先级与生效时机

配置按三层合并，**高优先级的非空值整体覆盖低优先级**：

| 优先级 | 层 | 来源 | 生效时机 |
|---|---|---|---|
| 0（最低） | 默认值 `DEFAULT` | 注册表 `ConfigDefinition.defaultValue` | 进程启动时固定，只读 |
| 1 | 启动配置 `STARTUP` | Spring `Environment` 中前缀 `feature.config.*` 的属性（application.properties / 环境变量 / JVM 参数，遵循 Spring 标准属性源顺序） | 进程启动时快照一次，只读 |
| 2（最高） | 运行时覆盖 `RUNTIME` | 已**发布成功**的版本快照中的配置 | 发布成功提交的同一瞬间对后续读取生效；回滚同理 |

逻辑键名映射：`feature.config.page-size=15` → 配置键 `page-size`。

**绝不静默采用**，三种异常互不混淆（错误类型可区分）：

| 情况 | 异常 | HTTP | errorCode |
|---|---|---|---|
| 键未在注册表声明 | `UnknownConfigKeyException` | 404 | `UNKNOWN_KEY` |
| 三层均无值 | `MissingConfigException` | 409 | `MISSING` |
| 值无法解析为声明类型 | `TypeMismatchConfigException` | 422 | `TYPE_MISMATCH` |
| 类型正确但越界/非法 | `InvalidValueConfigException` | 422 | `INVALID_VALUE` |
| 发布批次中一条或多条非法（聚合） | `BatchValidationException` | 422 | `BATCH_INVALID`（含 `details[]`） |

- 启动配置在 Bean 构造期即校验：未知键 / 类型不符 / 越界会直接导致应用**启动失败（快速失败）**。
- 支持的声明类型：`STRING / INTEGER / LONG / BOOLEAN / DOUBLE`；`INTEGER/LONG` 可声明 `minValue/maxValue`。

读取示例：`GET /api/configs/page-size` 返回 `value`、命中层 `layer` 与层序号 `layerPrecedence`，解释「这个值从哪来」。

---

## 2. 发布与回滚语义

### 整批原子发布

- `configs` 与 `flags` 在**同一个新版本**中同时生效。
- 提交流程为「**先整批校验 → 全部通过后构建不可变快照 → 最后替换 volatile 引用**」。
- 任一条目失败，所有错误聚合成 `BatchValidationException`（含每一条独立错误），**当前版本保持不变，不存在部分更新**。
- 未在批次中出现的键，表示该批次的完整内容中不包含它（随版本回退/移除语义，可通过回滚找回）。

### 版本快照与并发一致性

- `ConfigVersion` 是不可变记录（集合全部防御性拷贝并冻结）。
- 写操作（发布 / 回滚）在同一把锁内串行；读操作只读取一个 `volatile` 的不可变快照，**读不阻塞写、写不阻塞读**。
- 配置层与开关层读取**同一个版本快照**：发布后两者在同一瞬间可见，不存在「配置已更新、开关尚未更新」的中间窗口。
- 需要在一次观测中同时读配置与开关时，先取 `featureFlagService.snapshot()`，再使用 `evaluateOn(version, …)` / `resolveOn(version, …)`，保证严格不跨版本。

### 回滚

- `POST /api/feature-flags/rollback` 目标版本必须仍在历史保留窗口内（默认保留 20 个版本，可用 `feature.history-limit` 调整，含当前版本与初始空版本 0）。
- 回滚**不删除历史**，而是用目标版本的完整内容生成一个**新版本号**（审计可追溯），配置与开关随之原子恢复。
- 目标版本不存在或已超出窗口 → `InvalidValueConfigException`（422）。

---

## 3. 灰度规则

对 `(flagKey, subjectId)` 做确定性判定，优先级从高到低：

1. **强制覆盖**：`forceOn` → ON；`forceOff` → OFF（同一主体不得同时出现在两者中，发布期即拒绝）。
2. **黑名单** `denyList` → OFF。
3. **白名单** `allowList` → ON（同一主体不得同时在 allow/deny 中）。
4. **百分比**：`bucket = SHA-256(flagKey + 0x1F + subjectId)` 取前 8 字节为无符号 long，`mod 100` 得 `[0,99]` 桶位；`bucket < percent` 命中。
   - `percent=0` 全量 OFF，`percent=100` 全量 ON；边界 `bucket == percent` 不命中。

四态结果（`FlagEvaluation`：`status / reason / bucket / version`）：

| status | 含义 |
|---|---|
| `ON` | 当前版本存在该开关且命中 |
| `OFF` | 当前版本存在该开关但未命中 |
| `UNKNOWN` | 该开关键**从未发布过**（bucket=-1） |
| `ROLLED_BACK` | 曾经发布过，但在当前生效版本中已被移除/随回滚消失（bucket=-1） |

`reason` 完整记录判定依据（命中哪张名单或 `bucket<percent`），服务层日志会打印**输入与判定依据**；判定只依赖 `(flagKey, subjectId, 版本)`，与线程、时间无关，同一主体在同一版本上结果永不漂移。

### 隐私：变更记录不泄露敏感原值

- 配置定义可标记 `sensitive=true`。变更记录中的敏感值显示为 `***（sha256:<前8位>）`（可比对是否变化，但无法还原原值）。
- 开关名单只记录摘要（如 `percent=10,allow=1,deny=0,…`），**不记录具体主体标识**。
- 查询：`GET /api/feature-flags/history?key=<配置键或开关键>`。

---

## 4. HTTP API

| 方法与路径 | 说明 |
|---|---|
| `POST /api/feature-flags/publish` | 整批发布，body：`{configs:{}, flags:{}, publishedBy, note}` |
| `POST /api/feature-flags/rollback` | 回滚，body：`{targetVersion, publishedBy}` |
| `GET /api/feature-flags/evaluate?flag=&subject=` | 灰度判定（四态 + reason + bucket + version） |
| `GET /api/configs/{key}` | 读取分层合并后的配置及命中层 |
| `GET /api/feature-flags/history?key=` | 变更记录（敏感值已脱敏） |
| `GET /api/feature-flags/version` | 当前生效版本号 |

发布 body 中开关规则形态：
```json
{
  "percent": 50,
  "allowList": ["user-a"],
  "denyList": [],
  "forceOn": ["vip-1"],
  "forceOff": []
}
```

curl 快速体验：
```bash
curl -s -X POST localhost:8080/api/feature-flags/publish \
  -H 'Content-Type: application/json' \
  -d '{"configs":{"page-size":"42"},"flags":{"checkout-new":{"percent":100,"allowList":[],"denyList":[],"forceOn":[],"forceOff":[]}},"publishedBy":"alice","note":"demo"}'
curl -s 'localhost:8080/api/feature-flags/evaluate?flag=checkout-new&subject=user-1'
curl -s -X POST localhost:8080/api/feature-flags/rollback \
  -H 'Content-Type: application/json' -d '{"targetVersion":0,"publishedBy":"alice"}'
```

---

## 5. 兼容范围

- **Java**：21（records、switch 模式匹配、`Long.remainderUnsigned`）。
- **Spring Boot**：3.4.x（Spring Framework 6 / Jakarta）。
- 内置内存版本存储，面向**单实例 / 本地验证 / 嵌入式**场景；历史为进程内有限窗口（默认 20），重启后运行时发布内容回到「默认值 + 启动配置」。多实例强一致持久化不在本实现范围内，但快照与发布接口可替换为带持久化的 `VersionStore` 实现而不影响上层语义。
- 哈希算法固定为 SHA-256，分桶结果跨进程、跨 JVM 实现稳定。

---

## 6. 本地验证

无需任何外部服务或真实凭据：

```bash
# 编译
mvn compile

# 运行全部单测与集成测试（26 个），surefire 日志中含输入与判定依据
mvn test

# 启动后用上面的 curl 体验
mvn spring-boot:run
```

测试覆盖（均为纯本地）：

- `BucketHasherTest`：桶位稳定性、跨线程一致、`[0,99]` 范围与大样本覆盖（分桶边界）。
- `LayeredConfigServiceTest`：三层优先级、缺失 / 未注册 / 类型不符 / 越界四类可区分错误。
- `RolloutEvaluatorTest`：强制覆盖、名单、`percent=0/100/50` 边界、未知态、结果稳定。
- `VersionStoreTest`：原子发布、非法批次整批拒绝无部分生效、回滚、有限历史裁剪、敏感值与名单脱敏。
- `FeatureFlagServiceTest`：`UNKNOWN` 与 `ROLLED_BACK` 区分、回滚后重发、配置层随版本切换。
- `ConcurrencyConsistencyTest`：8 写线程并发发布/回滚 + 读线程约 2 万次基于同一快照的观测，断言无跨版本撕裂、版本号严格连续；非法批次并发压力下断言无部分生效、同主体结果不漂移。
- `FeatureFlagWebIntegrationTest`：MockMvc 端到端生命周期与 HTTP 错误码。

## 7. 关键代码位置

- 分层合并与错误类型：`service/LayeredConfigService.java`、`error/*`
- 版本存储 / 原子发布 / 回滚 / 审计：`service/VersionStore.java`
- 确定性分桶与灰度判定：`service/BucketHasher.java`、`service/RolloutEvaluator.java`
- 门面（四态区分、同快照读取、日志）：`service/FeatureFlagService.java`
- 声明与不可变模型：`model/*`；装配：`config/FeatureFlagConfiguration.java`
