package tech.qunxu.framework.common.util.json;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;

import java.util.List;
import java.util.Map;

/**
 * JSON 工具类，基于 Fastjson2。
 */
public final class JsonUtils {

    private JsonUtils() {
    }

    /**
     * 对象序列化为 JSON 字符串
     */
    public static String toJsonString(Object object) {
        return JSON.toJSONString(object);
    }

    /**
     * JSON 字符串反序列化为指定类型对象
     */
    public static <T> T parseObject(String text, Class<T> clazz) {
        return JSON.parseObject(text, clazz);
    }

    /**
     * JSON 字符串反序列化为泛型对象（如嵌套泛型）
     */
    public static <T> T parseObject(String text, TypeReference<T> typeReference) {
        return JSON.parseObject(text, typeReference);
    }

    /**
     * JSON 字符串反序列化为对象列表
     */
    public static <T> List<T> parseArray(String text, Class<T> clazz) {
        return JSON.parseArray(text, clazz);
    }

    /**
     * 对象转换为 Map
     */
    public static Map<String, Object> toMap(Object object) {
        return JSON.parseObject(JSON.toJSONString(object));
    }

    /**
     * 判断字符串是否为合法的 JSON
     */
    public static boolean isValidJson(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        try {
            JSON.parse(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

}
