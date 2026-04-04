# Changelog

本文件记录 sql-monitor-spring-boot-starter 各版本的变更内容。

## [3.0.1] - 2026-04-04

### 安全修复
- **P0** 修复 `SqlSensitiveFilter` debug 日志泄露脱敏前原始 SQL 的问题，改为仅记录 SQL 长度
- 新增参数级脱敏：`SqlInfoExtractor` 中 Map 参数敏感字段值替换为 `****`
- 新增 ReDoS 防护：`SqlSensitiveFilter` 对超长 SQL（>20000 字符）跳过正则脱敏

### 新功能
- **Actuator 健康检查**：新增 `SqlMonitorHealthIndicator`，通过 `/actuator/health` 暴露熔断器状态、线程池利用率等
- **配置元数据**：新增 `additional-spring-configuration-metadata.json`，IDE 中提供属性描述、默认值提示
- **SQL 解析长度可配置**：新增 `hyw.sql.monitor.sql-parse-max-length` 配置项（默认 10000，范围 1000~100000）
- **Grafana Dashboard**：新增 `grafana/sql-monitor-dashboard.json` 仪表盘模板（Prometheus 数据源）

### Bug 修复
- 修复 `UniversalSlowSqlInterceptor.consecutiveFailures` 的 check-then-act 竞态条件，改为无条件 `set(0)`
- 修复 `DatabaseTypeDetector.refreshDbTypeCache()` 缺少 double-check 导致多线程重复查询数据库类型
- 修复 `SqlSensitiveFilter.initPattern()` 依赖 `@PostConstruct` 生命周期，脱离 Spring 容器时正则未初始化

### 设计改进
- `DatabaseTypeDetector` 和 `SqlInfoExtractor` 构造器改为接收 `SlowSqlProperties` 引用，支持配置热更新自动生效
- 移除 `SlowSqlAutoConfiguration` 中的 `allowCoreThreadTimeOut(true)`，监控场景保留核心线程避免频繁创建/销毁
- `SqlMetricsHandler` 补充注释说明 Caffeine 缓存驱逐与 MeterRegistry 幂等性的安全关系

### 构建与工程
- 集成 JaCoCo 覆盖率门禁（行覆盖率 ≥ 50%）
- JaCoCo 升级到 `0.8.13`（兼容更高版本 JDK 的字节码）
- 集成 SpotBugs 静态分析（verify 阶段运行，`failOnError=false`）
- 新增 `maven-source-plugin` 和 `maven-javadoc-plugin`
- 补全 pom.xml 发布元数据（license、scm、developers、url）

### 测试
- 新增 `UniversalSlowSqlInterceptorTest`（9 个测试用例）
- 新增 `SqlInfoExtractorTest`（10 个测试用例）
- 新增 `SqlMonitorIntegrationTest`（H2 + MyBatis 端到端集成测试）
- 新增 `ConcurrentStressTest`（并发压力测试）
- 更新 `DatabaseTypeDetectorTest` 适配新构造器
- 新增 `SlowSqlPropertiesTest.sqlParseMaxLength` 验证测试

### 行为变更
- Micrometer 指标上报默认关闭：`hyw.sql.monitor.metrics.enabled` 默认值由 `true` 调整为 `false`（按需开启）

## [3.0.0] - 初始版本

- 基于 MyBatis Interceptor 的非侵入式慢 SQL 监控
- 确定性采样（Murmur3 Hash）
- SQL 脱敏（敏感字段/敏感表/手机号/身份证）
- Micrometer 指标（Timer/Counter）
- MDC 上下文传播与监控
- 熔断降级
- SPI 扩展点（SlowSqlHandler）
- 配置热更新
