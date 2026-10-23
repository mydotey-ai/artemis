package org.mydotey.ai.artemis.client.internal;

import java.util.List;

/**
 * 前置寻址传输端口：调服务端发现端点取节点列表（gRPC 端点 + 观测态优先级）。
 * 成功返回非空列表；失败（网络/认证/非 200/解析）抛异常、空 members 以空列表表达——
 * 两者均由 {@link NodeListRefresher} 按失败退避重试。
 */
interface NodeListFetcher {

    List<NodeEndpoint> fetch() throws Exception;
}
