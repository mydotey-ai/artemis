package org.mydotey.ai.artemis.client.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.mydotey.ai.artemis.proto.replication.v1.ClusterMember;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusResponse;
import org.mydotey.ai.artemis.proto.replication.v1.NodeState;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.ConfigurationProperties;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.type.string.StringToListConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP 前置寻址传输（registry/discovery 共用，v0.3 phase-8 泛化）：依次对配置 url 调
 * {@code GET <url>/cluster/nodes}（Basic 认证、响应体 = protobuf bytes），经注入的
 * {@link ResponseDecoder} 解析节点 gRPC 端点——registry 为 GetNodeStatusResponse
 * （含观测态优先级），discovery 为 DiscoveryNodesResponse（ADR-020，全部 UP）。
 * url 列表与凭证为 SCF 配置使用时重读；url 必配在构造期 fail-fast。
 * HttpURLConnection（JDK 自带，Java 8 零新增依赖）——低频 GET（5min 周期/退避重试）不引入 http client 组件。
 */
final class HttpNodeListFetcher implements NodeListFetcher {

    /** 响应体（protobuf bytes）→ 节点端点列表；两侧各持一个解析器实例。 */
    interface ResponseDecoder {
        List<NodeEndpoint> decode(byte[] body) throws Exception;
    }

    static final String KEY_REGISTRY_ADDRESSES = "artemis.client.registry.addresses";
    static final String KEY_DISCOVERY_ADDRESSES = "artemis.client.discovery.addresses";

    private static final Logger LOG = LoggerFactory.getLogger(HttpNodeListFetcher.class);

    private static final String NODES_PATH = "/cluster/nodes";
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 5_000;
    private static final int MAX_BODY_BYTES = 1 << 20;

    private final String addressesKey;
    private final Property<String, List<String>> addresses;
    private final Property<String, String> username;
    private final Property<String, String> password;
    private final ResponseDecoder decoder;

    HttpNodeListFetcher(StringProperties properties) {
        this(properties, KEY_REGISTRY_ADDRESSES, registryDecoder());
    }

    HttpNodeListFetcher(StringProperties properties, String addressesKey, ResponseDecoder decoder) {
        this.addressesKey = addressesKey;
        this.addresses = addressesProperty(properties, addressesKey);
        this.username = ClientProperties.authUsername(properties);
        this.password = ClientProperties.authPassword(properties);
        this.decoder = decoder;
        // 创建期必配校验（fail-fast，对齐原 serverAddresses 语义；非法值被 SCF filter 丢弃后同样为 null——
        // 消息同时覆盖缺失与非法，被拒值的具体原因见 validateAddresses 的 WARN）
        List<String> initial = addresses.getValue();
        if (initial == null || initial.isEmpty()) {
            throw new IllegalArgumentException(
                    addressesKey
                            + " must be set to valid http(s) url(s), e.g. http://artemis.example.com (missing or invalid — check WARN log for rejected values)");
        }
    }

    /** registry 解析器：GetNodeStatusResponse → 成员 external 端点 + 观测态优先级。 */
    static ResponseDecoder registryDecoder() {
        return new ResponseDecoder() {
            @Override
            public List<NodeEndpoint> decode(byte[] body) throws Exception {
                return toEndpoints(GetNodeStatusResponse.parseFrom(body));
            }
        };
    }

    /** string 源值经 SCF ListConverter 转成 url 列表，valueFilter 校验 http(s) url（非法整值丢弃 + WARN）。 */
    @SuppressWarnings("unchecked")
    static Property<String, List<String>> addressesProperty(StringProperties properties, String key) {
        return properties
                .getManager()
                .getProperty(ConfigurationProperties.<String, List<String>>newConfigBuilder()
                        .setKey(key)
                        .setValueType((Class<List<String>>) (Class<?>) List.class)
                        .addValueConverter(StringToListConverter.DEFAULT)
                        .setValueFilter(HttpNodeListFetcher::validateAddresses)
                        .setDoc(key + " bootstrap http urls, comma separated (required)")
                        .build());
    }

