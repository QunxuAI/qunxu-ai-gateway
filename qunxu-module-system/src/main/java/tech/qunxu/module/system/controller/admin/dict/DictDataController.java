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
import tech.qunxu.module.system.controller.admin.dict.vo.DictDataPageReqVO;
import tech.qunxu.module.system.controller.admin.dict.vo.DictDataRespVO;
import tech.qunxu.module.system.controller.admin.dict.vo.DictDataSaveReqVO;
import tech.qunxu.module.system.dal.dataobject.dict.DictDataDO;
import tech.qunxu.module.system.framework.operatelog.core.annotation.OperateLog;
import tech.qunxu.module.system.service.dict.DictDataService;

import java.util.List;

/**
 * 管理后台 - 字典数据接口。
 */
@Tag(name = "管理后台 - 字典数据")
@RestController
@RequestMapping("/admin-api/system/dict-data")
@RequiredArgsConstructor
public class DictDataController {

    private final DictDataService dictDataService;

    @GetMapping("/page")
    @Operation(summary = "分页查询字典数据")
    @PreAuthorize("@ss.hasPermission('system:dict:query')")
    public CommonResult<PageResult<DictDataRespVO>> getDictDataPage(DictDataPageReqVO reqVO) {
        PageResult<DictDataDO> pageResult = dictDataService.getDictDataPage(reqVO);
        List<DictDataRespVO> vos = CollectionUtils.convertList(pageResult.getList(), DictDataRespVO::from);
        return CommonResult.success(new PageResult<>(vos, pageResult.getTotal()));
    }

    @GetMapping("/get")
    @Operation(summary = "获取字典数据")
    @PreAuthorize("@ss.hasPermission('system:dict:query')")
    public CommonResult<DictDataRespVO> getDictData(@RequestParam("id") Long id) {
        return CommonResult.success(DictDataRespVO.from(dictDataService.getDictData(id)));
    }

    @GetMapping("/simple-list")
    @Operation(summary = "根据字典类型获取启用的字典数据列表")
    @PreAuthorize("@ss.hasPermission('system:dict:query')")
    public CommonResult<List<DictDataRespVO>> getSimpleDictDataList(@RequestParam("dictType") String dictType) {
        List<DictDataRespVO> vos = CollectionUtils.convertList(
                dictDataService.getDictDataListByDictType(dictType), DictDataRespVO::from);
        return CommonResult.success(vos);
    }

    @PostMapping("/create")
    @Operation(summary = "创建字典数据")
    @PreAuthorize("@ss.hasPermission('system:dict:create')")
    @OperateLog(module = "字典管理", name = "创建字典数据", type = "create")
    public CommonResult<Long> createDictData(@Valid @RequestBody DictDataSaveReqVO reqVO) {
        return CommonResult.success(dictDataService.createDictData(reqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新字典数据")
    @PreAuthorize("@ss.hasPermission('system:dict:update')")
    @OperateLog(module = "字典管理", name = "更新字典数据", type = "update")
    public CommonResult<Boolean> updateDictData(@Valid @RequestBody DictDataSaveReqVO reqVO) {
        dictDataService.updateDictData(reqVO);
        return CommonResult.success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除字典数据")
    @PreAuthorize("@ss.hasPermission('system:dict:delete')")
    @OperateLog(module = "字典管理", name = "删除字典数据", type = "delete")
    public CommonResult<Boolean> deleteDictData(@RequestParam("id") Long id) {
        dictDataService.deleteDictData(id);
        return CommonResult.success(true);
    }

}
