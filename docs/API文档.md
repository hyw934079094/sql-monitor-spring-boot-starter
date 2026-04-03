# SQL Monitor Spring Boot Starter v3.0.0 — API 文档

## 一、项目概述

基于 Spring Boot 3 + MyBatis 的**企业级 SQL 监控 Starter**。引入依赖即可自动生效，零代码侵入。

**核心能力：**

- 异步慢 SQL 检测与告警（不阻塞业务线程）
- SQL 敏感信息自动脱敏
- Micrometer 指标上报（Prometheus / Grafana 就绪）
- MDC 链路上下文跨线程传递与健康监控
- 熔断降级保护（监控组件异常不影响业务）
- SlowSqlHandler SPI 扩展点（自定义持久化）

---

## 二、架构总览

```
┌─────────────────────────────────────────────────────────────────────┐
│                     消费方 Spring Boot 应用                         │
│                                                                     │
│   Controller → Service → Mapper.selectById(1)                       │
│                                    │                                │
│                          ┌─────────▼──────────┐                     │
│                          │  MyBatis Executor   │                     │
│                          └─────────┬──────────┘                     │
│  ┌─────────────────────────────────┼────────────────────────────┐   │
│  │         UniversalSlowSqlInterceptor (MyBatis Plugin)         │   │
│  │                                 │                             │   │
│  │  1. 记录 start 时间              │                             │   │
│  │  2. invocation.proceed()  ──────┤  ← 正常执行 SQL             │   │
│  │  3. 计算 cost                    │                             │   │
│  │  4. submitMetricsTask() ────────┼──→ 异步线程池               │   │
│  │     (TaskRejectedException      │    (sqlMonitorExecutor)     │   │
│  │      → 丢弃 + 计数)             │         │                   │   │
│  │  5. return result               │         │                   │   │
│  └─────────────────────────────────┼─────────┼───────────────────┘   │
│                                    │         │                       │
│                                    │    ┌────▼─────────────────┐     │
│                                    │    │ EnhancedMdcTask-     │     │
│                                    │    │ Decorator            │     │
│                                    │    │ (传递 MDC 上下文)     │     │
│                                    │    └────┬─────────────────┘     │
│                                    │         │                       │
│                              ┌─────▼─────────▼──────────────────┐   │
│                              │    processSqlMetrics()            │   │
│                              │                                   │   │
│                              │  ┌─ 熔断检查 (CircuitBreaker)    │   │
│                              │  │  ↓ 降级时仅计数，跳过解析     │   │
│                              │  │                                │   │
│                              │  ├─ MdcMonitor.recordMdcStatus() │   │
│                              │  │                                │   │
│                              │  ├─ SqlInfoExtractor              │   │
│                              │  │   ├─ DatabaseTypeDetector      │   │
│                              │  │   ├─ SqlParser (JSqlParser)    │   │
│                              │  │   └─ SqlSensitiveFilter        │   │
│                              │  │          ↓                     │   │
│                              │  │       SqlInfo                  │   │
│                              │  │                                │   │
│                              │  └─ SqlMetricsHandler             │   │
│                              │      ├─ log.warn / log.error      │   │
│                              │      ├─ Micrometer Timer/Counter  │   │
│                              │      └─ SlowSqlHandler[] (SPI)    │   │
│                              └───────────────────────────────────┘   │
│                                                                      │
│  ┌──────────────────┐    ┌───────────────────┐                       │
│  │ SqlMonitor-      │    │ MdcMonitor-       │                       │
│  │ Scheduler        │    │ Scheduler         │                       │
│  │ (每分钟统计报告) │    │ (每分钟MDC报告)   │                       │
│  └──────────────────┘    └───────────────────┘                       │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 三、类职责说明

### 3.1 自动配置层

| 类 | 包 | 职责 |
|----|-----|------|
| **SlowSqlAutoConfiguration** | `autoconfigure` | Spring Boot 自动配置入口。按条件注册所有 Bean，确保在 MyBatis 自动配置之后执行（`@AutoConfigureAfter`）。所有 Bean 均带 `@ConditionalOnMissingBean`，消费方可覆盖任意组件。 |
| **SlowSqlProperties** | `config` | 配置属性类（`@ConfigurationProperties`）。管理监控开关、阈值、采样率、线程池、脱敏规则、MDC 等全部配置。实现 `EnvironmentAware` 支持运行时刷新，内置配置校验和变更监听器。 |

### 3.2 核心拦截层

| 类 | 包 | 职责 |
|----|-----|------|
| **UniversalSlowSqlInterceptor** | `interceptor` | **核心入口**。MyBatis `Interceptor` 实现，拦截 `Executor.query()` 和 `Executor.update()`。计时后将监控任务提交到异步线程池，业务线程**零阻塞**。内置熔断器（连续 10 次失败 → 降级仅计数 → 60s 后自动恢复）和确定性采样（Murmur3 Hash）。 |

### 3.3 处理器层（interceptor.handler）

| 类 | 包 | 职责 |
|----|-----|------|
| **SqlInfoExtractor** | `handler` | 从 MyBatis `Invocation` 中提取完整 SQL 信息：获取 BoundSql → 数据库类型适配 → SQL 解析 → 脱敏 → 截断 → 封装为 `SqlInfo`。同时从 MDC 读取 traceId/userId 写入 SqlInfo。 |
| **SqlMetricsHandler** | `handler` | 处理慢 SQL 告警和 Micrometer 指标上报。构造 `SlowSqlLog` 对象，按严重级别输出日志（warn/error），回调所有 `SlowSqlHandler` SPI 实现，并向 Micrometer 注册 Timer/Counter。 |
| **SqlMonitorScheduler** | `handler` | 自管理的调度器（daemon 线程）。每分钟输出 SQL 执行统计（总数/慢SQL/严重慢SQL/丢弃数），每小时监控 Caffeine 缓存使用率并预警。 |
| **DatabaseTypeDetector** | `handler` | 数据库类型检测与缓存。优先从 MyBatis `Configuration.databaseId` 获取，回退到反射读取 DataSource 的 JDBC URL 并通过正则推断。带时间戳缓存，避免重复检测。 |

### 3.4 SQL 解析与脱敏层

| 类 | 包 | 职责 |
|----|-----|------|
| **SqlParser** (接口) | `parser` | SQL 解析器抽象。定义 `parse()`、`getSqlType()`、`extractTableNames()` 等方法，内置 `SqlParseResult` 数据类。消费方可替换实现。 |
| **JSqlParser** | `parser` | 默认实现，基于 JSqlParser 库。解析 SQL 类型、表名、WHERE 条件、ORDER BY、LIMIT、JOIN、子查询等结构信息。 |
| **SqlSensitiveFilter** | `filter` | SQL 脱敏过滤器。启动时编译正则，运行时对敏感字段值（`password='xxx'` → `password='****'`）、敏感表全量数据、手机号、身份证号进行脱敏。 |

### 3.5 MDC 监控层

| 类 | 包 | 职责 |
|----|-----|------|
| **EnhancedMdcTaskDecorator** | `decorator` | `TaskDecorator` 实现，配置在异步线程池上。在任务提交时快照业务线程的 MDC 上下文（按配置的 key 列表），在异步线程执行时恢复，执行后还原原有 MDC（不覆盖其他框架设置的值）。 |
| **MdcMonitor** | `monitor` | MDC 健康度监控器。按采样率抽检异步线程中 MDC key 的完整性，统计命中/丢失次数，提供 `getAndResetStats()` 原子快照。 |
| **MdcMonitorScheduler** | `monitor` | 自管理调度器（不依赖 `@EnableScheduling`）。每 60 秒输出 MDC 传递成功率，低于阈值时触发 ERROR 告警。 |

### 3.6 数据模型

| 类 | 包 | 职责 |
|----|-----|------|
| **SqlInfo** | `model` | SQL 详情模型。包含 sqlId、脱敏后 SQL、SQL 类型、表名、WHERE 条件、参数、JOIN/子查询标志、数据库类型、traceId、userId。 |
| **SlowSqlLog** | `model` | 慢 SQL 日志模型。在 SqlInfo 基础上增加 timestamp、threadName、cost、method、hasError、errorMessage 等运行时信息。用于日志输出和 SPI 回调。 |

### 3.7 扩展点

| 类 | 包 | 职责 |
|----|-----|------|
| **SlowSqlHandler** (接口) | `handler` | 慢 SQL 处理 SPI。消费方实现此接口并注册为 Bean，即可将慢 SQL 持久化到 DB/ES/MQ 等。支持多实现，按 `@Order` 排序执行，每个 handler 异常隔离。 |

---

## 四、一条 SQL 的完整生命周期

以下以 `UserMapper.selectById(1)` 为例，追踪一条 SQL 从执行到监控输出的完整链路：

### 阶段 1：业务线程 — SQL 拦截与计时

```
业务线程 (http-nio-8080-exec-1)
│
├─ 1. MyBatis 调用 Executor.query()
│     └─ 命中 @Intercepts 注解，进入 UniversalSlowSqlInterceptor.intercept()
│
├─ 2. 检查 properties.isEnabled() → true，继续
│
├─ 3. 记录 start = System.nanoTime()
│     获取 sqlId = "com.example.mapper.UserMapper.selectById"
│     sqlMonitorScheduler.incrementTotalCount()
│
├─ 4. invocation.proceed()  ← 正常执行 SQL，耗时 1500ms
│
├─ 5. cost = 1500ms
│     调用 submitMetricsTask(invocation, sqlId, 1500, null, "query")
│     │
│     ├─ 正常情况：asyncExecutor.submit(task)  → 任务进入异步队列
│     │   └─ EnhancedMdcTaskDecorator 自动快照当前 MDC {traceId, userId, ...}
│     │
│     └─ 队列满时：捕获 TaskRejectedException → sqlMonitorScheduler.incrementRejectedCount()
│                  （丢弃监控任务，不阻塞业务线程）
│
└─ 6. return result  ← 业务线程立即返回，不等待监控处理
```

**耗时影响**：业务线程仅增加 ~1μs（一次 nanoTime + 一次线程池 submit）。

### 阶段 2：异步线程 — MDC 恢复与熔断检查

```
异步线程 (sql-monitor-1)
│
├─ 7. EnhancedMdcTaskDecorator.MdcAwareRunnable.run()
│     ├─ 保存当前线程原有 MDC 上下文
│     ├─ 逐个 put 业务线程快照的 MDC {traceId=abc123, userId=U001}
│     └─ 设置 async.queue.time = 排队等待时间
│
├─ 8. MdcMonitor.recordMdcStatus()  (如果 mdc.enabled=true)
│     └─ 按 monitorRate 采样，检查 traceId/userId 等 key 是否存在
│
├─ 9. 熔断检查
│     ├─ degradedMode=false → 继续完整处理
│     └─ degradedMode=true  → 仅执行慢 SQL 计数，跳过解析，直接 return
│
└─ 10. 慢 SQL 计数（始终执行，不受熔断影响）
       cost=1500 > slowThreshold=1000 → sqlMonitorScheduler.incrementSlowCount()
       cost=1500 < criticalThreshold=5000 → 不计入 critical
