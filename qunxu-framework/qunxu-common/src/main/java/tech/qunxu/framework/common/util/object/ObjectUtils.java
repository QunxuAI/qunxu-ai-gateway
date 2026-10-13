package tech.qunxu.framework.common.util.object;

/**
 * 对象工具类。
 */
public final class ObjectUtils {

    private ObjectUtils() {
    }

    /**
     * 判断对象是否为空：null、空字符串、空白字符串均视为空
     */
    public static boolean isEmpty(Object object) {
        if (object == null) {
            return true;
        }
        if (object instanceof CharSequence cs) {
            return cs.toString().trim().isEmpty();
        }
        return false;
    }

    /**
     * 判断对象是否非空
     */
    public static boolean isNotEmpty(Object object) {
        return !isEmpty(object);
    }

    /**
     * 对象相等判断（null 安全）
     */
    public static boolean equals(Object a, Object b) {
        return java.util.Objects.equals(a, b);
    }

    /**
     * 对象转字符串，null 返回空字符串
     */
    public static String toStr(Object object) {
        return object == null ? "" : object.toString();
    }

    /**
     * 获取对象默认值：对象为 null 时返回 defaultValue
     */
    public static <T> T defaultIfNull(T object, T defaultValue) {
        return object != null ? object : defaultValue;
    }

    /**
     * 克隆对象（需实现 Cloneable）
     */
    @SuppressWarnings("unchecked")
    public static <T extends Cloneable> T clone(T object) {
        try {
            return (T) object.getClass().getMethod("clone").invoke(object);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("对象克隆失败: " + object.getClass().getName(), e);
        }
    }

}
