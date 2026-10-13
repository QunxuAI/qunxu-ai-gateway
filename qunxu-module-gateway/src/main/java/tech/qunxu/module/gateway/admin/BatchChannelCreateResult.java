package tech.qunxu.module.gateway.admin;

import tech.qunxu.module.gateway.persistence.entity.LogicalModelEntity;
import tech.qunxu.module.gateway.persistence.entity.ModelChannelEntity;

import java.util.List;

/**
 * 批量创建通道结果。
 *
 * @param createdModels 本次自动创建的逻辑模型（已存在的复用，不在此列）
 * @param created       成功创建的通道
 * @param skipped       已存在而被跳过的模型名
 * @param total         本次请求的模型总数
 */
public record BatchChannelCreateResult(
        List<LogicalModelEntity> createdModels,
        List<ModelChannelEntity> created,
        List<String> skipped,
        int total) {
}
