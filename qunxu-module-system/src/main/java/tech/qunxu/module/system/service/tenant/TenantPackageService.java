package tech.qunxu.module.system.service.tenant;

import com.baomidou.dynamic.datasource.annotation.DS;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tech.qunxu.framework.common.exception.util.ServiceExceptionUtil;
import tech.qunxu.framework.common.pojo.PageResult;
import tech.qunxu.module.system.controller.admin.tenant.vo.TenantPackagePageReqVO;
import tech.qunxu.module.system.controller.admin.tenant.vo.TenantPackageSaveReqVO;
import tech.qunxu.module.system.dal.dataobject.tenant.TenantDO;
import tech.qunxu.module.system.dal.dataobject.tenant.TenantPackageDO;
import tech.qunxu.module.system.dal.mysql.tenant.TenantMapper;
import tech.qunxu.module.system.dal.mysql.tenant.TenantPackageMapper;
import tech.qunxu.module.system.enums.ErrorCodeConstants;

import java.util.List;

/**
 * 租户套餐服务。
 */
@Service
@RequiredArgsConstructor
public class TenantPackageService {

    private final TenantPackageMapper tenantPackageMapper;
    private final TenantMapper tenantMapper;

    /**
     * 分页查询租户套餐
     */
    @DS("slave")
    public PageResult<TenantPackageDO> getTenantPackagePage(TenantPackagePageReqVO reqVO) {
        return tenantPackageMapper.selectPage(reqVO, new LambdaQueryWrapper<TenantPackageDO>()
                .like(StrUtil.isNotEmpty(reqVO.getName()), TenantPackageDO::getName, reqVO.getName())
                .eq(reqVO.getStatus() != null, TenantPackageDO::getStatus, reqVO.getStatus())
                .orderByAsc(TenantPackageDO::getId));
    }

    /**
     * 获取租户套餐
     */
    @DS("slave")
    public TenantPackageDO getTenantPackage(Long id) {
        return tenantPackageMapper.selectById(id);
    }

    /**
     * 获取全部租户套餐（用于下拉框）
     */
    @DS("slave")
    public List<TenantPackageDO> getTenantPackageSimpleList() {
        return tenantPackageMapper.selectList(new LambdaQueryWrapper<TenantPackageDO>().orderByAsc(TenantPackageDO::getId));
    }

    /**
     * 校验租户套餐是否存在
     */
    @DS("slave")
    public void validTenantPackage(Long id) {
        if (id != null && tenantPackageMapper.selectById(id) == null) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.TENANT_PACKAGE_NOT_EXISTS);
        }
    }

    /**
     * 创建租户套餐
     */
    public Long createTenantPackage(TenantPackageSaveReqVO reqVO) {
        validateTenantPackageNameUnique(reqVO.getName(), null);
        TenantPackageDO tenantPackage = new TenantPackageDO();
        tenantPackage.setName(reqVO.getName());
        tenantPackage.setStatus(reqVO.getStatus());
        tenantPackage.setMenuIds(tech.qunxu.framework.common.util.json.JsonUtils.toJsonString(reqVO.getMenuIds()));
        tenantPackage.setRemark(reqVO.getRemark());
        tenantPackageMapper.insert(tenantPackage);
        return tenantPackage.getId();
    }

    /**
     * 更新租户套餐
     */
    public void updateTenantPackage(TenantPackageSaveReqVO reqVO) {
        validateTenantPackageExists(reqVO.getId());
        validateTenantPackageNameUnique(reqVO.getName(), reqVO.getId());
        TenantPackageDO tenantPackage = tenantPackageMapper.selectById(reqVO.getId());
        tenantPackage.setName(reqVO.getName());
        tenantPackage.setStatus(reqVO.getStatus());
        tenantPackage.setMenuIds(tech.qunxu.framework.common.util.json.JsonUtils.toJsonString(reqVO.getMenuIds()));
        tenantPackage.setRemark(reqVO.getRemark());
        tenantPackageMapper.updateById(tenantPackage);
    }

    /**
     * 删除租户套餐
     */
    public void deleteTenantPackage(Long id) {
        validateTenantPackageExists(id);
        // 套餐下存在租户不允许删除
        Long count = tenantMapper.selectCount(new LambdaQueryWrapper<TenantDO>().eq(TenantDO::getPackageId, id));
        if (count > 0) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.TENANT_PACKAGE_HAS_TENANT);
        }
        tenantPackageMapper.deleteById(id);
    }

    private void validateTenantPackageExists(Long id) {
        if (tenantPackageMapper.selectById(id) == null) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.TENANT_PACKAGE_NOT_EXISTS);
        }
    }

    private void validateTenantPackageNameUnique(String name, Long excludeId) {
        Long count = tenantPackageMapper.selectCount(new LambdaQueryWrapper<TenantPackageDO>()
                .eq(TenantPackageDO::getName, name)
                .ne(excludeId != null, TenantPackageDO::getId, excludeId));
        if (count > 0) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.TENANT_PACKAGE_NAME_EXISTS);
        }
    }

}
