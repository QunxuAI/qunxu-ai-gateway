package tech.qunxu.module.system.service.post;

import com.baomidou.dynamic.datasource.annotation.DS;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tech.qunxu.framework.common.exception.util.ServiceExceptionUtil;
import tech.qunxu.framework.common.pojo.PageResult;
import tech.qunxu.module.system.dal.dataobject.post.PostDO;
import tech.qunxu.module.system.dal.mysql.post.PostMapper;
import tech.qunxu.module.system.enums.ErrorCodeConstants;

import java.util.List;

/**
 * 岗位服务。
 */
@Service
@RequiredArgsConstructor
public class PostService {

    private final PostMapper postMapper;

    /**
     * 分页查询岗位
     */
    @DS("slave")
    public PageResult<PostDO> getPostPage(tech.qunxu.module.system.controller.admin.post.vo.PostPageReqVO reqVO) {
        return postMapper.selectPage(reqVO, new LambdaQueryWrapper<PostDO>()
                .like(StrUtil.isNotEmpty(reqVO.getName()), PostDO::getName, reqVO.getName())
                .like(StrUtil.isNotEmpty(reqVO.getCode()), PostDO::getCode, reqVO.getCode())
                .eq(reqVO.getStatus() != null, PostDO::getStatus, reqVO.getStatus())
                .orderByAsc(PostDO::getSort));
    }

    /**
     * 获取岗位
     */
    @DS("slave")
    public PostDO getPost(Long id) {
        return postMapper.selectById(id);
    }

    /**
     * 获取全部岗位（用于下拉框）
     */
    @DS("slave")
    public List<PostDO> getPostSimpleList() {
        return postMapper.selectList(new LambdaQueryWrapper<PostDO>().orderByAsc(PostDO::getSort));
    }

    /**
     * 创建岗位
     */
    public Long createPost(PostDO post) {
        validatePost(post);
        postMapper.insert(post);
        return post.getId();
    }

    /**
     * 更新岗位
     */
    public void updatePost(PostDO post) {
        validatePostExists(post.getId());
        validatePost(post);
        postMapper.updateById(post);
    }

    /**
     * 删除岗位
     */
    public void deletePost(Long id) {
        validatePostExists(id);
        postMapper.deleteById(id);
    }

    private void validatePost(PostDO post) {
        // 岗位编码唯一
        Long count = postMapper.selectCount(new LambdaQueryWrapper<PostDO>()
                .eq(PostDO::getCode, post.getCode())
                .ne(post.getId() != null, PostDO::getId, post.getId()));
        if (count > 0) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.POST_CODE_EXISTS);
        }
    }

    private void validatePostExists(Long id) {
        if (postMapper.selectById(id) == null) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.POST_NOT_EXISTS);
        }
    }

}