    @Override
    public List<NodeEndpoint> fetch() throws Exception {
        List<String> urls = addresses.getValue();
        if (urls == null || urls.isEmpty()) {
            throw new IllegalStateException(addressesKey + " must be set to valid http(s) url(s) (missing or invalid)");
        }
        Exception last = null;
        for (String url : urls) {
            try {
                return fetchFrom(url.trim()); // 与校验一致：逗号后空格等常见写法不因未 trim 失效
            } catch (Exception e) {
                last = e;
                LOG.debug("node list fetch failed from {}: {}", url, e.getMessage());
            }
        }
        throw new IllegalStateException("node list fetch failed from all " + urls.size() + " bootstrap url(s)", last);
    }

    private List<NodeEndpoint> fetchFrom(String url) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) URI.create(nodesUrl(url)).toURL().openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        String usernameValue = username.getValue();
        String passwordValue = password.getValue();
        if (ClientProperties.isNonBlank(usernameValue) && ClientProperties.isNonBlank(passwordValue)) {
            connection.setRequestProperty("Authorization", ClientBasicAuth.headerValue(usernameValue, passwordValue));
        }
        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("GET " + nodesUrl(url) + " returned " + status);
        }
        try {
            return decoder.decode(readBody(connection));
        } finally {
            connection.disconnect();
        }
    }

    /** 拼接发现端点路径：保留配置 url 的 path 前缀（如 http://host/artemis → /artemis/cluster/nodes）。 */
    static String nodesUrl(String url) {
        String trimmed = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        return trimmed + NODES_PATH;
    }

    /** registry 解析：external 端点非法（host 空/端口越界）跳过该成员（防御服务端异常配置）。 */
    private static List<NodeEndpoint> toEndpoints(GetNodeStatusResponse response) {
        List<NodeEndpoint> endpoints = new ArrayList<>();
        if (!response.hasData()) {
            return endpoints;
        }
        for (ClusterMember member : response.getData().getMembersList()) {
            String host = member.getExternalHost();
            int port = member.getExternalPort();
            if (host != null && !host.isEmpty() && port >= 1 && port <= 65535) {
                endpoints.add(new NodeEndpoint(host + ":" + port, priorityOf(member.getState())));
            }
        }
        return endpoints;
    }

    static int priorityOf(NodeState state) {
        switch (state) {
            case NODE_STATE_UP:
                return NodeEndpoint.PRIORITY_UP;
            case NODE_STATE_DOWN:
                return NodeEndpoint.PRIORITY_DOWN;
            default: // UNKNOWN / STARTING / 未识别
                return NodeEndpoint.PRIORITY_ALIVE;
        }
    }

    private static byte[] readBody(HttpURLConnection connection) throws IOException {
        InputStream stream = connection.getInputStream();
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > MAX_BODY_BYTES) {
                    throw new IOException("node list body exceeds " + MAX_BODY_BYTES + " bytes");
                }
            }
            return buffer.toByteArray();
        } finally {
            stream.close();
        }
    }

    private static List<String> validateAddresses(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return urls; // 必配校验归构造期/使用期（必配缺失 fail-fast 消息更准确）
        }
        for (String url : urls) {
            try {
                validateAddress(url);
            } catch (IllegalArgumentException e) {
                LOG.warn(e.getMessage());
                return null; // SCF 不传播 filter 异常：丢弃非法值（getValue 为 null，由必配校验兜底）
            }
        }
        return urls;
    }

    /** 校验 http(s) url：scheme 限定、host 必填、端口可选（缺省走 scheme 默认）、拒绝 query/fragment（拼接发现路径会变形）。 */
    private static void validateAddress(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (RuntimeException e) {
            throw invalid(url);
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw invalid(url);
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw invalid(url);
        }
        if (uri.getPort() < -1 || uri.getPort() > 65535) {
            throw invalid(url);
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw invalid(url);
        }
    }

    private static IllegalArgumentException invalid(String url) {
        return new IllegalArgumentException("invalid bootstrap url '" + url + "' (expected http(s)://host[:port])");
    }
}
