package tech.qunxu.module.system.service.notice;

import com.baomidou.dynamic.datasource.annotation.DS;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tech.qunxu.framework.common.exception.util.ServiceExceptionUtil;
import tech.qunxu.framework.common.pojo.PageResult;
import tech.qunxu.module.system.controller.admin.notice.vo.NoticePageReqVO;
import tech.qunxu.module.system.controller.admin.notice.vo.NoticeSaveReqVO;
import tech.qunxu.module.system.dal.dataobject.notice.NoticeDO;
import tech.qunxu.module.system.dal.mysql.notice.NoticeMapper;
import tech.qunxu.module.system.enums.ErrorCodeConstants;

/**
 * 通知公告服务。
 */
@Service
@RequiredArgsConstructor
public class NoticeService {

    private final NoticeMapper noticeMapper;

    /**
     * 分页查询通知公告
     */
    @DS("slave")
    public PageResult<NoticeDO> getNoticePage(NoticePageReqVO reqVO) {
        return noticeMapper.selectPage(reqVO, new LambdaQueryWrapper<NoticeDO>()
                .like(StrUtil.isNotEmpty(reqVO.getTitle()), NoticeDO::getTitle, reqVO.getTitle())
                .eq(reqVO.getType() != null, NoticeDO::getType, reqVO.getType())
                .eq(reqVO.getStatus() != null, NoticeDO::getStatus, reqVO.getStatus())
                .orderByDesc(NoticeDO::getId));
    }

    /**
     * 获取通知公告
     */
    @DS("slave")
    public NoticeDO getNotice(Long id) {
        return noticeMapper.selectById(id);
    }

    /**
     * 创建通知公告
     */
    public Long createNotice(NoticeSaveReqVO reqVO) {
        NoticeDO notice = new NoticeDO();
        notice.setTitle(reqVO.getTitle());
        notice.setContent(reqVO.getContent());
        notice.setType(reqVO.getType());
        notice.setStatus(reqVO.getStatus());
        noticeMapper.insert(notice);
        return notice.getId();
    }

    /**
     * 更新通知公告
     */
    public void updateNotice(NoticeSaveReqVO reqVO) {
        validateNoticeExists(reqVO.getId());
        NoticeDO notice = noticeMapper.selectById(reqVO.getId());
        notice.setTitle(reqVO.getTitle());
        notice.setContent(reqVO.getContent());
        notice.setType(reqVO.getType());
        notice.setStatus(reqVO.getStatus());
        noticeMapper.updateById(notice);
    }

    /**
     * 删除通知公告
     */
    public void deleteNotice(Long id) {
        validateNoticeExists(id);
        noticeMapper.deleteById(id);
    }

    private void validateNoticeExists(Long id) {
        if (noticeMapper.selectById(id) == null) {
            throw ServiceExceptionUtil.exception(ErrorCodeConstants.NOTICE_NOT_EXISTS);
        }
    }

}
