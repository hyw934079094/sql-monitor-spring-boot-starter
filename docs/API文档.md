# SQL Monitor Spring Boot Starter API文档

## 项目简介

SQL Monitor Spring Boot Starter 是一个企业级的SQL监控组件，基于Spring Boot 3开发，提供慢SQL监控、敏感信息过滤、MDC监控等功能，帮助企业及时发现和解决SQL执行问题，提高系统的性能和可靠性。

## 核心组件

### 1. 自动配置

#### SlowSqlAutoConfiguration
- **功能**：自动配置慢SQL监控相关组件
- **包路径**：`com.hyw.boot.autoconfigure.SlowSqlAutoConfiguration`
- **依赖条件**：
    - 存在MyBatis的Executor和MappedStatement类
    - 配置项`hyw.sql.monitor.enabled`为true
- **提供的Bean**：
    - `sqlSensitiveFilter`：SQL敏感信息过滤器
    - `sqlParser`：SQL解析器
    - `mdcTaskDecorator`：MDC任务装饰器
    - `mdcMonitor`：MDC监控器
    - `sqlMonitorExecutor`：SQL监控线程池
    - `slowSqlInterceptor`：慢SQL拦截器

### 2. 配置管理

#### SlowSqlProperties
- **功能**：管理所有配置项
- **包路径**：`com.hyw.boot.config.SlowSqlProperties`
- **配置前缀**：`hyw.sql.monitor`
- **核心配置项**：
    - `enabled`：是否启用监控
    - `slowThreshold`：慢SQL阈值(ms)
    - `criticalThreshold`：严重慢SQL阈值(ms)
    - `logEnabled`：是否记录采样日志
    - `sampleRate`：采样率
    - `maxSqlLength`：SQL最大截断长度
    - `maxCacheSize`：缓存最大数量
    - `dbTypeCacheSeconds`：数据库类型缓存时间
    - `sensitive`：脱敏配置
    - `pool`：线程池配置
    - `mdc`：MDC监控配置

### 3. 核心拦截器

#### UniversalSlowSqlInterceptor
- **功能**：拦截MyBatis执行过程，监控SQL执行时间
- **包路径**：`com.hyw.boot.interceptor.UniversalSlowSqlInterceptor`
- **拦截的方法**：
    - `update`：更新操作
    - `query`：查询操作
    - `batch`：批量操作
- **核心功能**：
    - 记录SQL执行时间
    - 识别慢SQL和严重慢SQL
    - 异步处理监控数据
    - 收集监控指标

### 4. 过滤器

#### SqlSensitiveFilter
- **功能**：过滤SQL中的敏感信息
- **包路径**：`com.hyw.boot.filter.SqlSensitiveFilter`
- **过滤的内容**：
    - 敏感字段（如密码、身份证号、手机号等）
    - 敏感表数据
    - 手机号
    - 身份证号

### 5. 监控

#### MdcMonitor
- **功能**：监控MDC上下文传递
- **包路径**：`com.hyw.boot.monitor.MdcMonitor`
- **核心功能**：
    - 记录MDC命中情况
    - 统计MDC丢失情况
    - 提供详细的MDC统计信息

#### MdcMonitorScheduler
- **功能**：定时监控MDC状态
- **包路径**：`com.hyw.boot.monitor.MdcMonitorScheduler`
- **调度频率**：每60秒执行一次

### 6. 解析器

#### JSqlParser
- **功能**：解析SQL语句
- **包路径**：`com.hyw.boot.parser.JSqlParser`
- **核心功能**：
    - 解析SQL类型（SELECT、INSERT、UPDATE、DELETE等）
    - 提取表名
    - 解析WHERE条件、排序条件、分页条件
    - 检测是否包含JOIN和子查询

### 7. 装饰器

#### EnhancedMdcTaskDecorator
- **功能**：确保异步线程的MDC传递
- **包路径**：`com.hyw.boot.decorator.EnhancedMdcTaskDecorator`
- **核心功能**：
    - 捕获当前线程的MDC上下文
    - 在异步线程中恢复MDC上下文
    - 记录队列等待时间

