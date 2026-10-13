package tech.qunxu.module.gateway.admin;

/**
 * 供应商模型信息：模型 id 与自动识别的能力。
 *
 * @param id         上游模型标识（作为逻辑模型名与通道上游模型名）
 * @param capability 自动识别的能力（CHAT/EMBEDDING/RERANK/TTS/STT/IMAGE，无法识别时为 {@code null}）
 */
public record ProviderModelInfo(String id, String capability) {
}
