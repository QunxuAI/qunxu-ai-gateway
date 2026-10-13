package tech.qunxu.module.system.controller.admin.logger;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.qunxu.framework.common.pojo.CommonResult;
import tech.qunxu.framework.common.pojo.PageResult;
import tech.qunxu.framework.common.util.collection.CollectionUtils;
import tech.qunxu.module.system.controller.admin.logger.vo.OperateLogPageReqVO;
import tech.qunxu.module.system.controller.admin.logger.vo.OperateLogRespVO;
import tech.qunxu.module.system.dal.dataobject.logger.OperateLogDO;
import tech.qunxu.module.system.service.logger.OperateLogService;

import java.util.List;

/**
 * 管理后台 - 操作日志接口。
 */
@Tag(name = "管理后台 - 操作日志")
@RestController
@RequestMapping("/admin-api/system/operate-log")
@RequiredArgsConstructor
public class OperateLogController {

    private final OperateLogService operateLogService;

    @GetMapping("/page")
    @Operation(summary = "分页查询操作日志")
    @PreAuthorize("@ss.hasPermission('system:operate-log:query')")
    public CommonResult<PageResult<OperateLogRespVO>> getOperateLogPage(OperateLogPageReqVO reqVO) {
        PageResult<OperateLogDO> pageResult = operateLogService.getOperateLogPage(reqVO);
        List<OperateLogRespVO> vos = CollectionUtils.convertList(pageResult.getList(), OperateLogRespVO::from);
        return CommonResult.success(new PageResult<>(vos, pageResult.getTotal()));
    }

    @GetMapping("/get")
    @Operation(summary = "获取操作日志")
    @PreAuthorize("@ss.hasPermission('system:operate-log:query')")
    public CommonResult<OperateLogRespVO> getOperateLog(@RequestParam("id") Long id) {
        return CommonResult.success(OperateLogRespVO.from(operateLogService.getOperateLog(id)));
    }

}