## 配置项详解

### 1. 基础配置

| 配置项 | 类型 | 默认值 | 说明 |
|-------|------|-------|------|
| `hyw.sql.monitor.enabled` | boolean | true | 是否启用监控 |
| `hyw.sql.monitor.slow-threshold` | long | 1000 | 慢SQL阈值(ms) |
| `hyw.sql.monitor.critical-threshold` | long | 5000 | 严重慢SQL阈值(ms) |
| `hyw.sql.monitor.log-enabled` | boolean | false | 是否记录采样日志 |
| `hyw.sql.monitor.sample-rate` | double | 0.01 | 采样率 |
| `hyw.sql.monitor.max-sql-length` | int | 2000 | SQL最大截断长度 |
| `hyw.sql.monitor.max-cache-size` | int | 1000 | 缓存最大数量 |
| `hyw.sql.monitor.db-type-cache-seconds` | int | 60 | 数据库类型缓存时间 |

### 2. 脱敏配置

| 配置项 | 类型 | 默认值 | 说明 |
|-------|------|-------|------|
| `hyw.sql.monitor.sensitive.enabled` | boolean | true | 是否启用脱敏 |
| `hyw.sql.monitor.sensitive.mask-char` | string | "*" | 脱敏字符 |
| `hyw.sql.monitor.sensitive.mask-length` | int | 4 | 脱敏长度 |
| `hyw.sql.monitor.sensitive.sensitive-fields` | list | [password, pwd, secret, token, id_card, phone, mobile, email, bank_card] | 敏感字段 |
| `hyw.sql.monitor.sensitive.sensitive-tables` | list | [user, account, payment, customer] | 敏感表 |

### 3. 线程池配置

| 配置项 | 类型 | 默认值 | 说明 |
|-------|------|-------|------|
| `hyw.sql.monitor.pool.core-size` | int | 2 | 核心线程数 |
| `hyw.sql.monitor.pool.max-size` | int | 4 | 最大线程数 |
| `hyw.sql.monitor.pool.queue-capacity` | int | 1000 | 队列容量 |
| `hyw.sql.monitor.pool.keep-alive-seconds` | int | 60 | 线程保持时间 |

### 4. MDC监控配置

| 配置项 | 类型 | 默认值 | 说明 |
|-------|------|-------|------|
| `hyw.sql.monitor.mdc.enabled` | boolean | true | 是否启用MDC监控 |
| `hyw.sql.monitor.mdc.monitor-rate` | double | 0.01 | MDC监控采样率 |
| `hyw.sql.monitor.mdc.alert-threshold` | double | 99.5 | 告警阈值 |
| `hyw.sql.monitor.mdc.mdc-keys` | list | [traceId, spanId, userId, requestId, clientIp] | 监控的MDC键 |

## 使用方法

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.hyw</groupId>
    <artifactId>sql-monitor-spring-boot-starter</artifactId>
    <version>3.0.0</version>
</dependency>
```

### 2. 配置示例

```yaml
# 慢SQL监控组件配置
hyw:
  sql:
    monitor:
      enabled: true
      slow-threshold: 800        # 慢SQL阈值(ms)
      critical-threshold: 3000   # 严重慢SQL阈值(ms)
      log-enabled: true          # 是否记录采样日志
      sample-rate: 0.05          # 采样率 5%
      max-sql-length: 3000       # SQL最大截断长度
      max-cache-size: 2000       # 缓存最大数量
      db-type-cache-seconds: 60  # 数据库类型缓存时间
      
      # 脱敏配置
      sensitive:
        enabled: true
        mask-char: "*"
        mask-length: 4
        sensitive-fields:
          - password
          - pwd
          - secret
          - token
          - id_card
          - phone
          - mobile
          - email
          - bank_card
        sensitive-tables:
          - user
          - account
          - payment
          - customer
      
      # 线程池配置
      pool:
        core-size: 4
        max-size: 8
        queue-capacity: 2000
        keep-alive-seconds: 60
      
      # MDC链路追踪配置
      mdc:
        enabled: true
        monitor-rate: 0.01        # MDC监控采样率
        alert-threshold: 99.5      # 告警阈值
        mdc-keys:
          - traceId
          - spanId
          - userId
          - requestId
          - clientIp
          - serverIp
