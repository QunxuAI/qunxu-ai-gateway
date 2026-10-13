package tech.qunxu.module.system.controller.admin.logger.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.common.pojo.PageParam;

/**
 * 操作日志分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OperateLogPageReqVO extends PageParam {

    /** 模块名 */
    private String module;

    /** 操作名 */
    private String name;

    /** 用户编号 */
    private Long userId;

    /** 结果码（0 成功 -1 失败） */
    private Integer resultCode;

}
