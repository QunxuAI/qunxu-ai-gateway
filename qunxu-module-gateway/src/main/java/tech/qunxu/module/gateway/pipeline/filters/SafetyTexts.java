package tech.qunxu.module.gateway.pipeline.filters;

import tech.qunxu.module.gateway.common.model.TokenUsage;
import tech.qunxu.module.gateway.common.model.UnifiedMessage;
import tech.qunxu.module.gateway.common.model.UnifiedRequest;
import tech.qunxu.module.gateway.common.model.UnifiedResponse;
import tech.qunxu.module.gateway.common.model.UnifiedStreamChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 安全过滤器共享的文本抽取/回填工具（纯函数）。
 *
 * <p>职责：①按能力从 Unified IR 聚合出<b>待校验文本</b>（输入=各能力提示文本，输出=面向用户的产出文本）；
 * ②在 MASK 裁决下以<b>保结构</b>方式逐段回填脱敏文本（保留角色/多模态片段/生成参数，不破坏下游语义）；
 * ③流式增量聚合与「聚合后重建」；④审计标记（围栏命中 / 内容审计裁决）的合并。供输入/输出的围栏与内容审计过滤器复用。</p>
 *
 * <p>非文本能力（如 STT 音频、TTS 音频字节、Embedding 向量）聚合为空串，过滤器据此跳过校验。</p>
 */
final class SafetyTexts {

    private SafetyTexts() {
    }

    // ---- 输入文本聚合 ----

    /** 按能力聚合请求中的待校验文本；非文本能力返回空串。 */
    static String inputText(UnifiedRequest req) {
        if (req == null || req.capability() == null) {
            return "";
        }
        return switch (req.capability()) {
            case CHAT -> req.chat() == null ? "" : req.chat().fullText();
            case EMBEDDING -> req.embedding() == null || req.embedding().input() == null
                    ? "" : String.join("\n", req.embedding().input());
            case RERANK -> rerankInputText(req.rerank());
            case TTS -> req.tts() == null || req.tts().input() == null ? "" : req.tts().input();
            case IMAGE -> req.image() == null || req.image().prompt() == null ? "" : req.image().prompt();
            case STT -> "";
        };
    }

