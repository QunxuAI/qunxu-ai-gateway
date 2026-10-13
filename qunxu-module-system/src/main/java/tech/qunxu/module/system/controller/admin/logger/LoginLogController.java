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
import tech.qunxu.module.system.controller.admin.logger.vo.LoginLogPageReqVO;
import tech.qunxu.module.system.controller.admin.logger.vo.LoginLogRespVO;
import tech.qunxu.module.system.dal.dataobject.logger.LoginLogDO;
import tech.qunxu.module.system.service.logger.LoginLogService;

import java.util.List;

/**
 * 管理后台 - 登录日志接口。
 */
@Tag(name = "管理后台 - 登录日志")
@RestController
@RequestMapping("/admin-api/system/login-log")
@RequiredArgsConstructor
public class LoginLogController {

    private final LoginLogService loginLogService;

    @GetMapping("/page")
    @Operation(summary = "分页查询登录日志")
    @PreAuthorize("@ss.hasPermission('system:login-log:query')")
    public CommonResult<PageResult<LoginLogRespVO>> getLoginLogPage(LoginLogPageReqVO reqVO) {
        PageResult<LoginLogDO> pageResult = loginLogService.getLoginLogPage(reqVO);
        List<LoginLogRespVO> vos = CollectionUtils.convertList(pageResult.getList(), LoginLogRespVO::from);
        return CommonResult.success(new PageResult<>(vos, pageResult.getTotal()));
    }

    @GetMapping("/get")
    @Operation(summary = "获取登录日志")
    @PreAuthorize("@ss.hasPermission('system:login-log:query')")
    public CommonResult<LoginLogRespVO> getLoginLog(@RequestParam("id") Long id) {
        return CommonResult.success(LoginLogRespVO.from(loginLogService.getLoginLog(id)));
    }

}
