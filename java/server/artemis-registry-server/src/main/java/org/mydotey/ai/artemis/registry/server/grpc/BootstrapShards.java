package org.mydotey.ai.artemis.registry.server.grpc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import org.mydotey.ai.artemis.proto.common.v1.ServiceInstances;

/**
 * 全量快照分片（复制 peer bootstrap 与投影 bootstrap 共用）：按序列化字节预算切分并逐片发送。
 * 单一配置来源 {@link #KEY_SHARD_BYTES}（同一注册表全量导出、同一消息尺寸约束）。
 */
final class BootstrapShards {

    static final String KEY_SHARD_BYTES = "artemis.registry.bootstrap.shardBytes";
    static final int DEFAULT_SHARD_BYTES = 1024 * 1024;
    static final int MIN_SHARD_BYTES = 64 * 1024;
    static final int MAX_SHARD_BYTES = 8 * 1024 * 1024;

    private BootstrapShards() {}

    /**
     * 惰性遍历逐 service 分片（不物化整表），每片交给 {@code shardConsumer}；
     * 空表不产生分片（流正常结束即空注册表）。
     */
    static void send(Iterator<ServiceInstances> services, int budget, Consumer<List<ServiceInstances>> shardConsumer) {
        List<ServiceInstances> shard = new ArrayList<>();
        int size = 0;
        while (services.hasNext()) {
            ServiceInstances service = services.next();
            int serviceSize = service.getSerializedSize();
            if (!shard.isEmpty() && size + serviceSize > budget) {
                shardConsumer.accept(shard);
                shard = new ArrayList<>();
                size = 0;
            }
            shard.add(service);
            size += serviceSize;
        }
        if (!shard.isEmpty()) {
            shardConsumer.accept(shard);
        }
    }
}
