package tech.qunxu.module.gateway.common.model;

import java.util.List;

/**
 * 统一对话消息。适配 OpenAI 与 Anthropic 的消息模型，支持文本、多模态内容与工具调用。
 *
 * @param role        system / user / assistant / tool
 * @param content     纯文本内容（多模态时可为空，使用 {@link #contentParts}）
 * @param contentParts 多模态内容片段（文本 / 图片），可为空
 * @param name        可选的发送者名称
 * @param toolCallId  当 role=tool 时对应的工具调用 ID
 * @param toolCalls   当 role=assistant 时模型发起的工具调用
 * @param protocolProtected 是否为「协议状态」内容（思考块签名 / 加密续传数据等）：围栏与内容审计
 *                          排除此类消息（原样保留），避免改坏签名或续传密文（P1 协议感知保护）
 */
public record UnifiedMessage(
        String role,
        String content,
        List<ContentPart> contentParts,
        String name,
        String toolCallId,
        List<ToolCall> toolCalls,
        boolean protocolProtected) {

    public static UnifiedMessage system(String content) {
        return new UnifiedMessage("system", content, null, null, null, null, false);
    }

    public static UnifiedMessage user(String content) {
        return new UnifiedMessage("user", content, null, null, null, null, false);
    }

    public static UnifiedMessage assistant(String content) {
        return new UnifiedMessage("assistant", content, null, null, null, null, false);
    }

    /** 返回标记为协议状态（受保护）的副本；原消息不可变。 */
    public UnifiedMessage asProtocolProtected() {
        return new UnifiedMessage(role, content, contentParts, name, toolCallId, toolCalls, true);
    }

    /** 提取用于安全围栏/内容审计的纯文本（拼接文本内容与文本片段；协议状态消息返回空串）。 */
    public String textContent() {
        if (protocolProtected) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (content != null) {
            sb.append(content);
        }
        if (contentParts != null) {
            for (ContentPart part : contentParts) {
                if (part != null && "text".equals(part.type()) && part.text() != null) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(part.text());
                }
            }
        }
        return sb.toString();
    }

    /**
     * 多模态内容片段。
     *
     * @param type     "text" 或 "image_url"
     * @param text     文本内容（type=text）
     * @param imageUrl 图片地址（type=image_url，可为 data URL 或 http URL）
     */
    public record ContentPart(String type, String text, String imageUrl) {
        public static ContentPart text(String text) {
            return new ContentPart("text", text, null);
        }

        public static ContentPart imageUrl(String url) {
            return new ContentPart("image_url", null, url);
        }
    }

    /**
     * 工具调用。
     *
     * @param id       调用 ID
     * @param type     固定为 "function"
     * @param function 函数名与参数
     */
    public record ToolCall(String id, String type, ToolFunction function) {
    }

    /**
     * 工具函数。
     *
     * @param name      函数名
     * @param arguments JSON 字符串形式的参数
     */
    public record ToolFunction(String name, String arguments) {
    }
}