    private static String rerankInputText(UnifiedRequest.RerankPayload r) {
        if (r == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (r.query() != null) {
            sb.append(r.query());
        }
        if (r.documents() != null) {
            for (String d : r.documents()) {
                if (d != null) {
                    sb.append('\n').append(d);
                }
            }
        }
        return sb.toString();
    }

    // ---- 输出文本聚合 ----

    /** 按能力聚合响应中面向用户的产出文本；无文本产出返回空串。 */
    static String outputText(UnifiedResponse resp) {
        if (resp == null || resp.capability() == null) {
            return "";
        }
        return switch (resp.capability()) {
            case CHAT -> {
                if (resp.choices() == null) {
                    yield "";
                }
                StringBuilder sb = new StringBuilder();
                for (UnifiedResponse.Choice c : resp.choices()) {
                    if (c != null) {
                        sb.append(c.text()).append('\n');
                    }
                }
                yield sb.toString();
            }
            case STT -> resp.audio() == null || resp.audio().text() == null ? "" : resp.audio().text();
            case IMAGE -> {
                if (resp.images() == null) {
                    yield "";
                }
                StringBuilder sb = new StringBuilder();
                for (UnifiedResponse.ImageResult im : resp.images()) {
                    if (im != null && im.revisedPrompt() != null) {
                        sb.append(im.revisedPrompt()).append('\n');
                    }
                }
                yield sb.toString();
            }
            default -> "";
        };
    }

    // ---- 输入 MASK 回填（保结构，逐段脱敏） ----

    /** 以 {@code masker} 逐段脱敏请求文本并重建请求，保留能力/模型/流式标记/其余载荷与参数。 */
    static UnifiedRequest maskInput(UnifiedRequest req, UnaryOperator<String> masker) {
        if (req == null || req.capability() == null) {
            return req;
        }
        return switch (req.capability()) {
            case CHAT -> req.chat() == null ? req : new UnifiedRequest(req.capability(), req.model(), req.stream(),
                    maskChat(req.chat(), masker), req.embedding(), req.rerank(), req.tts(), req.stt(), req.image(), req.extra());
            case EMBEDDING -> req.embedding() == null ? req : new UnifiedRequest(req.capability(), req.model(), req.stream(),
                    req.chat(), maskEmbedding(req.embedding(), masker), req.rerank(), req.tts(), req.stt(), req.image(), req.extra());
            case RERANK -> req.rerank() == null ? req : new UnifiedRequest(req.capability(), req.model(), req.stream(),
                    req.chat(), req.embedding(), maskRerank(req.rerank(), masker), req.tts(), req.stt(), req.image(), req.extra());
            case TTS -> req.tts() == null ? req : new UnifiedRequest(req.capability(), req.model(), req.stream(),
                    req.chat(), req.embedding(), req.rerank(), maskTts(req.tts(), masker), req.stt(), req.image(), req.extra());
            case IMAGE -> req.image() == null ? req : new UnifiedRequest(req.capability(), req.model(), req.stream(),
                    req.chat(), req.embedding(), req.rerank(), req.tts(), req.stt(), maskImage(req.image(), masker), req.extra());
            case STT -> req;
        };
    }

    private static UnifiedRequest.ChatPayload maskChat(UnifiedRequest.ChatPayload chat, UnaryOperator<String> masker) {
        List<UnifiedMessage> msgs = null;
        if (chat.messages() != null) {
            msgs = new ArrayList<>(chat.messages().size());
            for (UnifiedMessage m : chat.messages()) {
                msgs.add(m == null ? null : maskMessage(m, masker));
            }
        }
        return new UnifiedRequest.ChatPayload(msgs, chat.params(), chat.tools(), chat.toolChoice());
    }

    private static UnifiedMessage maskMessage(UnifiedMessage m, UnaryOperator<String> masker) {
        // 协议状态消息（思考块签名/加密续传数据）：原样保留，不做任何脱敏
        if (m.protocolProtected()) {
            return m;
        }
        String content = m.content() != null ? masker.apply(m.content()) : null;
        List<UnifiedMessage.ContentPart> parts = null;
        if (m.contentParts() != null) {
            parts = new ArrayList<>(m.contentParts().size());
            for (UnifiedMessage.ContentPart p : m.contentParts()) {
                if (p == null) {
                    continue;
                }
                if ("text".equals(p.type()) && p.text() != null) {
                    parts.add(UnifiedMessage.ContentPart.text(masker.apply(p.text())));
                } else {
                    parts.add(p);
                }
            }
        }
        return new UnifiedMessage(m.role(), content, parts, m.name(), m.toolCallId(), m.toolCalls(), false);
    }

    private static UnifiedRequest.EmbeddingPayload maskEmbedding(UnifiedRequest.EmbeddingPayload e, UnaryOperator<String> masker) {
        List<String> input = null;
        if (e.input() != null) {
            input = new ArrayList<>(e.input().size());
            for (String s : e.input()) {
                input.add(s == null ? null : masker.apply(s));
            }
        }
        return new UnifiedRequest.EmbeddingPayload(input, e.encodingFormat(), e.dimensions());
    }

    private static UnifiedRequest.RerankPayload maskRerank(UnifiedRequest.RerankPayload r, UnaryOperator<String> masker) {
        String query = r.query() == null ? null : masker.apply(r.query());
        List<String> docs = null;
        if (r.documents() != null) {
            docs = new ArrayList<>(r.documents().size());
            for (String d : r.documents()) {
                docs.add(d == null ? null : masker.apply(d));
            }
        }
        return new UnifiedRequest.RerankPayload(query, docs, r.topN(), r.returnDocuments());
    }

    private static UnifiedRequest.TtsPayload maskTts(UnifiedRequest.TtsPayload t, UnaryOperator<String> masker) {
        String input = t.input() == null ? null : masker.apply(t.input());
        return new UnifiedRequest.TtsPayload(input, t.voice(), t.responseFormat(), t.speed());
    }

    private static UnifiedRequest.ImagePayload maskImage(UnifiedRequest.ImagePayload im, UnaryOperator<String> masker) {
        String prompt = im.prompt() == null ? null : masker.apply(im.prompt());
        return new UnifiedRequest.ImagePayload(prompt, im.size(), im.n(), im.responseFormat(), im.quality(), im.style());
    }

    // ---- 输出 MASK 回填（保结构，逐段脱敏） ----

    /** 以 {@code masker} 逐段脱敏响应文本并重建响应，保留 id/model/created/用量与其余载荷。 */
    static UnifiedResponse maskOutput(UnifiedResponse resp, UnaryOperator<String> masker) {
        return transformOutput(resp, masker);
    }

    /** 以 {@code restorer} 逐段还原响应文本并重建响应（把输入围栏登记的占位符替换回原文）。 */
    static UnifiedResponse restoreOutput(UnifiedResponse resp, UnaryOperator<String> restorer) {
        return transformOutput(resp, restorer);
    }

    /** 通用逐段文本变换：脱敏与还原共用同一保结构回填骨架。 */
    private static UnifiedResponse transformOutput(UnifiedResponse resp, UnaryOperator<String> op) {
        if (resp == null || resp.capability() == null) {
            return resp;
        }
        return switch (resp.capability()) {
            case CHAT -> resp.choices() == null ? resp : new UnifiedResponse(resp.capability(), resp.id(), resp.model(),
                    resp.created(), maskChoices(resp.choices(), op), resp.embeddings(), resp.rerankResults(),
                    resp.images(), resp.audio(), resp.usage());
            case STT -> resp.audio() == null ? resp : new UnifiedResponse(resp.capability(), resp.id(), resp.model(),
                    resp.created(), resp.choices(), resp.embeddings(), resp.rerankResults(), resp.images(),
                    new UnifiedResponse.AudioResult(resp.audio().audio(),
                            resp.audio().text() == null ? null : op.apply(resp.audio().text()),
                            resp.audio().contentType()),
                    resp.usage());
            case IMAGE -> resp.images() == null ? resp : new UnifiedResponse(resp.capability(), resp.id(), resp.model(),
                    resp.created(), resp.choices(), resp.embeddings(), resp.rerankResults(),
                    maskImages(resp.images(), op), resp.audio(), resp.usage());
            default -> resp;
        };
    }

    private static List<UnifiedResponse.Choice> maskChoices(List<UnifiedResponse.Choice> choices, UnaryOperator<String> masker) {
        List<UnifiedResponse.Choice> out = new ArrayList<>(choices.size());
        for (UnifiedResponse.Choice c : choices) {
            if (c == null) {
                out.add(null);
                continue;
            }
            UnifiedMessage msg = c.message() == null ? null : maskMessage(c.message(), masker);
            out.add(new UnifiedResponse.Choice(c.index(), c.finishReason(), msg));
        }
        return out;
    }

    private static List<UnifiedResponse.ImageResult> maskImages(List<UnifiedResponse.ImageResult> images, UnaryOperator<String> masker) {
        List<UnifiedResponse.ImageResult> out = new ArrayList<>(images.size());
        for (UnifiedResponse.ImageResult im : images) {
            if (im == null) {
                out.add(null);
                continue;
            }
            out.add(new UnifiedResponse.ImageResult(im.url(), im.b64Json(),
                    im.revisedPrompt() == null ? null : masker.apply(im.revisedPrompt())));
        }
        return out;
    }

    // ---- 流式聚合与重建 ----

    /** 聚合流式增量正文（仅 {@code deltaContent}），供输出方向「聚合后校验」。 */
    static String aggregateContent(List<UnifiedStreamChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (UnifiedStreamChunk c : chunks) {
            if (c != null && c.hasContent()) {
                sb.append(c.deltaContent());
            }
        }
        return sb.toString();
    }

    /**
     * 流式 MASK 重建：以脱敏后的<b>聚合正文</b>替换原增量序列，产出「role → content(脱敏全文) → end」三帧，
     * 保留原 id/model/created、结束原因与 token 用量。牺牲增量平滑度以换取脱敏正确性（跨帧命中也能覆盖）。
     */
    static List<UnifiedStreamChunk> rebuildMaskedStream(List<UnifiedStreamChunk> chunks, String maskedText) {
        UnifiedStreamChunk first = chunks.get(0);
        String id = first.id();
        String model = first.model();
        long created = first.created();
        String finishReason = "stop";
        TokenUsage usage = null;
        for (UnifiedStreamChunk c : chunks) {
            if (c == null) {
                continue;
            }
            if (c.usage() != null) {
                usage = c.usage();
            }
            if (c.finishReason() != null) {
                finishReason = c.finishReason();
            }
        }
        List<UnifiedStreamChunk> out = new ArrayList<>(3);
        out.add(UnifiedStreamChunk.role(id, model, created, "assistant"));
        if (maskedText != null && !maskedText.isEmpty()) {
            out.add(UnifiedStreamChunk.content(id, model, created, maskedText));
        }
        out.add(UnifiedStreamChunk.end(id, model, created, finishReason, usage));
        return out;
    }

    // ---- 审计标记合并 ----

    /** 合并围栏命中标记：去空、去重后以逗号连接（输入与输出可能各自命中）。 */
    static String mergeHit(String existing, String add) {
        if (add == null || add.isBlank()) {
            return existing;
        }
        if (existing == null || existing.isBlank()) {
            return add;
        }
        if (existing.equals(add)) {
            return existing;
        }
        return existing + "," + add;
    }

    /** 合并内容审计裁决串：保留<b>更严重</b>者（BLOCK &gt; REVIEW &gt; PASS），使审计反映最强处置。 */
    static String mergeModeration(String existing, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return existing;
        }
        if (existing == null || existing.isBlank()) {
            return candidate;
        }
        return severity(candidate) > severity(existing) ? candidate : existing;
    }

    private static int severity(String s) {
        if (s == null) {
            return -1;
        }
        if (s.startsWith("BLOCK")) {
            return 2;
        }
        if (s.startsWith("REVIEW")) {
            return 1;
        }
        return 0;
    }
}
