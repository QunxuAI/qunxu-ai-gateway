package tech.qunxu.module.system.controller.admin.dept;

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
import tech.qunxu.framework.common.util.collection.CollectionUtils;
import tech.qunxu.module.system.controller.admin.dept.vo.DeptRespVO;
import tech.qunxu.module.system.controller.admin.dept.vo.DeptSaveReqVO;
import tech.qunxu.module.system.dal.dataobject.dept.DeptDO;
import tech.qunxu.module.system.framework.operatelog.core.annotation.OperateLog;
import tech.qunxu.module.system.framework.util.TreeUtils;
import tech.qunxu.module.system.service.dept.DeptService;

import java.util.List;

/**
 * 管理后台 - 部门接口。
 */
@Tag(name = "管理后台 - 部门")
@RestController
@RequestMapping("/admin-api/system/dept")
@RequiredArgsConstructor
public class DeptController {

    private final DeptService deptService;

    @GetMapping("/list")
    @Operation(summary = "获取部门列表（树形结构）")
    @PreAuthorize("@ss.hasPermission('system:dept:query')")
    public CommonResult<List<DeptRespVO>> getDeptList() {
        List<DeptDO> depts = deptService.getDeptList();
        List<DeptRespVO> vos = CollectionUtils.convertList(depts, DeptRespVO::from);
        return CommonResult.success(TreeUtils.build(vos, DeptRespVO::getId, DeptRespVO::getParentId,
                DeptRespVO::getChildren, DeptRespVO::setChildren));
    }

    @GetMapping("/get")
    @Operation(summary = "获取部门")
    @PreAuthorize("@ss.hasPermission('system:dept:query')")
    public CommonResult<DeptRespVO> getDept(@RequestParam("id") Long id) {
        return CommonResult.success(DeptRespVO.from(deptService.getDept(id)));
    }

    @PostMapping("/create")
    @Operation(summary = "创建部门")
    @PreAuthorize("@ss.hasPermission('system:dept:create')")
    @OperateLog(module = "部门管理", name = "创建部门", type = "create")
    public CommonResult<Long> createDept(@Valid @RequestBody DeptSaveReqVO reqVO) {
        DeptDO dept = new DeptDO();
        dept.setName(reqVO.getName());
        dept.setParentId(reqVO.getParentId());
        dept.setSort(reqVO.getSort());
        dept.setLeaderUserId(reqVO.getLeaderUserId());
        dept.setPhone(reqVO.getPhone());
        dept.setEmail(reqVO.getEmail());
        dept.setStatus(reqVO.getStatus());
        return CommonResult.success(deptService.createDept(dept));
    }

    @PutMapping("/update")
    @Operation(summary = "更新部门")
    @PreAuthorize("@ss.hasPermission('system:dept:update')")
    @OperateLog(module = "部门管理", name = "更新部门", type = "update")
    public CommonResult<Boolean> updateDept(@Valid @RequestBody DeptSaveReqVO reqVO) {
        DeptDO dept = new DeptDO();
        dept.setId(reqVO.getId());
        dept.setName(reqVO.getName());
        dept.setParentId(reqVO.getParentId());
        dept.setSort(reqVO.getSort());
        dept.setLeaderUserId(reqVO.getLeaderUserId());
        dept.setPhone(reqVO.getPhone());
        dept.setEmail(reqVO.getEmail());
        dept.setStatus(reqVO.getStatus());
        deptService.updateDept(dept);
        return CommonResult.success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除部门")
    @PreAuthorize("@ss.hasPermission('system:dept:delete')")
    @OperateLog(module = "部门管理", name = "删除部门", type = "delete")
    public CommonResult<Boolean> deleteDept(@RequestParam("id") Long id) {
        deptService.deleteDept(id);
        return CommonResult.success(true);
    }

}
