package com.xm2nd.tlmagenttweaks.common.stream;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** 传输层共享的基础设施线程：读取池与截止计时器，进程退出时统一关闭。 */
final class TransportExecutors {

    static final ExecutorService READER = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "tlm-stream-reader");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 读 body 硬截止计时器：JDK {@code HttpRequest.timeout} 只覆盖“收到响应头”，
     * 无法中断已获得的 {@link InputStream} 阻塞读取，因此必须由独立计时器强制
     * 关闭流并让本次尝试作废。
     */
    static final ScheduledExecutorService DEADLINE = Executors.newScheduledThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "tlm-stream-deadline");
        thread.setDaemon(true);
        return thread;
    });

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            READER.shutdownNow();
            DEADLINE.shutdownNow();
        }, "tlm-stream-shutdown"));
    }

    private TransportExecutors() {
    }
}
