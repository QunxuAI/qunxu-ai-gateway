package tech.qunxu.module.system.controller.admin.dict;

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
import tech.qunxu.module.system.controller.admin.dict.vo.DictTypePageReqVO;
import tech.qunxu.module.system.controller.admin.dict.vo.DictTypeRespVO;
import tech.qunxu.module.system.controller.admin.dict.vo.DictTypeSaveReqVO;
import tech.qunxu.module.system.dal.dataobject.dict.DictTypeDO;
import tech.qunxu.module.system.framework.operatelog.core.annotation.OperateLog;
import tech.qunxu.module.system.service.dict.DictTypeService;

import java.util.List;

/**
 * 管理后台 - 字典类型接口。
 */
@Tag(name = "管理后台 - 字典类型")
@RestController
@RequestMapping("/admin-api/system/dict-type")
@RequiredArgsConstructor
public class DictTypeController {

    private final DictTypeService dictTypeService;

    @GetMapping("/page")
    @Operation(summary = "分页查询字典类型")
    @PreAuthorize("@ss.hasPermission('system:dict:query')")
    public CommonResult<PageResult<DictTypeRespVO>> getDictTypePage(DictTypePageReqVO reqVO) {
        PageResult<DictTypeDO> pageResult = dictTypeService.getDictTypePage(reqVO);
        List<DictTypeRespVO> vos = CollectionUtils.convertList(pageResult.getList(), DictTypeRespVO::from);
        return CommonResult.success(new PageResult<>(vos, pageResult.getTotal()));
    }

    @GetMapping("/get")
    @Operation(summary = "获取字典类型")
    @PreAuthorize("@ss.hasPermission('system:dict:query')")
    public CommonResult<DictTypeRespVO> getDictType(@RequestParam("id") Long id) {
        return CommonResult.success(DictTypeRespVO.from(dictTypeService.getDictType(id)));
    }

    @GetMapping("/simple-list")
    @Operation(summary = "获取全部字典类型（下拉框）")
    @PreAuthorize("@ss.hasPermission('system:dict:query')")
    public CommonResult<List<DictTypeRespVO>> getSimpleDictTypeList() {
        List<DictTypeRespVO> vos = CollectionUtils.convertList(dictTypeService.getDictTypeSimpleList(), DictTypeRespVO::from);
        return CommonResult.success(vos);
    }

    @PostMapping("/create")
    @Operation(summary = "创建字典类型")
    @PreAuthorize("@ss.hasPermission('system:dict:create')")
    @OperateLog(module = "字典管理", name = "创建字典类型", type = "create")
    public CommonResult<Long> createDictType(@Valid @RequestBody DictTypeSaveReqVO reqVO) {
        return CommonResult.success(dictTypeService.createDictType(reqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新字典类型")
    @PreAuthorize("@ss.hasPermission('system:dict:update')")
    @OperateLog(module = "字典管理", name = "更新字典类型", type = "update")
    public CommonResult<Boolean> updateDictType(@Valid @RequestBody DictTypeSaveReqVO reqVO) {
        dictTypeService.updateDictType(reqVO);
        return CommonResult.success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除字典类型")
    @PreAuthorize("@ss.hasPermission('system:dict:delete')")
    @OperateLog(module = "字典管理", name = "删除字典类型", type = "delete")
    public CommonResult<Boolean> deleteDictType(@RequestParam("id") Long id) {
        dictTypeService.deleteDictType(id);
        return CommonResult.success(true);
    }

}
