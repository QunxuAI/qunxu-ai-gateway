package tech.qunxu.module.system.controller.admin.dict.vo;

import lombok.Data;
import tech.qunxu.module.system.dal.dataobject.dict.DictDataDO;

import java.time.LocalDateTime;

/**
 * 字典数据响应。
 */
@Data
public class DictDataRespVO {

    private Long id;

    private Integer sort;

    private String label;

    private String value;

    private String dictType;

    private Integer status;

    private String colorType;

    private String cssClass;

    private String remark;

    private LocalDateTime createTime;

    /**
     * DO → VO
     */
    public static DictDataRespVO from(DictDataDO dictData) {
        DictDataRespVO vo = new DictDataRespVO();
        vo.setId(dictData.getId());
        vo.setSort(dictData.getSort());
        vo.setLabel(dictData.getLabel());
        vo.setValue(dictData.getValue());
        vo.setDictType(dictData.getDictType());
        vo.setStatus(dictData.getStatus());
        vo.setColorType(dictData.getColorType());
        vo.setCssClass(dictData.getCssClass());
        vo.setRemark(dictData.getRemark());
        vo.setCreateTime(dictData.getCreateTime());
        return vo;
    }

}
