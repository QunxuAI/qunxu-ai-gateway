package tech.qunxu.module.system.controller.admin.post;

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
import tech.qunxu.module.system.controller.admin.post.vo.PostPageReqVO;
import tech.qunxu.module.system.controller.admin.post.vo.PostRespVO;
import tech.qunxu.module.system.controller.admin.post.vo.PostSaveReqVO;
import tech.qunxu.module.system.dal.dataobject.post.PostDO;
import tech.qunxu.module.system.framework.operatelog.core.annotation.OperateLog;
import tech.qunxu.module.system.service.post.PostService;

import java.util.List;

/**
 * 管理后台 - 岗位接口。
 */
@Tag(name = "管理后台 - 岗位")
@RestController
@RequestMapping("/admin-api/system/post")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    @GetMapping("/page")
    @Operation(summary = "分页查询岗位")
    @PreAuthorize("@ss.hasPermission('system:post:query')")
    public CommonResult<PageResult<PostRespVO>> getPostPage(PostPageReqVO reqVO) {
        PageResult<PostDO> pageResult = postService.getPostPage(reqVO);
        List<PostRespVO> vos = CollectionUtils.convertList(pageResult.getList(), PostRespVO::from);
        return CommonResult.success(new PageResult<>(vos, pageResult.getTotal()));
    }

    @GetMapping("/get")
    @Operation(summary = "获取岗位")
    @PreAuthorize("@ss.hasPermission('system:post:query')")
    public CommonResult<PostRespVO> getPost(@RequestParam("id") Long id) {
        return CommonResult.success(PostRespVO.from(postService.getPost(id)));
    }

    @GetMapping("/simple-list")
    @Operation(summary = "获取全部岗位（下拉框）")
    @PreAuthorize("@ss.hasPermission('system:post:query')")
    public CommonResult<List<PostRespVO>> getSimplePostList() {
        List<PostRespVO> vos = CollectionUtils.convertList(postService.getPostSimpleList(), PostRespVO::from);
        return CommonResult.success(vos);
    }

    @PostMapping("/create")
    @Operation(summary = "创建岗位")
    @PreAuthorize("@ss.hasPermission('system:post:create')")
    @OperateLog(module = "岗位管理", name = "创建岗位", type = "create")
    public CommonResult<Long> createPost(@Valid @RequestBody PostSaveReqVO reqVO) {
        PostDO post = new PostDO();
        post.setName(reqVO.getName());
        post.setCode(reqVO.getCode());
        post.setSort(reqVO.getSort());
        post.setStatus(reqVO.getStatus());
        post.setRemark(reqVO.getRemark());
        return CommonResult.success(postService.createPost(post));
    }

    @PutMapping("/update")
    @Operation(summary = "更新岗位")
    @PreAuthorize("@ss.hasPermission('system:post:update')")
    @OperateLog(module = "岗位管理", name = "更新岗位", type = "update")
    public CommonResult<Boolean> updatePost(@Valid @RequestBody PostSaveReqVO reqVO) {
        PostDO post = new PostDO();
        post.setId(reqVO.getId());
        post.setName(reqVO.getName());
        post.setCode(reqVO.getCode());
        post.setSort(reqVO.getSort());
        post.setStatus(reqVO.getStatus());
        post.setRemark(reqVO.getRemark());
        postService.updatePost(post);
        return CommonResult.success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除岗位")
    @PreAuthorize("@ss.hasPermission('system:post:delete')")
    @OperateLog(module = "岗位管理", name = "删除岗位", type = "delete")
    public CommonResult<Boolean> deletePost(@RequestParam("id") Long id) {
        postService.deletePost(id);
        return CommonResult.success(true);
    }

}
