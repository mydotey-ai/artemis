package org.mydotey.ai.artemis.discovery.server.upstream;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.mydotey.scf.type.AbstractTypeConverter;

/**
 * JSON map 配置转换（phase-5）：{@code {"clusterA":"host:port,host:port"}} →
 * {@code Map<String, List<String>>}（键 = clusterId，值 = 逗号分隔端点字符串切分结果）。
 *
 * <p>非法值<b>返回 null 不抛异常</b>（SCF 语义：converter 抛异常会被 manager catch 后
 * 跳过整个 source 打 ERROR 日志；返回 null 则视为未命中落默认值）——fail-fast 收敛到
 * {@link UpstreamConfig} 构造器出带配置键的清晰消息。内建 StringToMapConverter 按逗号/
 * 冒号切分且静默丢坏项（端点含冒号无法表达），不适用。
 */
@SuppressWarnings({"unchecked", "rawtypes"})
final class StringToJsonListMapConverter extends AbstractTypeConverter<String, Map<String, List<String>>> {

    static final StringToJsonListMapConverter DEFAULT = new StringToJsonListMapConverter();

    /** wire 形态：值为逗号分隔端点串（非 JSON 数组），先按 Map&lt;String,String&gt; 反序列化再切分。 */
    private static final TypeToken<Map<String, String>> RAW_TYPE = new TypeToken<Map<String, String>>() {};

    private final Gson gson = new Gson();

    private StringToJsonListMapConverter() {
        super(String.class, (Class) Map.class); // 泛型擦除，与 SCF 内建 converter 同法
    }

    @Override
    public Map<String, List<String>> convert(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        try {
            Map<String, String> raw = gson.fromJson(source, RAW_TYPE.getType());
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            Map<String, List<String>> result = new LinkedHashMap<>(raw.size());
            for (Map.Entry<String, String> entry : raw.entrySet()) {
                result.put(entry.getKey(), splitEndpoints(entry.getValue()));
            }
            return result;
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    private static List<String> splitEndpoints(String value) {
        if (value == null) {
            return List.of();
        }
        List<String> endpoints = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                endpoints.add(trimmed);
            }
        }
        return endpoints;
    }
}