```

### 3. 日志配置

```yaml
# 日志级别配置
logging:
  level:
    com.hyw.boot: INFO
    com.hyw.boot.monitor: INFO  # 生产环境建议 INFO
    com.baomidou.mybatisplus: INFO
    # SQL 日志级别（生产环境建议关闭）
    com.hyw.mapper: INFO
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}] %-5level %logger{36} - %msg%n"
    file: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}] %-5level %logger{36} - %msg%n"
  file:
    name: logs/sql-monitor.log
    max-size: 100MB
    max-history: 30
```

## 监控指标

### 1. Micrometer指标

| 指标名称 | 类型 | 说明 | 标签 |
|---------|------|------|------|
| `sql.execution.time` | Timer | SQL执行时间 | sqlId, sqlType, tables |
| `sql.slow.count` | Counter | 慢SQL计数 | sqlId |

### 2. 日志输出

- **慢SQL记录**：当SQL执行时间超过慢SQL阈值时，输出WARN级别日志
- **严重慢SQL告警**：当SQL执行时间超过严重慢SQL阈值时，输出ERROR级别日志
- **SQL监控统计**：每分钟输出一次SQL执行统计信息
- **MDC监控统计**：当MDC丢失达到一定次数时，输出WARN级别日志

## 常见问题

### 1. 慢SQL监控不生效

- 检查配置项`hyw.sql.monitor.enabled`是否为true
- 检查是否引入了MyBatis依赖
- 检查SQL执行时间是否超过阈值

### 2. 敏感信息未脱敏

- 检查配置项`hyw.sql.monitor.sensitive.enabled`是否为true
- 检查敏感字段和敏感表配置是否正确
- 检查SQL语句格式是否符合脱敏规则

### 3. MDC监控不生效

- 检查配置项`hyw.sql.monitor.mdc.enabled`是否为true
- 检查MDC键配置是否正确
- 检查日志配置是否包含MDC信息

### 4. 性能问题

- 调整线程池配置，增加核心线程数和最大线程数
- 调整缓存大小和过期时间
- 调整采样率，减少日志输出

## 扩展机制

### 1. 自定义SQL解析器

实现`SqlParser`接口，并重写相应方法：

```java
public class CustomSqlParser implements SqlParser {
    @Override
    public SqlParseResult parse(String sql) {
        // 自定义解析逻辑
    }
    
    // 其他方法实现
}
```

然后在配置类中注册：

```java
@Bean
public SqlParser sqlParser() {
    return new CustomSqlParser();
}
```

### 2. 自定义敏感信息过滤器

继承`SqlSensitiveFilter`类，并重写相应方法：

```java
public class CustomSqlSensitiveFilter extends SqlSensitiveFilter {
    @Override
    public String filter(String sql) {
        // 自定义过滤逻辑
    }
}
```

然后在配置类中注册：

```java
@Bean
public SqlSensitiveFilter sqlSensitiveFilter(SlowSqlProperties properties) {
    return new CustomSqlSensitiveFilter(properties);
}
```

### 3. 自定义MDC监控

继承`MdcMonitor`类，并重写相应方法：

```java
public class CustomMdcMonitor extends MdcMonitor {
    @Override
    public void recordMdcStatus() {
        // 自定义监控逻辑
    }
}
```

然后在配置类中注册：

```java
@Bean
public MdcMonitor mdcMonitor(SlowSqlProperties properties) {
    return new CustomMdcMonitor(properties);
}
```
