package org.mydotey.ai.artemis.registry.infrastructure;

import java.util.ArrayList;
import java.util.List;
import org.mydotey.ai.artemis.common.config.ScfProperties;
import org.mydotey.ai.artemis.registry.domain.ClusterMember;
import org.mydotey.ai.artemis.registry.domain.ClusterMembers;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;

/**
 * 领域端口 {@link ClusterMembers} 的静态配置实现（架构 §4.1：成员抽象接口先行为主）。
 * 成员列表在启动期由本类定义并解析固化（逗号分隔 `host:port`）。
 */
public final class StaticClusterMembers implements ClusterMembers {

    static final String KEY_MEMBERS = "artemis.registry.cluster.members";

    private final Property<String, String> membersProperty;
    private final List<ClusterMember> members;

    public StaticClusterMembers(StringProperties properties) {
        this(ScfProperties.staticString(properties, KEY_MEMBERS, "", "static cluster members host:port list"));
    }

    StaticClusterMembers(Property<String, String> membersProperty) {
        this.membersProperty = membersProperty;
        this.members = parse(membersProperty.getValue());
    }

    @Override
    public List<ClusterMember> members() {
        return members;
    }

    static List<ClusterMember> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<ClusterMember> members = new ArrayList<>();
        for (String entry : raw.split(",")) {
            String value = entry.trim();
            if (value.isEmpty()) {
                continue;
            }
            String host;
            String portText;
            if (value.startsWith("[")) {
                // 括号 IPv6：host:port 中的冒号仅作分隔，如 [fd00::1]:9092
                int close = value.indexOf(']');
                if (close <= 1 || close + 1 >= value.length() || value.charAt(close + 1) != ':') {
                    throw new IllegalArgumentException(KEY_MEMBERS + " has invalid member: " + value);
                }
                host = value.substring(1, close).trim();
                portText = value.substring(close + 2).trim();
            } else {
                int firstColon = value.indexOf(':');
                int lastColon = value.lastIndexOf(':');
                // 裸 IPv6 含多个冒号，无法区分 host/port，必须用括号
                if (firstColon <= 0 || firstColon != lastColon || lastColon == value.length() - 1) {
                    throw new IllegalArgumentException(KEY_MEMBERS + " has invalid member: " + value);
                }
                host = value.substring(0, lastColon).trim();
                portText = value.substring(lastColon + 1).trim();
            }
            int port;
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(KEY_MEMBERS + " has invalid member: " + value, e);
            }
            if (host.isEmpty() || port < 1 || port > 65535) {
                throw new IllegalArgumentException(KEY_MEMBERS + " has invalid member: " + value);
            }
            members.add(new ClusterMember(value, host, port));
        }
        return List.copyOf(members);
    }
}
