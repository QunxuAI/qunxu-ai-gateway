package tech.qunxu.module.system.controller.admin.notice;

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
import tech.qunxu.module.system.controller.admin.notice.vo.NoticePageReqVO;
import tech.qunxu.module.system.controller.admin.notice.vo.NoticeRespVO;
import tech.qunxu.module.system.controller.admin.notice.vo.NoticeSaveReqVO;
import tech.qunxu.module.system.dal.dataobject.notice.NoticeDO;
import tech.qunxu.module.system.framework.operatelog.core.annotation.OperateLog;
import tech.qunxu.module.system.service.notice.NoticeService;

import java.util.List;

/**
 * 管理后台 - 通知公告接口。
 */
@Tag(name = "管理后台 - 通知公告")
@RestController
@RequestMapping("/admin-api/system/notice")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService noticeService;

    @GetMapping("/page")
    @Operation(summary = "分页查询通知公告")
    @PreAuthorize("@ss.hasPermission('system:notice:query')")
    public CommonResult<PageResult<NoticeRespVO>> getNoticePage(NoticePageReqVO reqVO) {
        PageResult<NoticeDO> pageResult = noticeService.getNoticePage(reqVO);
        List<NoticeRespVO> vos = CollectionUtils.convertList(pageResult.getList(), NoticeRespVO::from);
        return CommonResult.success(new PageResult<>(vos, pageResult.getTotal()));
    }

    @GetMapping("/get")
    @Operation(summary = "获取通知公告")
    @PreAuthorize("@ss.hasPermission('system:notice:query')")
    public CommonResult<NoticeRespVO> getNotice(@RequestParam("id") Long id) {
        return CommonResult.success(NoticeRespVO.from(noticeService.getNotice(id)));
    }

    @PostMapping("/create")
    @Operation(summary = "创建通知公告")
    @PreAuthorize("@ss.hasPermission('system:notice:create')")
    @OperateLog(module = "通知公告管理", name = "创建通知公告", type = "create")
    public CommonResult<Long> createNotice(@Valid @RequestBody NoticeSaveReqVO reqVO) {
        return CommonResult.success(noticeService.createNotice(reqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新通知公告")
    @PreAuthorize("@ss.hasPermission('system:notice:update')")
    @OperateLog(module = "通知公告管理", name = "更新通知公告", type = "update")
    public CommonResult<Boolean> updateNotice(@Valid @RequestBody NoticeSaveReqVO reqVO) {
        noticeService.updateNotice(reqVO);
        return CommonResult.success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除通知公告")
    @PreAuthorize("@ss.hasPermission('system:notice:delete')")
    @OperateLog(module = "通知公告管理", name = "删除通知公告", type = "delete")
    public CommonResult<Boolean> deleteNotice(@RequestParam("id") Long id) {
        noticeService.deleteNotice(id);
        return CommonResult.success(true);
    }

}
