package tech.qunxu.module.system.controller.admin.notice.vo;

import lombok.Data;
import tech.qunxu.module.system.dal.dataobject.notice.NoticeDO;

import java.time.LocalDateTime;

/**
 * 通知公告响应。
 */
@Data
public class NoticeRespVO {

    private Long id;

    private String title;

    private String content;

    private Integer type;

    private Integer status;

    private LocalDateTime createTime;

    /**
     * DO → VO
     */
    public static NoticeRespVO from(NoticeDO notice) {
        NoticeRespVO vo = new NoticeRespVO();
        vo.setId(notice.getId());
        vo.setTitle(notice.getTitle());
        vo.setContent(notice.getContent());
        vo.setType(notice.getType());
        vo.setStatus(notice.getStatus());
        vo.setCreateTime(notice.getCreateTime());
        return vo;
    }

}
