package org.mydotey.ai.artemis.client.internal;

import java.util.concurrent.ThreadFactory;

/** 命名 daemon 线程工厂（SDK 后台调度线程统一：daemon 不阻 JVM 退出，命名可排障认领）。 */
final class NamedDaemonThreadFactory implements ThreadFactory {

    private final String prefix;
    private int count;

    NamedDaemonThreadFactory(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, prefix + "-" + (++count));
        thread.setDaemon(true);
        return thread;
    }
}
