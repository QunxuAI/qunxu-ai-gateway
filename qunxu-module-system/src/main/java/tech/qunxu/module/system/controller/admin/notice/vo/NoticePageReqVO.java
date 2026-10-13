package tech.qunxu.module.system.controller.admin.notice.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import tech.qunxu.framework.common.pojo.PageParam;

/**
 * 通知公告分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NoticePageReqVO extends PageParam {

    /** 标题 */
    private String title;

    /** 类型（1 通知 2 公告） */
    private Integer type;

    /** 状态（0 关闭 1 开启） */
    private Integer status;

}
