# SQL Monitor Spring Boot Starter

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2+-green)](https://spring.io/projects/spring-boot)
[![JDK](https://img.shields.io/badge/JDK-17+-blue)](https://openjdk.org/)
[![MyBatis](https://img.shields.io/badge/MyBatis--Plus-3.5+-orange)](https://baomidou.com/)

**零侵入、异步非阻塞**的企业级 SQL 监控 Starter。引入依赖即自动生效，无需改动业务代码。

---

## 特性一览

| 能力 | 说明 |
|------|------|
| **慢 SQL 检测** | 异步计时 + 分级告警（WARN / ERROR），业务线程零阻塞 |
| **确定性采样** | 基于 Murmur3 Hash，同一 SQL 采样结果恒定，不会时记时不记 |
| **SQL 脱敏** | 敏感字段、敏感表、手机号、身份证自动脱敏，正则预编译 |
| **Micrometer 指标** | Timer + Counter，Prometheus / Grafana 就绪（默认关闭，按需开启；默认防高基数） |
| **MDC 链路追踪** | 跨线程传递 traceId 等上下文，附带传递成功率监控 |
| **熔断降级** | 监控组件连续异常时自动降级（仅计数），60s 后自动恢复 |
| **SPI 扩展** | 实现 `SlowSqlHandler` 接口即可将慢 SQL 持久化到 DB/ES/MQ |
| **配置热更新** | 采样率、阈值等核心参数变更即时生效，无需重启 |

---

## 兼容矩阵

| 依赖 | 要求 | 说明 |
|------|------|------|
| JDK | 17+ | 必须 |
| Spring Boot | 3.0+ | 必须 |
| MyBatis / MyBatis-Plus | 3.5+ | 必须（业务方自行引入，Starter 不传递版本） |
| Micrometer | — | 可选，通常已通过 `spring-boot-starter-actuator` 引入 |
| 数据库 | MySQL / Oracle / PostgreSQL / SQL Server / 达梦 | 自动检测 |

---

## 快速接入（3 步）

### Step 1：加依赖

```xml
<dependency>
    <groupId>com.hyw</groupId>
    <artifactId>sql-monitor-spring-boot-starter</artifactId>
    <version>3.0.0</version>
</dependency>
```

> **注意**：业务工程需自行引入 MyBatis / MyBatis-Plus 依赖（Starter 不强制传递版本，避免冲突）。

### Step 2：启动应用

**无需任何配置**，默认即可工作（开箱即用的“最小化能力”）：

- 仅基于耗时判断慢 SQL（默认阈值 1000ms / 5000ms）
- 发现慢 SQL 时输出 `WARN/ERROR` 日志，便于你直接通过日志定位并优化 SQL

默认值：

- 慢 SQL 阈值 1000ms
- 严重慢 SQL 阈值 5000ms
- 线程池 core=2, max=4, queue=1000（监控任务异步执行，不阻塞业务线程）
- `metrics.enabled=false`（默认不采集/不上报指标）

```
INFO  慢SQL监控初始化完成 - 线程池: core=2, max=4, queue=1000, MDC监控=true
```

### Step 3：观察日志

```
WARN  慢SQL记录: {"sqlId":"com.example.mapper.UserMapper.selectById","cost":1523,"tables":["user"],...}
ERROR 严重慢SQL告警: {"sqlId":"com.example.mapper.OrderMapper.complexQuery","cost":8200,...}
INFO  SQL监控统计 - 总执行: 1286, 慢SQL: 23 (1.79%), 严重慢SQL: 2 (0.16%), 丢弃: 0
```

---

## 可选增强能力（按需开启）

你可以从“仅日志发现慢 SQL”逐步升级到更完整的可观测体系：

- **Micrometer 指标采集/上报**
  - 开关：`hyw.sql.monitor.metrics.enabled=true`
  - 说明：默认关闭；仅在你确实需要指标（如 Prometheus/Grafana）时开启

- **Prometheus + Grafana 观测**
  - 前提：开启 `metrics.enabled=true` + 接入 Prometheus Registry/抓取链路
  - 看板：`grafana/sql-monitor-dashboard.json`

- **Actuator 健康检查**
  - 前提：业务方引入 `spring-boot-starter-actuator`
  - 端点：`/actuator/health`

- **MDC 传递监控（可选）**
  - 开关：`hyw.sql.monitor.mdc.enabled=false` 可关闭
  - 说明：如果你暂时不关注 MDC 传递成功率，可关闭以减少额外监控开销

---

## 生产推荐配置

```yaml
hyw:
  sql:
    monitor:
      slow-threshold: 500          # 降低阈值，更早发现问题
      critical-threshold: 3000
      sample-rate: 0.05            # 采样率提升到 5%
      log-enabled: true

      metrics:
        enabled: true            # 默认关闭，生产环境按需开启
        include-sql-id: false      # ⚠️ 保持 false，防止 Prometheus 时间序列爆炸
        percentile-histogram: true  # 推荐，利于服务端聚合

      pool:
        core-size: 4
        max-size: 8
        queue-capacity: 2000

      sensitive:
        enabled: true
        sensitive-fields:
          - password
          - secret
          - token
          - phone
          - id_card
          - bank_card
        sensitive-tables:
          - user
          - account
          - payment

      mdc:
        enabled: true
        alert-threshold: 99.0
        mdc-keys:
          - traceId
          - spanId
          - userId
```

---

## Prometheus + Grafana 接入

> 本 Starter **默认不启用 Micrometer 指标上报**（`hyw.sql.monitor.metrics.enabled=false`）。
> 如需接入 Prometheus/Grafana，请先在配置中显式开启：

```yaml
hyw:
  sql:
    monitor:
      metrics:
        enabled: true
```

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: prometheus
```

### Grafana 仪表盘

仓库已提供可直接导入的 Grafana Dashboard：

- **路径**：`grafana/sql-monitor-dashboard.json`

导入方式：Grafana -> Dashboards -> New -> Import -> 上传该 JSON 文件。

```promql
# P99 SQL 执行时间
histogram_quantile(0.99, rate(sql_execution_time_seconds_bucket[5m]))

# 慢 SQL 速率
rate(sql_slow_count_total[5m])
```

默认指标标签：`sqlType`, `tables`, `dbType`（不含 `sqlId`，防高基数）。

---

## 扩展点

### 1. 慢 SQL 持久化（SlowSqlHandler SPI）

```java
@Component
@Order(1)
public class EsSlowSqlHandler implements SlowSqlHandler {
    @Override
    public void handle(SlowSqlLog log) {
        // 写入 Elasticsearch / 数据库 / MQ
        esClient.index(log);
    }
}
```

注册为 Bean 即生效，支持多个实现，按 `@Order` 排序，异常隔离。

### 2. 替换 SQL 解析器

```java
@Bean
public SqlParser sqlParser() {
    return new CustomSqlParser();  // 替换默认的 JSqlParser
}
```

### 3. 自定义 MDC 传递装饰器

```java
@Bean(name = "sqlMonitorMdcTaskDecorator")
public TaskDecorator sqlMonitorMdcTaskDecorator(SlowSqlProperties props) {
    return new CustomTaskDecorator(props);
}
```

> Bean 名称必须为 `sqlMonitorMdcTaskDecorator`，Starter 通过 `@Qualifier` 精确注入。

---

## 常见坑与 FAQ

### Q1：Prometheus 内存/磁盘暴涨（时间序列爆炸）

**原因**：开启了 `metrics.include-sql-id=true`，大型项目有数千个不同的 sqlId，每个都产生独立时间序列。

**解决**：保持默认值 `false`。如确需按 sqlId 粒度监控，建议配合 Prometheus 的 `metric_relabel_configs` 做聚合。

```yaml
hyw:
  sql:
    monitor:
      metrics:
        include-sql-id: false  # 默认值，保持即可
```

---

### Q2：日志出现 "SQL监控任务被丢弃 N 次"

**原因**：异步线程池队列已满（高并发 + 线程池偏小）。丢弃的是**监控任务**，不影响业务 SQL 执行。

**解决**：增大线程池或队列容量。

```yaml
hyw:
  sql:
    monitor:
      pool:
        core-size: 4       # 默认 2
        max-size: 8         # 默认 4
        queue-capacity: 5000 # 默认 1000
```

---

### Q3：日志出现 "SQL监控连续失败 10 次，触发熔断降级"

**原因**：SQL 解析/脱敏/指标上报连续异常（通常是某些非标 SQL 导致 JSqlParser 报错）。

**影响**：
- 熔断期间**慢 SQL 计数不受影响**（始终准确）
- 仅跳过 SQL 解析、脱敏和 Micrometer 上报
- 60 秒后自动尝试恢复

**如果频繁触发**：检查日志中 `处理SQL指标失败` 的堆栈，通常是某条特殊 SQL 导致解析失败。可以通过自定义 `SqlParser` 替换默认解析器来兼容。

---

### Q4：MyBatis-Plus 版本冲突 / ClassNotFoundException

**原因**：本 Starter 的 `mybatis-plus-spring-boot3-starter` 为 `provided` scope，不传递到业务工程。

**解决**：确保业务工程自行引入 MyBatis-Plus 依赖：

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    <version>3.5.9</version>  <!-- 自行管理版本 -->
</dependency>
```

---

### Q5：MDC 传递成功率告警 "低于阈值 99.5%"

**原因**：业务线程提交异步任务时 MDC 中缺少配置的 key（如 `traceId`）。常见于：
- 非 HTTP 请求触发的 SQL（定时任务、MQ 消费者）
- 框架未设置 traceId 的场景

**解决**：
- 调低告警阈值：`mdc.alert-threshold: 95.0`
- 或在定时任务/MQ 入口手动设置 MDC

---

### Q6：想完全关闭监控（临时排查）

```yaml
hyw:
  sql:
    monitor:
      enabled: false  # 拦截器直接 bypass，零开销
```

---

## 配置速查表

所有配置项前缀：`hyw.sql.monitor`

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `enabled` | boolean | `true` | 总开关 |
| `slow-threshold` | long | `1000` | 慢 SQL 阈值（ms） |
| `critical-threshold` | long | `5000` | 严重慢 SQL 阈值（ms） |
| `log-enabled` | boolean | `false` | 采样日志开关 |
| `sample-rate` | double | `0.01` | 采样率（0~1） |
| `max-sql-length` | int | `2000` | SQL 最大保留长度 |
| `max-cache-size` | int | `1000` | Caffeine 缓存上限 |
| `db-type-cache-seconds` | int | `60` | 数据库类型缓存时间（秒） |
| **metrics.** | | | |
| `metrics.enabled` | boolean | `false` | Micrometer 指标开关（默认关闭，按需开启） |
| `metrics.include-sql-id` | boolean | `false` | sqlId 作为 tag（⚠️ 高基数风险） |
| `metrics.percentile-histogram` | boolean | `true` | 使用直方图（推荐） |
| `metrics.client-percentiles` | double[] | `0.5,0.95,0.99` | 客户端百分位数 |
| **sensitive.** | | | |
| `sensitive.enabled` | boolean | `true` | 脱敏开关 |
| `sensitive.mask-char` | String | `*` | 脱敏字符 |
| `sensitive.mask-length` | int | `4` | 保留前后缀长度 |
| `sensitive.sensitive-fields` | List | `password,pwd,secret,...` | 敏感字段 |
| `sensitive.sensitive-tables` | List | `user,account,payment,...` | 敏感表 |
| **pool.** | | | |
| `pool.core-size` | int | `2` | 核心线程数 |
| `pool.max-size` | int | `4` | 最大线程数 |
| `pool.queue-capacity` | int | `1000` | 队列容量 |
| `pool.keep-alive-seconds` | int | `60` | 空闲线程存活时间 |
| **mdc.** | | | |
| `mdc.enabled` | boolean | `true` | MDC 监控开关 |
| `mdc.monitor-rate` | double | `0.01` | MDC 采样率 |
| `mdc.alert-threshold` | double | `99.5` | 成功率告警阈值（%） |
| `mdc.mdc-keys` | List | `traceId,spanId,userId,...` | 跨线程传递的 MDC key |

---

## 详细文档

- [API 文档](docs/API文档.md) — 架构总览、类职责、SQL 生命周期、线程模型
- [部署文档](docs/部署文档.md) — 环境要求、容器化、Kubernetes、Prometheus 告警规则

---

## Actuator 健康检查（可选）

如业务方引入 `spring-boot-starter-actuator`，本 Starter 会自动注册 `SqlMonitorHealthIndicator`，
在 `/actuator/health` 中暴露 SQL Monitor 健康状态（熔断状态、连续失败次数、线程池状态等）。

## License

内部使用
