package tech.qunxu.module.gateway.admin;

import tech.qunxu.module.gateway.persistence.entity.ApiKeyEntity;

/**
 * 创建/轮换 APIKey 的一次性响应。
 *
 * <p>密钥明文仅在创建与轮换时返回一次，落库后（数据库与内存快照）只保存 SHA-256 摘要，
 * 任何查询接口都不会再回显明文。</p>
 *
 * @param key    落库后的 APIKey 实体（含摘要、前缀，不含明文）
 * @param apiKey 密钥明文（仅本次响应可见，请提示调用方立即保存）
 */
public record ApiKeyCreated(ApiKeyEntity key, String apiKey) {
}
