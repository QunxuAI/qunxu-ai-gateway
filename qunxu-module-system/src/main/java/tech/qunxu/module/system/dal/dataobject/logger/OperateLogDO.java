package tech.qunxu.module.system.dal.dataobject.logger;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.tenant.core.db.TenantBaseDO;

import java.time.LocalDateTime;

/**
 * 操作日志 DO。
 */
@TableName("system_operate_log")
@Data
@EqualsAndHashCode(callSuper = true)
public class OperateLogDO extends TenantBaseDO {

    /** 用户编号 */
    private Long userId;

    /** 用户类型 */
    private Integer userType;

    /** 模块名 */
    private String module;

    /** 操作名 */
    private String name;

    /** 操作类型：create / update / delete / query / other */
    private String type;

    /** 操作内容描述 */
    private String content;

    /** 请求方法 */
    private String requestMethod;

    /** 请求地址 */
    private String requestUrl;

    /** 用户 IP */
    private String userIp;

    /** 浏览器 UA */
    private String userAgent;

    /** Java 方法全名 */
    private String javaMethod;

    /** Java 方法入参（JSON 字符串） */
    private String javaMethodArgs;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 执行时长（毫秒） */
    private Integer duration;

    /** 结果码：0 成功，非 0 失败 */
    private Integer resultCode;

    /** 结果提示 */
    private String resultMsg;

    /** 结果数据（JSON 字符串） */
    private String resultData;

}
