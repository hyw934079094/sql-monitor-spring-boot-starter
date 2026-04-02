package com.hyw.boot.decorator;

import com.hyw.boot.config.SlowSqlProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 增强版MDC传递装饰器
 *
 * @author hyw
 * @version 3.0.0
 */
public class EnhancedMdcTaskDecorator implements TaskDecorator {

    private static final Logger log = LoggerFactory.getLogger(EnhancedMdcTaskDecorator.class);

    private final SlowSqlProperties properties;
    private final Set<String> mdcKeys;

    public EnhancedMdcTaskDecorator(SlowSqlProperties properties) {
        this.properties = properties;
        this.mdcKeys = new HashSet<>(properties.getMdc().getMdcKeys());

        log.info("MDC装饰器初始化 - 监控的MDC keys: {}, MDC监控采样率: {}, 告警阈值: {}%",
                mdcKeys,
                properties.getMdc().getMonitorRate(),
                properties.getMdc().getAlertThreshold());
    }

    @Override
    public Runnable decorate(Runnable runnable) {
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

        return new MdcAwareRunnable(runnable, context, traceId, properties);
    }

    /**
     * MDC感知的Runnable
     */
    private static class MdcAwareRunnable implements Runnable {
        private final Runnable delegate;
        private final Map<String, String> context;
        private final String traceId;
        private final long createTime;
        private final SlowSqlProperties properties;

        public MdcAwareRunnable(Runnable delegate, Map<String, String> context,
                                String traceId, SlowSqlProperties properties) {
            this.delegate = delegate;
            this.context = context;
            this.traceId = traceId;
            this.createTime = System.currentTimeMillis();
            this.properties = properties;
        }

        @Override
        public void run() {
            long queueTime = System.currentTimeMillis() - createTime;

            if (context != null && !context.isEmpty()) {
                MDC.setContextMap(new HashMap<>(context));
            }

            try {
                MDC.put("async.queue.time", String.valueOf(queueTime));

                if (log.isDebugEnabled() && traceId != null) {
                    log.debug("恢复MDC上下文 - traceId: {}, 队列等待: {}ms",
                            traceId, queueTime);
                }

                delegate.run();

            } finally {
                MDC.clear();
            }
        }
    }
}