```

### 阶段 3：异步线程 — SQL 信息提取

```
├─ 11. 判断是否需要详细信息
│      cost=1500 > slowThreshold=1000 → needDetail=true
│
├─ 12. SqlInfoExtractor.extractSqlInfo(invocation)
│      │
│      ├─ 12a. 获取 BoundSql
│      │       sql = "SELECT id, name, password FROM user WHERE id = ?"
│      │
│      ├─ 12b. DatabaseTypeDetector.getDatabaseType()
│      │       → 检查缓存 → 命中返回 "mysql"
│      │       → 未命中 → 反射读取 DataSource JDBC URL → 正则匹配 → 缓存结果
│      │
│      ├─ 12c. adaptSqlByDbType(sql, "mysql")
│      │       → MySQL 无特殊处理，原样返回
│      │
│      ├─ 12d. SqlParser.parse(sql)
│      │       → JSqlParser 解析：
│      │         sqlType = "SELECT"
│      │         tables  = ["user"]
│      │         where   = "id = ?"
│      │         hasJoin = false
│      │         hasSubQuery = false
│      │
│      ├─ 12e. SqlSensitiveFilter.filter(sql)
│      │       → 检测到敏感表 "user" → 全量脱敏
│      │       → "SELECT id, name, password FROM user WHERE id='***'"
│      │
│      ├─ 12f. 空白压缩 + 超长截断（maxSqlLength=2000）
│      │
│      └─ 12g. 封装 SqlInfo
│              sqlId     = "com.example.mapper.UserMapper.selectById"
│              sqlType   = "SELECT"
│              tables    = ["user"]
│              dbType    = "mysql"
│              traceId   = MDC.get("traceId")  → "abc123"
│              userId    = MDC.get("userId")   → "U001"
│              filteredSql = "SELECT id, name, password FROM user WHERE id='***'"
```

### 阶段 4：异步线程 — 指标处理与告警

```
├─ 13. SqlMetricsHandler.handleSqlMetrics(sqlInfo, sqlId, 1500, null, "query")
│      │
│      ├─ 13a. cost=1500 > slowThreshold=1000 → 进入慢 SQL 处理
│      │       │
│      │       ├─ 构造 SlowSqlLog 对象
│      │       │   timestamp  = 1712045000000
│      │       │   threadName = "sql-monitor-1"
│      │       │   sqlId      = "com.example.mapper.UserMapper.selectById"
│      │       │   cost       = 1500
│      │       │   traceId    = "abc123"
│      │       │   ...
│      │       │
│      │       ├─ cost=1500 < criticalThreshold=5000
│      │       │   → log.warn("慢SQL记录: {...}")
│      │       │
│      │       └─ 回调 SlowSqlHandler SPI
│      │           for (handler : slowSqlHandlers) {
│      │               handler.handle(slowSqlLog);  // 消费方持久化逻辑
│      │           }
│      │
│      ├─ 13b. Micrometer 指标上报
│      │       Timer  "sql.execution.time" {sqlId, sqlType="SELECT", tables="user", dbType="mysql"}
│      │              .record(1500ms)
│      │       Counter "sql.slow.count" {sqlId, dbType="mysql"}
│      │              .increment()
│      │
│      └─ 13c. consecutiveFailures.set(0)  ← 处理成功，重置熔断计数
│
└─ 14. finally: 恢复异步线程原有 MDC 上下文
```

### 阶段 5：后台调度 — 统计报告

```
sql-monitor-scheduler 线程（每分钟执行一次）
│
└─ 15. SqlMonitorScheduler.startStatReport()
       │
       ├─ total=1286, slow=23, critical=2, rejected=0
       │
       └─ log.info("SQL监控统计 - 总执行: 1286, 慢SQL: 23 (1.79%), 严重慢SQL: 2 (0.16%), 丢弃: 0")

