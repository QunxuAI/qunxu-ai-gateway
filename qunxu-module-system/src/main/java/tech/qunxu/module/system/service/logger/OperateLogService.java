package tech.qunxu.module.system.service.logger;

import com.baomidou.dynamic.datasource.annotation.DS;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tech.qunxu.framework.common.pojo.PageResult;
import tech.qunxu.module.system.controller.admin.logger.vo.OperateLogPageReqVO;
import tech.qunxu.module.system.dal.dataobject.logger.OperateLogDO;
import tech.qunxu.module.system.dal.mysql.logger.OperateLogMapper;

/**
 * 操作日志服务。
 */
@Service
@RequiredArgsConstructor
public class OperateLogService {

    private final OperateLogMapper operateLogMapper;

    /**
     * 记录操作日志
     */
    public void createOperateLog(OperateLogDO operateLog) {
        operateLogMapper.insert(operateLog);
    }

    /**
     * 分页查询操作日志
     */
    @DS("slave")
    public PageResult<OperateLogDO> getOperateLogPage(OperateLogPageReqVO reqVO) {
        return operateLogMapper.selectPage(reqVO, new LambdaQueryWrapper<OperateLogDO>()
                .like(StrUtil.isNotEmpty(reqVO.getModule()), OperateLogDO::getModule, reqVO.getModule())
                .like(StrUtil.isNotEmpty(reqVO.getName()), OperateLogDO::getName, reqVO.getName())
                .eq(reqVO.getUserId() != null, OperateLogDO::getUserId, reqVO.getUserId())
                .eq(reqVO.getResultCode() != null, OperateLogDO::getResultCode, reqVO.getResultCode())
                .orderByDesc(OperateLogDO::getId));
    }

    /**
     * 获取操作日志
     */
    @DS("slave")
    public OperateLogDO getOperateLog(Long id) {
        return operateLogMapper.selectById(id);
    }

}
