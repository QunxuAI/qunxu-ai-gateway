package tech.qunxu.module.gateway.common.util;

import tools.jackson.databind.JsonNode;

/**
 * Jackson {@link JsonNode} 安全导航工具：统一处理字段缺失 / null / 类型不符，返回 {@code null} 或默认值。
 *
 * <p>协议层与适配器解析下游/客户端 JSON 时复用，避免重复的空判定样板。</p>
 */
public final class JsonUtils {

    private JsonUtils() {
    }

    /** 取文本字段；缺失或 null 返回 {@code null}。 */
    public static String text(JsonNode node, String field) {
        JsonNode value = child(node, field);
        return value == null ? null : value.asText();
    }

    /** 取文本字段并去除空串；空串或 null 返回 {@code null}。 */
    public static String textOrNullEmpty(JsonNode node, String field) {
        String text = text(node, field);
        return (text == null || text.isEmpty()) ? null : text;
    }

    /** 取整型字段；缺失或非数值返回 {@code null}。 */
    public static Integer integer(JsonNode node, String field) {
        JsonNode value = child(node, field);
        return (value == null || !value.isNumber()) ? null : value.asInt();
    }

    /** 取长整型字段；缺失或非数值返回 {@code null}。 */
    public static Long longVal(JsonNode node, String field) {
        JsonNode value = child(node, field);
        return (value == null || !value.isNumber()) ? null : value.asLong();
    }

    /** 取双精度字段；缺失或非数值返回 {@code null}。 */
    public static Double doubleVal(JsonNode node, String field) {
        JsonNode value = child(node, field);
        return (value == null || !value.isNumber()) ? null : value.asDouble();
    }

    /** 取布尔字段；缺失或非布尔返回 {@code null}。 */
    public static Boolean bool(JsonNode node, String field) {
        JsonNode value = child(node, field);
        return (value == null || !value.isBoolean()) ? null : value.asBoolean();
    }

    /** 布尔字段，缺失时返回默认值（用于 {@code stream} 等）。 */
    public static boolean boolOr(JsonNode node, String field, boolean defaultValue) {
        JsonNode value = child(node, field);
        return value == null ? defaultValue : value.asBoolean(defaultValue);
    }

    /** 判断对象字段存在且非 null。 */
    public static boolean hasNonNull(JsonNode node, String field) {
        JsonNode value = child(node, field);
        return value != null;
    }

    /** 安全取子节点：节点为空或字段缺失/Null 返回 {@code null}。 */
    public static JsonNode child(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value;
    }

    /** 安全取路径节点（始终非 null，缺失时为 MissingNode），便于链式 {@code path().path()}。 */
    public static JsonNode path(JsonNode node, String field) {
        return node == null ? tools.jackson.databind.node.MissingNode.getInstance() : node.path(field);
    }
}
