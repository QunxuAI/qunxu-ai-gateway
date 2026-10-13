package tech.qunxu.module.system.service.dict;

import com.baomidou.dynamic.datasource.annotation.DS;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tech.qunxu.framework.common.enums.CommonStatusEnum;
import tech.qunxu.framework.common.exception.util.ServiceExceptionUtil;
import tech.qunxu.framework.common.pojo.PageResult;
import tech.qunxu.module.system.controller.admin.dict.vo.DictDataPageReqVO;
import tech.qunxu.module.system.controller.admin.dict.vo.DictDataSaveReqVO;
import tech.qunxu.module.system.dal.dataobject.dict.DictDataDO;
import tech.qunxu.module.system.dal.mysql.dict.DictDataMapper;
import tech.qunxu.module.system.enums.ErrorCodeConstants;

import java.util.List;

/**
 * 字典数据服务。
 */
@Service
@RequiredArgsConstructor
public class DictDataService {

    private final DictDataMapper dictDataMapper;

    /**
     * 分页查询字典数据
     */
    @DS("slave")
    public PageResult<DictDataDO> getDictDataPage(DictDataPageReqVO reqVO) {
        return dictDataMapper.selectPage(reqVO, new LambdaQueryWrapper<DictDataDO>()
                .like(StrUtil.isNotEmpty(reqVO.getLabel()), DictDataDO::getLabel, reqVO.getLabel())
                .eq(StrUtil.isNotEmpty(reqVO.getDictType()), DictDataDO::getDictType, reqVO.getDictType())
                .eq(reqVO.getStatus() != null, DictDataDO::getStatus, reqVO.getStatus())
                .orderByAsc(DictDataDO::getSort));
    }

    /**
     * 获取字典数据
     */
    @DS("slave")
    public DictDataDO getDictData(Long id) {
        return dictDataMapper.selectById(id);
    }

    /**
     * 根据字典类型获取启用的字典数据列表
     */
    @DS("slave")
    public List<DictDataDO> getDictDataListByDictType(String dictType) {
        return dictDataMapper.selectList(new LambdaQueryWrapper<DictDataDO>()
                .eq(DictDataDO::getDictType, dictType)
                .eq(DictDataDO::getStatus, CommonStatusEnum.ENABLE.getStatus())
                .orderByAsc(DictDataDO::getSort));
    }

    /**
     * 创建字典数据
     */
    public Long createDictData(DictDataSaveReqVO reqVO) {
        validateDictDataUnique(reqVO.getDictType(), reqVO.getValue(), null);
        DictDataDO dictData = new DictDataDO();
        dictData.setSort(reqVO.getSort());
        dictData.setLabel(reqVO.getLabel());
        dictData.setValue(reqVO.getValue());
        dictData.setDictType(reqVO.getDictType());
        dictData.setStatus(reqVO.getStatus());
        dictData.setColorType(reqVO.getColorType());
        dictData.setCssClass(reqVO.getCssClass());
        dictData.setRemark(reqVO.getRemark());
        dictDataMapper.insert(dictData);
        return dictData.getId();
    }

    /**
     * 更新字典数据
     */
    public void updateDictData(DictDataSaveReqVO reqVO) {
        validateDictDataExists(reqVO.getId());
        validateDictDataUnique(reqVO.getDictType(), reqVO.getValue(), reqVO.getId());
        DictDataDO dictData = dictDataMapper.selectById(reqVO.getId());
        dictData.setSort(reqVO.getSort());
        dictData.setLabel(reqVO.getLabel());
        dictData.setValue(reqVO.getValue());
        dictData.setDictType(reqVO.getDictType());
        dictData.setStatus(reqVO.getStatus());
        dictData.setColorType(reqVO.getColorType());
        dictData.setCssClass(reqVO.getCssClass());
        dictData.setRemark(reqVO.getRemark());
        dictDataMapper.updateById(dictData);
    }

    /**
     * 删除字典数据
     */
    public void deleteDictData(Long id) {
        validateDictDataExists(id);
        dictDataMapper.deleteById(id);
    }

    private void validateDictDataExists(Long id) {
        if (dictDataMapper.selectById(id) == null) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.DICT_DATA_NOT_EXISTS);
        }
    }

    private void validateDictDataUnique(String dictType, String value, Long excludeId) {
        Long count = dictDataMapper.selectCount(new LambdaQueryWrapper<DictDataDO>()
                .eq(DictDataDO::getDictType, dictType)
                .eq(DictDataDO::getValue, value)
                .ne(excludeId != null, DictDataDO::getId, excludeId));
        if (count > 0) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.DICT_DATA_EXISTS);
        }
    }

}
