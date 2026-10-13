package tech.qunxu.module.system.service.dict;

import com.baomidou.dynamic.datasource.annotation.DS;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tech.qunxu.framework.common.exception.util.ServiceExceptionUtil;
import tech.qunxu.framework.common.pojo.PageResult;
import tech.qunxu.module.system.controller.admin.dict.vo.DictTypePageReqVO;
import tech.qunxu.module.system.controller.admin.dict.vo.DictTypeSaveReqVO;
import tech.qunxu.module.system.dal.dataobject.dict.DictDataDO;
import tech.qunxu.module.system.dal.dataobject.dict.DictTypeDO;
import tech.qunxu.module.system.dal.mysql.dict.DictDataMapper;
import tech.qunxu.module.system.dal.mysql.dict.DictTypeMapper;
import tech.qunxu.module.system.enums.ErrorCodeConstants;

import java.util.List;

/**
 * 字典类型服务。
 */
@Service
@RequiredArgsConstructor
public class DictTypeService {

    private final DictTypeMapper dictTypeMapper;
    private final DictDataMapper dictDataMapper;

    /**
     * 分页查询字典类型
     */
    @DS("slave")
    public PageResult<DictTypeDO> getDictTypePage(DictTypePageReqVO reqVO) {
        return dictTypeMapper.selectPage(reqVO, new LambdaQueryWrapper<DictTypeDO>()
                .like(StrUtil.isNotEmpty(reqVO.getName()), DictTypeDO::getName, reqVO.getName())
                .like(StrUtil.isNotEmpty(reqVO.getType()), DictTypeDO::getType, reqVO.getType())
                .eq(reqVO.getStatus() != null, DictTypeDO::getStatus, reqVO.getStatus())
                .orderByDesc(DictTypeDO::getId));
    }

    /**
     * 获取字典类型
     */
    @DS("slave")
    public DictTypeDO getDictType(Long id) {
        return dictTypeMapper.selectById(id);
    }

    /**
     * 获取全部字典类型（用于下拉框）
     */
    @DS("slave")
    public List<DictTypeDO> getDictTypeSimpleList() {
        return dictTypeMapper.selectList(new LambdaQueryWrapper<DictTypeDO>().orderByDesc(DictTypeDO::getId));
    }

    /**
     * 创建字典类型
     */
    public Long createDictType(DictTypeSaveReqVO reqVO) {
        validateDictTypeUnique(reqVO.getType(), null);
        DictTypeDO dictType = new DictTypeDO();
        dictType.setName(reqVO.getName());
        dictType.setType(reqVO.getType());
        dictType.setStatus(reqVO.getStatus());
        dictType.setRemark(reqVO.getRemark());
        dictTypeMapper.insert(dictType);
        return dictType.getId();
    }

    /**
     * 更新字典类型
     */
    public void updateDictType(DictTypeSaveReqVO reqVO) {
        validateDictTypeExists(reqVO.getId());
        validateDictTypeUnique(reqVO.getType(), reqVO.getId());
        DictTypeDO dictType = dictTypeMapper.selectById(reqVO.getId());
        dictType.setName(reqVO.getName());
        dictType.setType(reqVO.getType());
        dictType.setStatus(reqVO.getStatus());
        dictType.setRemark(reqVO.getRemark());
        dictTypeMapper.updateById(dictType);
    }

    /**
     * 删除字典类型
     */
    public void deleteDictType(Long id) {
        validateDictTypeExists(id);
        // 类型下存在数据不允许删除
        Long count = dictDataMapper.selectCount(new LambdaQueryWrapper<DictDataDO>().eq(DictDataDO::getDictType, id));
        if (count > 0) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.DICT_TYPE_HAS_DATA);
        }
        dictTypeMapper.deleteById(id);
    }

    private void validateDictTypeExists(Long id) {
        if (dictTypeMapper.selectById(id) == null) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.DICT_TYPE_NOT_EXISTS);
        }
    }

    private void validateDictTypeUnique(String type, Long excludeId) {
        Long count = dictTypeMapper.selectCount(new LambdaQueryWrapper<DictTypeDO>()
                .eq(DictTypeDO::getType, type)
                .ne(excludeId != null, DictTypeDO::getId, excludeId));
        if (count > 0) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.DICT_TYPE_EXISTS);
        }
    }

}