mdc-monitor-scheduler 线程（每分钟执行一次）
│
└─ 16. MdcMonitorScheduler.reportMdcStats()
       │
       ├─ MdcMonitor.getAndResetStats()  ← 原子获取并重置
       │   total=13, hit=12, miss=1
       │
       ├─ log.info("MDC传递统计 - 总任务: 13, 成功: 12 (92.31%), 失败: 1 (7.69%)")
       └─ hitRate=92.31 < alertThreshold=99.5
           → log.error("MDC传递成功率低于阈值 99.5%！")
```

---

## 五、熔断降级机制

当监控组件自身出现异常（如 SQL 解析失败、序列化异常等）时，熔断器保护业务不受影响：

```
正常模式 ──连续失败10次──→ 降级模式（仅计数，跳过解析/脱敏/指标）
                              │
                          等待 60 秒
                              │
                              ▼
                          自动尝试恢复
                          ├─ 成功 → 回到正常模式
                          └─ 失败 → 继续降级
```

| 参数 | 值 | 说明 |
|------|-----|------|
| `CIRCUIT_BREAKER_THRESHOLD` | 10 | 连续失败触发熔断的次数 |
| `CIRCUIT_BREAKER_RECOVERY_MS` | 60000 | 降级后等待恢复的时间（ms） |

降级期间**慢 SQL 计数不受影响**（始终准确），仅跳过 SQL 解析、脱敏和 Micrometer 上报。

---

## 六、线程模型

```
业务线程 (http-nio-*)           异步线程池 (sql-monitor-*)
┌──────────────────┐          ┌───────────────────────────┐
│ intercept()      │          │ core=2, max=4, queue=1000 │
│   nanoTime       │  submit  │ AbortPolicy (满则丢弃)    │
│   proceed()      │ ───────→ │ TaskDecorator: MDC传递    │
│   nanoTime       │          │                           │
│   return result  │          │ processSqlMetrics()       │
└──────────────────┘          └───────────────────────────┘

