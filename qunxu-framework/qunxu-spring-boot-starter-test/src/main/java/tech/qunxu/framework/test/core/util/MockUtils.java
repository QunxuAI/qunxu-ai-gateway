package tech.qunxu.framework.test.core.util;

import org.mockito.Mockito;

/**
 * 测试工具类。
 */
public final class MockUtils {

    private MockUtils() {
    }

    /**
     * 创建 Mock 对象
     */
    public static <T> T mock(Class<T> clazz) {
        return Mockito.mock(clazz);
    }

}
