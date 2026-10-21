package org.mydotey.ai.artemis.registry.infrastructure;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.mydotey.ai.artemis.common.config.ScfProperties;
import org.mydotey.ai.artemis.registry.domain.ClusterEndpoint;
import org.mydotey.ai.artemis.registry.domain.ClusterMember;
import org.mydotey.ai.artemis.registry.domain.ClusterMembers;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.type.string.StringToListConverter;

/**
 * 领域端口 {@link ClusterMembers} 的静态配置实现（架构 §4.1：成员抽象接口先行为主）。
 *
 * <p>成员条目 {@code <node-id>@<peer-host>:<peer-port>@<external-host>:<external-port>}
 * （逗号分隔，host 支持括号 IPv6）；自身身份显式配置 {@code artemis.registry.cluster.self}，
 * 替代按 URL 子串猜测本机。成员表非空时 self 必填且必须命中；成员表为空（单节点）时 self 必须缺省。
 * 非法条目/重复 id/端口越界/self 不合法均启动失败（fail-fast）。
 */
public final class StaticClusterMembers implements ClusterMembers {

    static final String KEY_MEMBERS = "artemis.registry.cluster.members";
    static final String KEY_SELF = "artemis.registry.cluster.self";

    private final List<ClusterMember> members;
    private final Optional<ClusterMember> self;

    @SuppressWarnings("unchecked")
    public StaticClusterMembers(StringProperties properties) {
        this(
                ScfProperties.staticProperty(
                        properties,
                        KEY_MEMBERS,
                        (Class<List<String>>) (Class<?>) List.class,
                        List.of(),
                        StringToListConverter.DEFAULT,
                        null,
                        "static cluster members <node-id>@<peer-host>:<peer-port>@<external-host>:<external-port> list"),
                ScfProperties.staticString(properties, KEY_SELF, "", "static self node id in cluster members"));
    }

    StaticClusterMembers(Property<String, List<String>> membersProperty, Property<String, String> selfProperty) {
        this.members = parse(membersProperty.getValue());
        this.self = resolveSelf(selfProperty.getValue());
    }

    @Override
    public List<ClusterMember> members() {
        return members;
    }

    @Override
    public Optional<ClusterMember> self() {
        return self;
    }

    private Optional<ClusterMember> resolveSelf(String selfId) {
        boolean configured = selfId != null && !selfId.isBlank();
        if (members.isEmpty()) {
            if (configured) {
                throw new IllegalArgumentException(KEY_SELF + " set but " + KEY_MEMBERS + " is empty");
            }
            return Optional.empty();
        }
        if (!configured) {
            throw new IllegalArgumentException(KEY_SELF + " is required when " + KEY_MEMBERS + " is not empty");
        }
        String id = selfId.trim();
        for (ClusterMember member : members) {
            if (member.nodeId().equals(id)) {
                return Optional.of(member);
            }
        }
        throw new IllegalArgumentException(KEY_SELF + " '" + id + "' not found in " + KEY_MEMBERS);
    }

    static List<ClusterMember> parse(List<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<ClusterMember> members = new ArrayList<>();
        Set<String> nodeIds = new HashSet<>();
        for (String raw : entries) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String value = raw.trim();
            String[] parts = value.split("@", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException(KEY_MEMBERS
                        + " has invalid member (expected <id>@<peer-host>:<peer-port>@<external-host>:<external-port>): "
                        + value);
            }
            String nodeId = parts[0].trim();
            ClusterEndpoint peer = parseEndpoint(parts[1].trim());
            ClusterEndpoint external = parseEndpoint(parts[2].trim());
            if (nodeId.isEmpty()) {
                throw new IllegalArgumentException(KEY_MEMBERS + " has invalid member: " + value);
            }
            if (!nodeIds.add(nodeId)) {
                throw new IllegalArgumentException(KEY_MEMBERS + " has duplicate node id: " + nodeId);
            }
            members.add(new ClusterMember(nodeId, peer, external));
        }
        return List.copyOf(members);
    }

    private static ClusterEndpoint parseEndpoint(String value) {
        String host;
        String portText;
        if (value.startsWith("[")) {
            // 括号 IPv6：host:port 中的冒号仅作分隔，如 [fd00::1]:9093
            int close = value.indexOf(']');
            if (close <= 1 || close + 1 >= value.length() || value.charAt(close + 1) != ':') {
                throw new IllegalArgumentException(KEY_MEMBERS + " has invalid endpoint: " + value);
            }
            host = value.substring(1, close).trim();
            portText = value.substring(close + 2).trim();
        } else {
            int firstColon = value.indexOf(':');
            int lastColon = value.lastIndexOf(':');
            // 裸 IPv6 含多个冒号，无法区分 host/port，必须用括号
            if (firstColon <= 0 || firstColon != lastColon || lastColon == value.length() - 1) {
                throw new IllegalArgumentException(KEY_MEMBERS + " has invalid endpoint: " + value);
            }
            host = value.substring(0, lastColon).trim();
            portText = value.substring(lastColon + 1).trim();
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(KEY_MEMBERS + " has invalid endpoint: " + value, e);
        }
        validateHost(value, host);
        try {
            return new ClusterEndpoint(host, port);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(KEY_MEMBERS + " has invalid endpoint: " + value, e);
        }
    }

    /** host 语法校验：拒绝空白/非法 IPv6；主机名不解析 DNS（仅语法检查）。 */
    private static void validateHost(String endpoint, String host) {
        if (host.isEmpty()
                || host.chars().anyMatch(Character::isWhitespace)
                || host.contains("[")
                || host.contains("]")) {
            throw new IllegalArgumentException(KEY_MEMBERS + " has invalid endpoint: " + endpoint);
        }
        if (host.contains(":")) {
            try {
                if (!(InetAddress.getByName(host) instanceof Inet6Address)) {
                    throw new IllegalArgumentException(KEY_MEMBERS + " has invalid IPv6 endpoint: " + endpoint);
                }
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException(KEY_MEMBERS + " has invalid IPv6 endpoint: " + endpoint, e);
            }
        }
    }
}
