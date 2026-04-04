package com.hyw.boot.decorator;

import com.hyw.boot.config.SlowSqlProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EnhancedMdcTaskDecorator 单元测试
 */
class EnhancedMdcTaskDecoratorTest {

    private EnhancedMdcTaskDecorator decorator;

    @BeforeEach
    void setUp() {
        SlowSqlProperties properties = new SlowSqlProperties();
        decorator = new EnhancedMdcTaskDecorator(properties);
        MDC.clear();
    }

    @Test
    @DisplayName("MDC 上下文正确传递到子线程")
    void shouldPropagatesMdcToChildThread() throws Exception {
        MDC.put("traceId", "trace-123");
        MDC.put("userId", "user-456");

        AtomicReference<String> childTraceId = new AtomicReference<>();
        AtomicReference<String> childUserId = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Runnable decorated = decorator.decorate(() -> {
            childTraceId.set(MDC.get("traceId"));
            childUserId.set(MDC.get("userId"));
            latch.countDown();
        });

        Thread thread = new Thread(decorated);
        thread.start();
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(childTraceId.get()).isEqualTo("trace-123");
        assertThat(childUserId.get()).isEqualTo("user-456");
    }

    @Test
    @DisplayName("子线程执行后恢复原有 MDC 上下文")
    void shouldRestorePreviousMdcAfterExecution() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Map<String, String>> afterContext = new AtomicReference<>();

        // 在主线程设置 MDC 用于传递
        MDC.put("traceId", "main-trace");
        Runnable decorated = decorator.decorate(() -> {
            // 什么都不做
        });
        MDC.clear();

        // 模拟子线程有自己的 MDC
        Thread thread = new Thread(() -> {
            MDC.put("existing", "keep-me");
            decorated.run();
            afterContext.set(MDC.getCopyOfContextMap());
            latch.countDown();
        });
        thread.start();
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        // 执行后原有 MDC 应被恢复
        assertThat(afterContext.get()).containsEntry("existing", "keep-me");
    }

    @Test
    @DisplayName("主线程无 MDC 时子线程不会 NPE")
    void shouldHandleEmptyMdc() throws Exception {
        // MDC 已在 setUp 中清空
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Boolean> completed = new AtomicReference<>(false);

        Runnable decorated = decorator.decorate(() -> {
            completed.set(true);
            latch.countDown();
        });

        Thread thread = new Thread(decorated);
        thread.start();
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(completed.get()).isTrue();
    }

    @Test
    @DisplayName("异步队列等待时间被记录到 MDC")
    void shouldRecordQueueTime() throws Exception {
        AtomicReference<String> queueTime = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Runnable decorated = decorator.decorate(() -> {
            queueTime.set(MDC.get("async.queue.time"));
            latch.countDown();
        });

        // 稍微等一下再执行，产生队列等待时间
        Thread.sleep(10);
        Thread thread = new Thread(decorated);
        thread.start();
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(queueTime.get()).isNotNull();
        assertThat(Long.parseLong(queueTime.get())).isGreaterThanOrEqualTo(0);
    }
}
