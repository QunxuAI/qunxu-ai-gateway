package tech.qunxu.module.system.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 通知公告类型枚举。
 */
@Getter
@AllArgsConstructor
public enum NoticeTypeEnum {

    /** 通知 */
    NOTICE(1),
    /** 公告 */
    ANNOUNCEMENT(2);

    /** 类型值 */
    private final Integer type;

    public static NoticeTypeEnum valueOfType(Integer type) {
        return Arrays.stream(values())
                .filter(item -> item.type.equals(type))
                .findFirst()
                .orElse(null);
    }

}