调度线程 (sql-monitor-scheduler)     调度线程 (mdc-monitor-scheduler)
┌─────────────────────────┐        ┌────────────────────────┐
│ 1分钟: SQL统计报告      │        │ 1分钟: MDC传递报告     │
│ 1小时: 缓存使用率监控   │        │                        │
│ daemon=true             │        │ daemon=true            │
└─────────────────────────┘        └────────────────────────┘
```

---

## 七、配置参考

### 7.1 基础配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `hyw.sql.monitor.enabled` | boolean | true | 总开关 |
| `hyw.sql.monitor.slow-threshold` | long | 1000 | 慢 SQL 阈值（ms） |
| `hyw.sql.monitor.critical-threshold` | long | 5000 | 严重慢 SQL 阈值（ms） |
| `hyw.sql.monitor.log-enabled` | boolean | false | 是否启用采样日志 |
| `hyw.sql.monitor.sample-rate` | double | 0.01 | 采样率（0~1），基于 Murmur3 确定性采样 |
| `hyw.sql.monitor.max-sql-length` | int | 2000 | SQL 最大保留长度（超出截断） |
| `hyw.sql.monitor.max-cache-size` | int | 1000 | Caffeine 缓存最大条目数 |
| `hyw.sql.monitor.db-type-cache-seconds` | int | 60 | 数据库类型缓存时间（秒） |

### 7.2 脱敏配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `sensitive.enabled` | boolean | true | 脱敏开关 |
| `sensitive.mask-char` | String | `*` | 脱敏字符 |
| `sensitive.mask-length` | int | 4 | 保留的前缀/后缀长度 |
| `sensitive.sensitive-fields` | List | password, pwd, secret, token, id_card, idcard, phone, mobile, email, bank_card, credit_card | 敏感字段名 |
| `sensitive.sensitive-tables` | List | user, account, payment, customer | 敏感表名（命中后全量脱敏） |

### 7.3 线程池配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `pool.core-size` | int | 2 | 核心线程数 |
| `pool.max-size` | int | 4 | 最大线程数 |
| `pool.queue-capacity` | int | 1000 | 队列容量（满后丢弃任务） |
| `pool.keep-alive-seconds` | int | 60 | 空闲线程存活时间 |

### 7.4 MDC 配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `mdc.enabled` | boolean | true | MDC 监控开关 |
| `mdc.monitor-rate` | double | 0.01 | MDC 采样率 |
| `mdc.alert-threshold` | double | 99.5 | MDC 传递成功率告警阈值（%） |
| `mdc.mdc-keys` | List | traceId, spanId, userId, requestId, clientIp | 需要跨线程传递和监控的 MDC key |

### 7.5 Metrics 配置（Micrometer）

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `metrics.enabled` | boolean | true | 是否启用 Micrometer 指标上报（关闭后将跳过指标注册与上报，减少 SQL 解析触发场景） |
| `metrics.include-sql-id` | boolean | false | 是否将 `sqlId` 作为 tag（高基数风险：大型项目可能导致时间序列爆炸，默认关闭） |
| `metrics.percentile-histogram` | boolean | true | 是否使用直方图（推荐，利于 Prometheus 聚合） |
| `metrics.client-percentiles` | double[] | 0.5, 0.95, 0.99 | 客户端百分位数（仅在 `percentile-histogram=false` 时生效） |

### 7.6 最小配置（零配置启动）

```yaml
# 无需任何配置，全部使用默认值即可工作
```

### 7.7 生产推荐配置

```yaml
hyw:
  sql:
    monitor:
      slow-threshold: 500
      critical-threshold: 3000
      sample-rate: 0.05
      log-enabled: true
      metrics:
        enabled: true
        include-sql-id: false
      pool:
        core-size: 4
        max-size: 8
        queue-capacity: 2000
