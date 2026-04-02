package com.hyw.boot.handler;

import com.hyw.boot.model.SlowSqlLog;

/**
 * 慢SQL处理扩展点
 *
 * <p>消费方实现此接口并注册为 Spring Bean，即可将慢SQL日志持久化到 DB/ES/MQ 等外部存储。
 * 可注册多个实现，按 {@link org.springframework.core.annotation.Order} 排序依次执行。</p>
 *
 * <p>默认提供日志输出实现（{@link com.hyw.boot.interceptor.handler.SqlMetricsHandler} 内部处理），
 * 消费方无需额外配置即可使用。</p>
 *
 * @author hyw
 * @version 3.0.0
 */
public interface SlowSqlHandler {

    /**
     * 处理慢SQL日志
     *
     * <p>注意：此方法在异步线程中执行，实现方应保证线程安全。
     * 如果实现中涉及远程调用（如写入 DB/MQ），建议自行处理超时和异常，
     * 避免影响其他 handler 的执行。</p>
     *
     * @param slowSqlLog 慢SQL日志详情
     */
    void handle(SlowSqlLog slowSqlLog);
}
