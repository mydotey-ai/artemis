package org.mydotey.ai.artemis.client.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.mydotey.scf.Property;

/**
 * 配置驱动的地址供给：每次 {@link #select()} 读取 SCF property——无静态固化（未 setStatic(true)）。
 * 运行时变更的生效时机由**配置源语义**决定：文件源重载/自定义源 raiseChangeEvent 后 SCF 缓存刷新，
 * 下次选址（断流重连）即用新值；系统属性/环境变量是启动期源，不产生变更事件（需重建 client）。
 * 三级容灾骨架端口实现之一；存活节点列表拉取、熔断换址、TTL 强制轮换为 v0.2 扩展点。
 */
final class ConfigAddressProvider implements ServerAddressProvider {

    static final String KEY_SERVER_ADDRESSES = "artemis.client.serverAddresses";

    private final Property<String, String> serverAddresses;

    ConfigAddressProvider(Property<String, String> serverAddresses) {
        this.serverAddresses = serverAddresses;
    }

    static Property<String, String> property(org.mydotey.scf.facade.StringProperties properties) {
        return ClientProperties.stringProperty(
                properties, KEY_SERVER_ADDRESSES, null, "registry server addresses, comma separated (required)");
    }

    @Override
    public String select() {
        List<String> addresses = parseAddresses(serverAddresses.getValue());
        if (addresses.size() == 1) {
            return addresses.get(0);
        }
        return addresses.get(ThreadLocalRandom.current().nextInt(addresses.size()));
    }

    /** 逗号分隔地址解析（host:port）；空白/缺失抛 {@link IllegalArgumentException}（消费方构造期 fail-fast）。 */
    static List<String> parseAddresses(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(KEY_SERVER_ADDRESSES + " must be set (comma separated host:port)");
        }
        List<String> addresses = new ArrayList<>();
        for (String part : value.split(",")) {
            String address = part.trim();
            if (address.isEmpty()) {
                continue;
            }
            validateAddress(address);
            addresses.add(address);
        }
        if (addresses.isEmpty()) {
            throw new IllegalArgumentException(KEY_SERVER_ADDRESSES + " must contain at least one host:port");
        }
        return addresses;
    }

    /** 严格校验 host:port（端口须为 1–65535 数字；IPv6 用 [addr]:port 形式）。 */
    private static void validateAddress(String address) {
        int separator = address.lastIndexOf(':');
        if (separator <= 0 || separator == address.length() - 1) {
            throw invalid(address, "expected host:port");
        }
        String port = address.substring(separator + 1);
        try {
            int portValue = Integer.parseInt(port);
            if (portValue < 1 || portValue > 65535) {
                throw invalid(address, "port out of range (1-65535)");
            }
        } catch (NumberFormatException e) {
            throw invalid(address, "port is not a number");
        }
        String host = address.substring(0, separator);
        if (host.indexOf(':') >= 0 && !(host.startsWith("[") && host.endsWith("]"))) {
            throw invalid(address, "IPv6 host must be bracketed ([addr]:port)");
        }
        if (host.isEmpty() || "[]".equals(host)) {
            throw invalid(address, "host must not be empty");
        }
    }

    private static IllegalArgumentException invalid(String address, String reason) {
        return new IllegalArgumentException(
                "invalid server address '" + address + "' (" + reason + ") in " + KEY_SERVER_ADDRESSES);
    }
}
