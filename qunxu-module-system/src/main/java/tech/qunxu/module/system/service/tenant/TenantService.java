package tech.qunxu.module.system.service.tenant;

import com.baomidou.dynamic.datasource.annotation.DS;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tech.qunxu.framework.common.enums.CommonStatusEnum;
import tech.qunxu.framework.common.exception.util.ServiceExceptionUtil;
import tech.qunxu.framework.common.pojo.PageResult;
import tech.qunxu.module.system.controller.admin.tenant.vo.TenantPageReqVO;
import tech.qunxu.module.system.controller.admin.tenant.vo.TenantSaveReqVO;
import tech.qunxu.module.system.dal.dataobject.tenant.TenantDO;
import tech.qunxu.module.system.dal.mysql.tenant.TenantMapper;
import tech.qunxu.module.system.enums.ErrorCodeConstants;

import java.time.LocalDateTime;

/**
 * 租户服务。
 */
@Service
@RequiredArgsConstructor
public class TenantService {

    /** 系统内置租户编号 */
    public static final Long DEFAULT_TENANT_ID = 1L;

    private final TenantMapper tenantMapper;
    private final TenantPackageService tenantPackageService;

    /**
     * 分页查询租户
     */
    @DS("slave")
    public PageResult<TenantDO> getTenantPage(TenantPageReqVO reqVO) {
        return tenantMapper.selectPage(reqVO, new LambdaQueryWrapper<TenantDO>()
                .like(StrUtil.isNotEmpty(reqVO.getName()), TenantDO::getName, reqVO.getName())
                .eq(reqVO.getStatus() != null, TenantDO::getStatus, reqVO.getStatus())
                .orderByAsc(TenantDO::getId));
    }

    /**
     * 获取租户
     */
    @DS("slave")
    public TenantDO getTenant(Long id) {
        return tenantMapper.selectById(id);
    }

    /**
     * 校验租户是否存在且可用
     */
    @DS("slave")
    public void validTenant(Long id) {
        TenantDO tenant = tenantMapper.selectById(id);
        if (tenant == null) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.TENANT_NOT_EXISTS);
        }
        if (CommonStatusEnum.isDisable(tenant.getStatus())) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.TENANT_STATUS_DISABLE);
        }
        if (tenant.getExpireTime() != null && tenant.getExpireTime().isBefore(LocalDateTime.now())) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.TENANT_EXPIRE);
        }
    }

    /**
     * 创建租户
     */
    public Long createTenant(TenantSaveReqVO reqVO) {
        validateTenantNameUnique(reqVO.getName(), null);
        tenantPackageService.validTenantPackage(reqVO.getPackageId());
        TenantDO tenant = new TenantDO();
        tenant.setName(reqVO.getName());
        tenant.setContactName(reqVO.getContactName());
        tenant.setContactMobile(reqVO.getContactMobile());
        tenant.setContactEmail(reqVO.getContactEmail());
        tenant.setStatus(reqVO.getStatus() != null ? reqVO.getStatus() : CommonStatusEnum.ENABLE.getStatus());
        tenant.setPackageId(reqVO.getPackageId());
        tenant.setExpireTime(reqVO.getExpireTime());
        tenant.setAccountCount(reqVO.getAccountCount() != null ? reqVO.getAccountCount() : 10);
        tenant.setWebsite(reqVO.getWebsite());
        tenant.setRemark(reqVO.getRemark());
        tenantMapper.insert(tenant);
        return tenant.getId();
    }

    /**
     * 更新租户
     */
    public void updateTenant(TenantSaveReqVO reqVO) {
        validateTenantExists(reqVO.getId());
        validateTenantNameUnique(reqVO.getName(), reqVO.getId());
        tenantPackageService.validTenantPackage(reqVO.getPackageId());
        TenantDO tenant = tenantMapper.selectById(reqVO.getId());
        tenant.setName(reqVO.getName());
        tenant.setContactName(reqVO.getContactName());
        tenant.setContactMobile(reqVO.getContactMobile());
        tenant.setContactEmail(reqVO.getContactEmail());
        tenant.setStatus(reqVO.getStatus());
        tenant.setPackageId(reqVO.getPackageId());
        tenant.setExpireTime(reqVO.getExpireTime());
        tenant.setAccountCount(reqVO.getAccountCount());
        tenant.setWebsite(reqVO.getWebsite());
        tenant.setRemark(reqVO.getRemark());
        tenantMapper.updateById(tenant);
    }

    /**
     * 删除租户
     */
    public void deleteTenant(Long id) {
        validateTenantExists(id);
        // 系统内置租户不允许删除
        if (DEFAULT_TENANT_ID.equals(id)) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.TENANT_SYSTEM_CANNOT_DELETE);
        }
        tenantMapper.deleteById(id);
    }

    private void validateTenantExists(Long id) {
        if (tenantMapper.selectById(id) == null) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.TENANT_NOT_EXISTS);
        }
    }

    private void validateTenantNameUnique(String name, Long excludeId) {
        Long count = tenantMapper.selectCount(new LambdaQueryWrapper<TenantDO>()
                .eq(TenantDO::getName, name)
                .ne(excludeId != null, TenantDO::getId, excludeId));
        if (count > 0) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.TENANT_NAME_EXISTS);
        }
    }

}
