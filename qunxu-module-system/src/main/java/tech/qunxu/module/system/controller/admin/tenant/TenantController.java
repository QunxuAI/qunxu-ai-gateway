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
import tech.qunxu.module.system.controller.admin.tenant.vo.TenantPageReqVO;
import tech.qunxu.module.system.controller.admin.tenant.vo.TenantRespVO;
import tech.qunxu.module.system.controller.admin.tenant.vo.TenantSaveReqVO;
import tech.qunxu.module.system.dal.dataobject.tenant.TenantDO;
import tech.qunxu.module.system.framework.operatelog.core.annotation.OperateLog;
import tech.qunxu.module.system.service.tenant.TenantService;

import java.util.List;

/**
 * 管理后台 - 租户接口。
 */
@Tag(name = "管理后台 - 租户")
@RestController
@RequestMapping("/admin-api/system/tenant")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @GetMapping("/page")
    @Operation(summary = "分页查询租户")
    @PreAuthorize("@ss.hasPermission('system:tenant:query')")
    public CommonResult<PageResult<TenantRespVO>> getTenantPage(TenantPageReqVO reqVO) {
        PageResult<TenantDO> pageResult = tenantService.getTenantPage(reqVO);
        List<TenantRespVO> vos = CollectionUtils.convertList(pageResult.getList(), TenantRespVO::from);
        return CommonResult.success(new PageResult<>(vos, pageResult.getTotal()));
    }

    @GetMapping("/get")
    @Operation(summary = "获取租户")
    @PreAuthorize("@ss.hasPermission('system:tenant:query')")
    public CommonResult<TenantRespVO> getTenant(@RequestParam("id") Long id) {
        return CommonResult.success(TenantRespVO.from(tenantService.getTenant(id)));
    }

    @PostMapping("/create")
    @Operation(summary = "创建租户")
    @PreAuthorize("@ss.hasPermission('system:tenant:create')")
    @OperateLog(module = "租户管理", name = "创建租户", type = "create")
    public CommonResult<Long> createTenant(@Valid @RequestBody TenantSaveReqVO reqVO) {
        return CommonResult.success(tenantService.createTenant(reqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新租户")
    @PreAuthorize("@ss.hasPermission('system:tenant:update')")
    @OperateLog(module = "租户管理", name = "更新租户", type = "update")
    public CommonResult<Boolean> updateTenant(@Valid @RequestBody TenantSaveReqVO reqVO) {
        tenantService.updateTenant(reqVO);
        return CommonResult.success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除租户")
    @PreAuthorize("@ss.hasPermission('system:tenant:delete')")
    @OperateLog(module = "租户管理", name = "删除租户", type = "delete")
    public CommonResult<Boolean> deleteTenant(@RequestParam("id") Long id) {
        tenantService.deleteTenant(id);
        return CommonResult.success(true);
    }

}
