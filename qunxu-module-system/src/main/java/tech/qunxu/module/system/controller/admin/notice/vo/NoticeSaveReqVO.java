package tech.qunxu.module.system.controller.admin.notice.vo;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 通知公告新增 / 修改请求。
 */
@Data
public class NoticeSaveReqVO {

    /** 通知公告编号（修改时必填） */
    private Long id;

    /** 标题 */
    @NotEmpty(message = "标题不能为空")
    private String title;

    /** 内容 */
    @NotEmpty(message = "内容不能为空")
    private String content;

    /** 类型（1 通知 2 公告） */
    @NotNull(message = "类型不能为空")
    private Integer type;

    /** 状态（0 关闭 1 开启） */
    private Integer status;

}
