package tech.qunxu.module.system.controller.admin.post.vo;

import lombok.Data;
import tech.qunxu.module.system.dal.dataobject.post.PostDO;

import java.time.LocalDateTime;

/**
 * 岗位响应。
 */
@Data
public class PostRespVO {

    private Long id;

    private String name;

    private String code;

    private Integer sort;

    private Integer status;

    private String remark;

    private LocalDateTime createTime;

    /**
     * DO → VO
     */
    public static PostRespVO from(PostDO post) {
        PostRespVO vo = new PostRespVO();
        vo.setId(post.getId());
        vo.setName(post.getName());
        vo.setCode(post.getCode());
        vo.setSort(post.getSort());
        vo.setStatus(post.getStatus());
        vo.setRemark(post.getRemark());
        vo.setCreateTime(post.getCreateTime());
        return vo;
    }

}