```

---

## 八、Micrometer 指标

| 指标名 | 类型 | 标签 | 说明 |
|--------|------|------|------|
| `sql.execution.time` | Timer | sqlType, tables, dbType（可选 sqlId） | SQL 执行耗时分布（直方图/百分位） |
| `sql.slow.count` | Counter | sqlType, dbType（可选 sqlId） | 慢 SQL 触发次数 |

Grafana 查询示例：
```promql
# P99 执行时间
histogram_quantile(0.99, rate(sql_execution_time_seconds_bucket[5m]))

# 慢 SQL 速率
rate(sql_slow_count_total[5m])
```

---

## 九、扩展点

所有核心组件均可通过 `@Bean` + `@ConditionalOnMissingBean` 覆盖。

### 9.1 SlowSqlHandler — 慢 SQL 持久化（SPI）

```java
@Component
@Order(1)
public class EsSlowSqlPersister implements SlowSqlHandler {
    @Override
    public void handle(SlowSqlLog log) {
        // 写入 Elasticsearch
        esClient.index(log);
    }
}

@Component
@Order(2)
public class DingTalkAlertHandler implements SlowSqlHandler {
    @Override
    public void handle(SlowSqlLog log) {
        if (log.getCost() > 5000) {
            dingTalkService.send("严重慢SQL: " + log.getSqlId());
        }
    }
}
```

注册为 Bean 即自动生效，支持多个实现，按 `@Order` 排序执行，异常隔离。

### 9.2 SqlParser — 自定义 SQL 解析

```java
@Bean
public SqlParser sqlParser() {
    return new CustomSqlParser(); // 替换默认的 JSqlParser
}
```

### 9.3 SqlSensitiveFilter — 自定义脱敏

```java
@Bean
public SqlSensitiveFilter sqlSensitiveFilter(SlowSqlProperties props) {
    return new CustomSqlSensitiveFilter(props);
}
```

### 9.4 MdcMonitor — 自定义 MDC 监控

```java
@Bean
public MdcMonitor mdcMonitor(SlowSqlProperties props) {
    return new CustomMdcMonitor(props);
}
```

### 9.5 TaskDecorator — 自定义线程上下文传递

如需覆盖本 Starter 的 MDC 传递装饰器，请提供同名 Bean：`sqlMonitorMdcTaskDecorator`。

```java
@Bean(name = "sqlMonitorMdcTaskDecorator")
public TaskDecorator sqlMonitorMdcTaskDecorator(SlowSqlProperties props) {
    return new CustomTaskDecorator(props);
}
```

---

## 十、快速开始

### Step 1：添加依赖

```xml
<dependency>
    <groupId>com.hyw</groupId>
    <artifactId>sql-monitor-spring-boot-starter</artifactId>
    <version>3.0.0</version>
