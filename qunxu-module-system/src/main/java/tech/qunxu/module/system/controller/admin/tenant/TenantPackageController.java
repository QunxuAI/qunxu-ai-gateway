package tech.qunxu.module.system.controller.admin.tenant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.qunxu.framework.common.pojo.CommonResult;
import tech.qunxu.framework.common.pojo.PageResult;
import tech.qunxu.framework.common.util.collection.CollectionUtils;
import tech.qunxu.module.system.controller.admin.tenant.vo.TenantPackagePageReqVO;
import tech.qunxu.module.system.controller.admin.tenant.vo.TenantPackageRespVO;
import tech.qunxu.module.system.controller.admin.tenant.vo.TenantPackageSaveReqVO;
import tech.qunxu.module.system.dal.dataobject.tenant.TenantPackageDO;
import tech.qunxu.module.system.framework.operatelog.core.annotation.OperateLog;
import tech.qunxu.module.system.service.tenant.TenantPackageService;

import java.util.List;

/**
 * 管理后台 - 租户套餐接口。
 */
@Tag(name = "管理后台 - 租户套餐")
@RestController
@RequestMapping("/admin-api/system/tenant-package")
@RequiredArgsConstructor
public class TenantPackageController {

    private final TenantPackageService tenantPackageService;

    @GetMapping("/page")
    @Operation(summary = "分页查询租户套餐")
    @PreAuthorize("@ss.hasPermission('system:tenant-package:query')")
    public CommonResult<PageResult<TenantPackageRespVO>> getTenantPackagePage(TenantPackagePageReqVO reqVO) {
        PageResult<TenantPackageDO> pageResult = tenantPackageService.getTenantPackagePage(reqVO);
        List<TenantPackageRespVO> vos = CollectionUtils.convertList(pageResult.getList(), TenantPackageRespVO::from);
        return CommonResult.success(new PageResult<>(vos, pageResult.getTotal()));
    }

    @GetMapping("/get")
    @Operation(summary = "获取租户套餐")
    @PreAuthorize("@ss.hasPermission('system:tenant-package:query')")
    public CommonResult<TenantPackageRespVO> getTenantPackage(@RequestParam("id") Long id) {
        return CommonResult.success(TenantPackageRespVO.from(tenantPackageService.getTenantPackage(id)));
    }

    @GetMapping("/simple-list")
    @Operation(summary = "获取全部租户套餐（下拉框）")
    @PreAuthorize("@ss.hasPermission('system:tenant-package:query')")
    public CommonResult<List<TenantPackageRespVO>> getSimpleTenantPackageList() {
        List<TenantPackageRespVO> vos = CollectionUtils.convertList(
                tenantPackageService.getTenantPackageSimpleList(), TenantPackageRespVO::from);
        return CommonResult.success(vos);
    }

    @PostMapping("/create")
    @Operation(summary = "创建租户套餐")
    @PreAuthorize("@ss.hasPermission('system:tenant-package:create')")
    @OperateLog(module = "租户套餐管理", name = "创建租户套餐", type = "create")
    public CommonResult<Long> createTenantPackage(@Valid @RequestBody TenantPackageSaveReqVO reqVO) {
        return CommonResult.success(tenantPackageService.createTenantPackage(reqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新租户套餐")
    @PreAuthorize("@ss.hasPermission('system:tenant-package:update')")
    @OperateLog(module = "租户套餐管理", name = "更新租户套餐", type = "update")
    public CommonResult<Boolean> updateTenantPackage(@Valid @RequestBody TenantPackageSaveReqVO reqVO) {
        tenantPackageService.updateTenantPackage(reqVO);
        return CommonResult.success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除租户套餐")
    @PreAuthorize("@ss.hasPermission('system:tenant-package:delete')")
    @OperateLog(module = "租户套餐管理", name = "删除租户套餐", type = "delete")
    public CommonResult<Boolean> deleteTenantPackage(@RequestParam("id") Long id) {
        tenantPackageService.deleteTenantPackage(id);
        return CommonResult.success(true);
    }

}
