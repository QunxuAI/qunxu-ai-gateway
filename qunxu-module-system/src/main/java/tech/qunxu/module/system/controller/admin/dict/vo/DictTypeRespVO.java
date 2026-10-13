package tech.qunxu.module.system.controller.admin.dict.vo;

import lombok.Data;
import tech.qunxu.module.system.dal.dataobject.dict.DictTypeDO;

import java.time.LocalDateTime;

/**
 * 字典类型响应。
 */
@Data
public class DictTypeRespVO {

    private Long id;

    private String name;

    private String type;

    private Integer status;

    private String remark;

    private LocalDateTime createTime;

    /**
     * DO → VO
     */
    public static DictTypeRespVO from(DictTypeDO dictType) {
        DictTypeRespVO vo = new DictTypeRespVO();
        vo.setId(dictType.getId());
        vo.setName(dictType.getName());
        vo.setType(dictType.getType());
        vo.setStatus(dictType.getStatus());
        vo.setRemark(dictType.getRemark());
        vo.setCreateTime(dictType.getCreateTime());
        return vo;
    }

}
