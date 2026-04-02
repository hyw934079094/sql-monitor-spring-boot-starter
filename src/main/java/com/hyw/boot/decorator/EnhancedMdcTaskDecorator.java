package com.hyw.boot.decorator;

import com.hyw.boot.config.SlowSqlProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 增强版MDC传递装饰器
 *
 * @author hyw
 * @version 3.0.0
 */
public class EnhancedMdcTaskDecorator implements TaskDecorator {

    private static final Logger log = LoggerFactory.getLogger(EnhancedMdcTaskDecorator.class);

    private final SlowSqlProperties properties;

    public EnhancedMdcTaskDecorator(SlowSqlProperties properties) {
        this.properties = properties;

        log.debug("MDC装饰器初始化 - 监控的MDC keys: {}, MDC监控采样率: {}, 告警阈值: {}%",
                properties.getMdc().getMdcKeys(),
                properties.getMdc().getMonitorRate(),
                properties.getMdc().getAlertThreshold());
    }

    @Override
    public Runnable decorate(Runnable runnable) {
        // 动态获取配置的MDC keys（支持运行时刷新）
        List<String> mdcKeys = properties.getMdc().getMdcKeys();

        // 捕获当前线程的MDC上下文
        Map<String, String> context = new HashMap<>();
        for (String key : mdcKeys) {
            String value = MDC.get(key);
            if (value != null) {
                context.put(key, value);
            }
        }

        String traceId = context.get("traceId");

        if (log.isDebugEnabled()) {
            log.debug("捕获MDC上下文 - keys: {}, traceId: {}", context.keySet(), traceId);
        }

        return new MdcAwareRunnable(runnable, context, traceId);
    }

    /**
     * MDC感知的Runnable
     */
    private static class MdcAwareRunnable implements Runnable {
        private final Runnable delegate;
        private final Map<String, String> context;
        private final String traceId;
        private final long createTime;

        public MdcAwareRunnable(Runnable delegate, Map<String, String> context,
                                String traceId) {
            this.delegate = delegate;
            this.context = context;
            this.traceId = traceId;
            this.createTime = System.currentTimeMillis();
        }

        @Override
        public void run() {
            long queueTime = System.currentTimeMillis() - createTime;

            // 保存异步线程原有的MDC上下文
            Map<String, String> previousContext = MDC.getCopyOfContextMap();

            try {
                // 逐个设置，不覆盖其他框架/装饰器设置的MDC值
                if (context != null) {
                    for (Map.Entry<String, String> entry : context.entrySet()) {
                        MDC.put(entry.getKey(), entry.getValue());
                    }
                }
                MDC.put("async.queue.time", String.valueOf(queueTime));

                if (log.isDebugEnabled() && traceId != null) {
                    log.debug("恢复MDC上下文 - traceId: {}, 队列等待: {}ms",
                            traceId, queueTime);
                }

                delegate.run();

            } finally {
                // 恢复异步线程原有的MDC上下文，而非直接清除
                if (previousContext != null) {
                    MDC.setContextMap(previousContext);
                } else {
                    MDC.clear();
                }
            }
        }
    }
}
