package tech.qunxu.framework.common.util.collection;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 集合工具类。
 * <p>
 * 提供常见的集合空判断与转换方法，避免业务代码重复编写。
 */
public final class CollectionUtils {

    private CollectionUtils() {
    }

    /**
     * 判断集合是否为空
     */
    public static boolean isEmpty(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }

    /**
     * 判断集合是否非空
     */
    public static boolean isNotEmpty(Collection<?> collection) {
        return !isEmpty(collection);
    }

    /**
     * 判断 Map 是否为空
     */
    public static boolean isEmpty(Map<?, ?> map) {
        return map == null || map.isEmpty();
    }

    /**
     * 判断 Map 是否非空
     */
    public static boolean isNotEmpty(Map<?, ?> map) {
        return !isEmpty(map);
    }

    /**
     * 集合转 Map：以 keyMapper 提取的值为 Key
     */
    public static <T, K> Map<K, T> convertMap(Collection<T> list, Function<T, K> keyMapper) {
        if (isEmpty(list)) {
            return Collections.emptyMap();
        }
        return list.stream().collect(Collectors.toMap(keyMapper, Function.identity(), (a, b) -> a));
    }

    /**
     * 集合转 Map：以 keyMapper 提取的值为 Key，valueMapper 提取的值为 Value
     */
    public static <T, K, V> Map<K, V> convertMap(Collection<T> list, Function<T, K> keyMapper,
                                                 Function<T, V> valueMapper) {
        if (isEmpty(list)) {
            return Collections.emptyMap();
        }
        return list.stream().collect(Collectors.toMap(keyMapper, valueMapper, (a, b) -> a));
    }

    /**
     * 集合转换：将集合元素通过 mapper 转换为新类型列表
     */
    public static <T, R> List<R> convertList(Collection<T> list, Function<T, R> mapper) {
        if (isEmpty(list)) {
            return Collections.emptyList();
        }
        return list.stream().map(mapper).collect(Collectors.toList());
    }

    /**
     * 集合转换：将集合元素通过 mapper 转换为新类型集合
     */
    public static <T, R> Set<R> convertSet(Collection<T> list, Function<T, R> mapper) {
        if (isEmpty(list)) {
            return Collections.emptySet();
        }
        return list.stream().map(mapper).collect(Collectors.toSet());
    }

    /**
     * 获取集合中第一个元素，空集合返回 null
     */
    public static <T> T getFirst(Collection<T> collection) {
        if (isEmpty(collection)) {
            return null;
        }
        return collection.iterator().next();
    }

    /**
     * 集合中是否包含指定元素（null 安全）
     */
    public static <T> boolean contains(Collection<T> collection, T value) {
        return collection != null && collection.contains(value);
    }

    /**
     * 求两个集合的差集：返回 a 中存在但 b 中不存在的元素
     */
    public static <T> List<T> difference(Collection<T> a, Collection<T> b) {
        if (isEmpty(a)) {
            return Collections.emptyList();
        }
        return a.stream().filter(item -> !b.contains(item)).collect(Collectors.toList());
    }

}