</dependency>
```

### Step 2：启动应用

无需任何配置，慢 SQL 监控自动生效。

### Step 3：观察日志输出

```
INFO  慢SQL监控初始化完成 - 线程池: core=2, max=4, queue=1000, MDC监控=true
INFO  SQL监控统计 - 总执行: 1286, 慢SQL: 23 (1.79%), 严重慢SQL: 2 (0.16%), 丢弃: 0
WARN  慢SQL记录: {"sqlId":"...selectById","cost":1523,"tables":["user"],...}
ERROR 严重慢SQL告警: {"sqlId":"...complexQuery","cost":8200,"tables":["order","item"],...}
```

### Step 4（可选）：Prometheus 集成

```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus
```

访问 `/actuator/prometheus`，搜索 `sql_execution_time` 和 `sql_slow_count`。

---

## 十一、日志输出参考

### 慢 SQL 日志格式（JSON）

```json
{
  "timestamp": 1712045000000,
  "threadName": "sql-monitor-1",
  "sqlId": "com.example.mapper.UserMapper.selectById",
  "cost": 1523,
  "method": "query",
  "sqlType": "SELECT",
  "tables": ["user"],
  "sql": "SELECT id, name, password FROM user WHERE id='***'",
  "params": "{\"id\": 1}",
  "hasJoin": false,
  "hasSubQuery": false,
  "whereCondition": "id = ?",
  "hasError": false,
  "errorMessage": null,
  "traceId": "abc123",
  "userId": "U001",
  "dbType": "mysql"
}
```

### 统计报告日志

```
SQL监控统计 - 总执行: 1286, 慢SQL: 23 (1.79%), 严重慢SQL: 2 (0.16%), 丢弃: 0
MDC传递统计 - 总任务: 13, 成功: 12 (92.31%), 失败: 1 (7.69%)
```
