package tech.qunxu.module.gateway.pipeline.filters;

import tech.qunxu.module.gateway.common.enums.Capability;
import tech.qunxu.module.gateway.common.model.GenerationParams;
import tech.qunxu.module.gateway.common.model.TokenUsage;
import tech.qunxu.module.gateway.common.model.UnifiedMessage;
import tech.qunxu.module.gateway.common.model.UnifiedRequest;
import tech.qunxu.module.gateway.common.model.UnifiedResponse;
import tech.qunxu.module.gateway.common.model.UnifiedStreamChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SafetyTexts} 单元测试（M7d）：验证按能力的<b>待校验文本聚合</b>、MASK 时的<b>保结构回填</b>
 * （角色/多模态片段/生成参数/用量不丢失）、流式增量聚合与「聚合后重建」，以及审计标记合并语义。
 */
class SafetyTextsTest {

    /** 测试用脱敏器：把数字替换为 {@code *}。 */
    private static String mask(String s) {
        return s == null ? null : s.replaceAll("\\d", "*");
    }

    // ---- 输入文本聚合 ----

    @Test
    void inputTextForChatJoinsAllMessages() {
        UnifiedRequest req = UnifiedRequest.chat("m", false, new UnifiedRequest.ChatPayload(
                List.of(UnifiedMessage.system("你是助手"), UnifiedMessage.user("手机号 13800138000")),
                null, null, null));

        assertThat(SafetyTexts.inputText(req)).isEqualTo("你是助手\n手机号 13800138000\n");
    }

    @Test
    void inputTextForChatIncludesMultimodalTextParts() {
        UnifiedMessage msg = new UnifiedMessage("user", null,
                List.of(UnifiedMessage.ContentPart.text("看图 123"),
                        UnifiedMessage.ContentPart.imageUrl("http://img/1.png")),
                null, null, null, false);
        UnifiedRequest req = UnifiedRequest.chat("m", false,
                new UnifiedRequest.ChatPayload(List.of(msg), null, null, null));

        // 图片片段不参与文本校验
        assertThat(SafetyTexts.inputText(req)).isEqualTo("看图 123\n");
    }

    @Test
    void inputTextForEmbeddingJoinsInputs() {
        UnifiedRequest req = UnifiedRequest.embedding("m",
                new UnifiedRequest.EmbeddingPayload(List.of("甲", "乙"), "float", 8));

        assertThat(SafetyTexts.inputText(req)).isEqualTo("甲\n乙");
    }

    @Test
    void inputTextForRerankIncludesQueryAndDocuments() {
        UnifiedRequest req = UnifiedRequest.rerank("m",
                new UnifiedRequest.RerankPayload("查询 1", List.of("文档 2", "文档 3"), 3, true));

        assertThat(SafetyTexts.inputText(req)).isEqualTo("查询 1\n文档 2\n文档 3");
    }

    @Test
    void inputTextForTtsAndImage() {
        assertThat(SafetyTexts.inputText(UnifiedRequest.tts("m",
                new UnifiedRequest.TtsPayload("朗读 42", "voice", "mp3", 1.0)))).isEqualTo("朗读 42");
        assertThat(SafetyTexts.inputText(UnifiedRequest.image("m", false,
                new UnifiedRequest.ImagePayload("一只猫 2 只", "1024x1024", 1, "url", "standard", "vivid"))))
                .isEqualTo("一只猫 2 只");
    }

    @Test
    void inputTextForSttAndMissingPayloadsIsEmpty() {
        assertThat(SafetyTexts.inputText(UnifiedRequest.stt("m",
                new UnifiedRequest.SttPayload(new byte[]{1}, "a.mp3", "zh", "json")))).isEmpty();
        assertThat(SafetyTexts.inputText(null)).isEmpty();
        assertThat(SafetyTexts.inputText(new UnifiedRequest(null, "m", false,
                null, null, null, null, null, null, Map.of()))).isEmpty();
        // 能力已声明但载荷缺失（异常请求）不得抛错
        assertThat(SafetyTexts.inputText(UnifiedRequest.chat("m", false, null))).isEmpty();
        assertThat(SafetyTexts.inputText(UnifiedRequest.embedding("m", null))).isEmpty();
        assertThat(SafetyTexts.inputText(UnifiedRequest.rerank("m", null))).isEmpty();
        assertThat(SafetyTexts.inputText(UnifiedRequest.tts("m", null))).isEmpty();
        assertThat(SafetyTexts.inputText(UnifiedRequest.image("m", false, null))).isEmpty();
    }

    // ---- 输出文本聚合 ----

    @Test
    void outputTextForChatJoinsChoices() {
        UnifiedResponse resp = UnifiedResponse.chat("id", "m", 1L, "stop", "回答 42", TokenUsage.of(1, 2));

        assertThat(SafetyTexts.outputText(resp)).isEqualTo("回答 42\n");
    }

    @Test
    void outputTextForSttAndImage() {
        UnifiedResponse stt = new UnifiedResponse(Capability.STT, "id", "m", 1L, null, null, null, null,
                new UnifiedResponse.AudioResult(null, "识别文本 7", null), null);
        assertThat(SafetyTexts.outputText(stt)).isEqualTo("识别文本 7");

        UnifiedResponse image = new UnifiedResponse(Capability.IMAGE, "id", "m", 1L, null, null, null,
                List.of(new UnifiedResponse.ImageResult("http://img/1.png", null, "修订提示 9")), null, null);
        assertThat(SafetyTexts.outputText(image)).isEqualTo("修订提示 9\n");
    }

    @Test
    void outputTextForNonTextualCapabilitiesIsEmpty() {
        UnifiedResponse embedding = new UnifiedResponse(Capability.EMBEDDING, "id", "m", 1L, null,
                List.of(new UnifiedResponse.Embedding(0, List.of(0.1f))), null, null, null, null);
        assertThat(SafetyTexts.outputText(embedding)).isEmpty();

        UnifiedResponse tts = new UnifiedResponse(Capability.TTS, "id", "m", 1L, null, null, null, null,
                new UnifiedResponse.AudioResult(new byte[]{1, 2}, null, "audio/mpeg"), null);
        assertThat(SafetyTexts.outputText(tts)).isEmpty();

        assertThat(SafetyTexts.outputText(null)).isEmpty();
    }

    // ---- 输入 MASK 回填 ----

    @Test
    void maskInputChatPreservesStructure() {
        GenerationParams params = new GenerationParams(0.7, 0.9, 512, List.of("STOP"),
                null, null, 42, "json_object", true, Map.of("k", "v"));
        UnifiedRequest.ToolDefinition tool = new UnifiedRequest.ToolDefinition("function",
                new UnifiedRequest.ToolDefinition.FunctionDef("f", "d", Map.of()));
        UnifiedRequest req = new UnifiedRequest(Capability.CHAT, "model-x", true,
                new UnifiedRequest.ChatPayload(
                        List.of(UnifiedMessage.system("系统 1"), UnifiedMessage.user("用户 2")),
                        params, List.of(tool), "auto"),
                null, null, null, null, null, Map.of("extra", "keep"));

        UnifiedRequest masked = SafetyTexts.maskInput(req, SafetyTextsTest::mask);

        assertThat(masked.capability()).isEqualTo(Capability.CHAT);
        assertThat(masked.model()).isEqualTo("model-x");
        assertThat(masked.stream()).isTrue();
        assertThat(masked.extra()).containsEntry("extra", "keep");
        assertThat(masked.chat().params()).isSameAs(params);
        assertThat(masked.chat().tools()).containsExactly(tool);
        assertThat(masked.chat().toolChoice()).isEqualTo("auto");
        assertThat(masked.chat().messages()).extracting(UnifiedMessage::role)
                .containsExactly("system", "user");
        assertThat(masked.chat().messages()).extracting(UnifiedMessage::content)
                .containsExactly("系统 *", "用户 *");
    }

    @Test
    void maskInputChatMasksTextPartsAndKeepsImageParts() {
        UnifiedMessage msg = new UnifiedMessage("user", "正文 5",
                List.of(UnifiedMessage.ContentPart.text("片段 6"),
                        UnifiedMessage.ContentPart.imageUrl("http://img/7.png")),
                "alice", null, null, false);
        UnifiedRequest req = UnifiedRequest.chat("m", false,
                new UnifiedRequest.ChatPayload(List.of(msg), null, null, null));

        UnifiedMessage masked = SafetyTexts.maskInput(req, SafetyTextsTest::mask).chat().messages().get(0);

        assertThat(masked.role()).isEqualTo("user");
        assertThat(masked.name()).isEqualTo("alice");
        assertThat(masked.content()).isEqualTo("正文 *");
        assertThat(masked.contentParts().get(0).text()).isEqualTo("片段 *");
        // 非文本片段原样保留
        assertThat(masked.contentParts().get(1)).isSameAs(msg.contentParts().get(1));
        assertThat(masked.contentParts().get(1).imageUrl()).isEqualTo("http://img/7.png");
    }

    @Test
    void maskInputLeavesProtocolProtectedMessagesUntouched() {
        UnifiedMessage protectedMsg = UnifiedMessage.assistant("思考内容带签名").asProtocolProtected();
        UnifiedMessage normal = UnifiedMessage.user("普通消息");
        UnifiedRequest req = UnifiedRequest.chat("m", false,
                new UnifiedRequest.ChatPayload(List.of(protectedMsg, normal), null, null, null));

        UnifiedRequest masked = SafetyTexts.maskInput(req, SafetyTextsTest::mask);

        // 受保护消息原样保留（占位符/掩码都不触碰），普通消息正常脱敏
        assertThat(masked.chat().messages().get(0)).isSameAs(protectedMsg);
        assertThat(masked.chat().messages().get(0).content()).isEqualTo("思考内容带签名");
        assertThat(masked.chat().messages().get(1).content()).isEqualTo("普通消息");
    }

    @Test
    void inputTextExcludesProtocolProtectedMessages() {
        UnifiedMessage protectedMsg = UnifiedMessage.assistant("设未知数 x").asProtocolProtected();
        UnifiedRequest req = UnifiedRequest.chat("m", false,
                new UnifiedRequest.ChatPayload(List.of(protectedMsg, UnifiedMessage.user("解题")), null, null, null));

        assertThat(SafetyTexts.inputText(req))
                .doesNotContain("设未知数 x")
                .contains("解题");
    }

    @Test
    void maskInputForOtherCapabilities() {
        UnifiedRequest embedding = UnifiedRequest.embedding("m",
                new UnifiedRequest.EmbeddingPayload(List.of("a1", "b2"), "float", 4));
        UnifiedRequest maskedEmb = SafetyTexts.maskInput(embedding, SafetyTextsTest::mask);
        assertThat(maskedEmb.embedding().input()).containsExactly("a*", "b*");
        assertThat(maskedEmb.embedding().encodingFormat()).isEqualTo("float");
        assertThat(maskedEmb.embedding().dimensions()).isEqualTo(4);

        UnifiedRequest rerank = UnifiedRequest.rerank("m",
                new UnifiedRequest.RerankPayload("q1", List.of("d2"), 5, false));
        UnifiedRequest maskedRerank = SafetyTexts.maskInput(rerank, SafetyTextsTest::mask);
        assertThat(maskedRerank.rerank().query()).isEqualTo("q*");
        assertThat(maskedRerank.rerank().documents()).containsExactly("d*");
        assertThat(maskedRerank.rerank().topN()).isEqualTo(5);
        assertThat(maskedRerank.rerank().returnDocuments()).isFalse();

        UnifiedRequest tts = UnifiedRequest.tts("m", new UnifiedRequest.TtsPayload("t1", "v", "mp3", 1.5));
        UnifiedRequest maskedTts = SafetyTexts.maskInput(tts, SafetyTextsTest::mask);
        assertThat(maskedTts.tts().input()).isEqualTo("t*");
        assertThat(maskedTts.tts().voice()).isEqualTo("v");
        assertThat(maskedTts.tts().speed()).isEqualTo(1.5);

        UnifiedRequest image = UnifiedRequest.image("m", false,
                new UnifiedRequest.ImagePayload("p1", "512x512", 2, "b64_json", "hd", "natural"));
        UnifiedRequest maskedImage = SafetyTexts.maskInput(image, SafetyTextsTest::mask);
        assertThat(maskedImage.image().prompt()).isEqualTo("p*");
        assertThat(maskedImage.image().size()).isEqualTo("512x512");
        assertThat(maskedImage.image().n()).isEqualTo(2);
        assertThat(maskedImage.image().style()).isEqualTo("natural");
    }

    @Test
    void maskInputSttAndMissingPayloadsAreNoOps() {
        UnifiedRequest stt = UnifiedRequest.stt("m",
                new UnifiedRequest.SttPayload(new byte[]{1}, "a.mp3", "zh", "json"));
        assertThat(SafetyTexts.maskInput(stt, SafetyTextsTest::mask)).isSameAs(stt);
        assertThat(SafetyTexts.maskInput(null, SafetyTextsTest::mask)).isNull();
        assertThat(SafetyTexts.maskInput(UnifiedRequest.chat("m", false, null),
                SafetyTextsTest::mask).chat()).isNull();
    }

    // ---- 输出 MASK 回填 ----

    @Test
    void maskOutputChatPreservesEnvelope() {
        TokenUsage usage = TokenUsage.of(10, 5);
        UnifiedResponse resp = UnifiedResponse.chat("resp-1", "model-x", 1700000000L, "stop", "答案 42", usage);

        UnifiedResponse masked = SafetyTexts.maskOutput(resp, SafetyTextsTest::mask);

        assertThat(masked.capability()).isEqualTo(Capability.CHAT);
        assertThat(masked.id()).isEqualTo("resp-1");
        assertThat(masked.model()).isEqualTo("model-x");
        assertThat(masked.created()).isEqualTo(1700000000L);
        assertThat(masked.usage()).isSameAs(usage);
        assertThat(masked.choices()).hasSize(1);
        assertThat(masked.choices().get(0).index()).isZero();
        assertThat(masked.choices().get(0).finishReason()).isEqualTo("stop");
        assertThat(masked.choices().get(0).message().role()).isEqualTo("assistant");
        assertThat(masked.choices().get(0).message().content()).isEqualTo("答案 **");
    }

    @Test
    void maskOutputSttAndImage() {
        UnifiedResponse stt = new UnifiedResponse(Capability.STT, "id", "m", 1L, null, null, null, null,
                new UnifiedResponse.AudioResult(new byte[]{9}, "识别 3", "audio/mpeg"), null);
        UnifiedResponse maskedStt = SafetyTexts.maskOutput(stt, SafetyTextsTest::mask);
        assertThat(maskedStt.audio().text()).isEqualTo("识别 *");
        assertThat(maskedStt.audio().audio()).containsExactly(9);
        assertThat(maskedStt.audio().contentType()).isEqualTo("audio/mpeg");

        UnifiedResponse image = new UnifiedResponse(Capability.IMAGE, "id", "m", 1L, null, null, null,
                List.of(new UnifiedResponse.ImageResult("http://img/1.png", "b64", "修订 8")), null, null);
        UnifiedResponse maskedImage = SafetyTexts.maskOutput(image, SafetyTextsTest::mask);
        assertThat(maskedImage.images().get(0).revisedPrompt()).isEqualTo("修订 *");
        assertThat(maskedImage.images().get(0).url()).isEqualTo("http://img/1.png");
        assertThat(maskedImage.images().get(0).b64Json()).isEqualTo("b64");
    }

    @Test
    void maskOutputForNonTextualCapabilitiesIsNoOp() {
        UnifiedResponse embedding = new UnifiedResponse(Capability.EMBEDDING, "id", "m", 1L, null,
                List.of(new UnifiedResponse.Embedding(0, List.of(0.1f))), null, null, null, null);
        assertThat(SafetyTexts.maskOutput(embedding, SafetyTextsTest::mask)).isSameAs(embedding);
        assertThat(SafetyTexts.maskOutput(null, SafetyTextsTest::mask)).isNull();
    }

    // ---- 流式聚合与重建 ----

    @Test
    void aggregateContentConcatenatesDeltasOnly() {
        List<UnifiedStreamChunk> chunks = List.of(
                UnifiedStreamChunk.role("c1", "m", 1L, "assistant"),
                UnifiedStreamChunk.reasoning("c1", "m", 1L, "思考过程 1"),
                UnifiedStreamChunk.content("c1", "m", 1L, "你好"),
                UnifiedStreamChunk.content("c1", "m", 1L, "，世界 2"),
                UnifiedStreamChunk.end("c1", "m", 1L, "stop", TokenUsage.of(3, 4)));

        assertThat(SafetyTexts.aggregateContent(chunks)).isEqualTo("你好，世界 2");
        assertThat(SafetyTexts.aggregateContent(List.of())).isEmpty();
        assertThat(SafetyTexts.aggregateContent(null)).isEmpty();
    }

    @Test
    void rebuildMaskedStreamProducesRoleContentEndFrames() {
        TokenUsage usage = TokenUsage.of(3, 4);
        List<UnifiedStreamChunk> chunks = List.of(
                UnifiedStreamChunk.role("c1", "model-x", 1700000000L, "assistant"),
                UnifiedStreamChunk.content("c1", "model-x", 1700000000L, "手机号 "),
                UnifiedStreamChunk.content("c1", "model-x", 1700000000L, "13800138000"),
                UnifiedStreamChunk.end("c1", "model-x", 1700000000L, "length", usage));

        List<UnifiedStreamChunk> rebuilt =
                SafetyTexts.rebuildMaskedStream(chunks, "手机号 ***********");

        assertThat(rebuilt).hasSize(3);
        assertThat(rebuilt.get(0).role()).isEqualTo("assistant");
        assertThat(rebuilt.get(0).done()).isFalse();
        assertThat(rebuilt.get(1).deltaContent()).isEqualTo("手机号 ***********");
        assertThat(rebuilt.get(2).done()).isTrue();
        // 保留原信封与用量，结束原因取最后一个非空值
        assertThat(rebuilt.get(2).finishReason()).isEqualTo("length");
        assertThat(rebuilt.get(2).usage()).isSameAs(usage);
        for (UnifiedStreamChunk c : rebuilt) {
            assertThat(c.id()).isEqualTo("c1");
            assertThat(c.model()).isEqualTo("model-x");
            assertThat(c.created()).isEqualTo(1700000000L);
        }
    }

    @Test
    void rebuildMaskedStreamOmitsContentFrameWhenMaskedTextEmpty() {
        List<UnifiedStreamChunk> chunks = List.of(
                UnifiedStreamChunk.content("c1", "m", 1L, "***"),
                UnifiedStreamChunk.end("c1", "m", 1L, "stop", null));

        List<UnifiedStreamChunk> rebuilt = SafetyTexts.rebuildMaskedStream(chunks, "");

        assertThat(rebuilt).hasSize(2);
        assertThat(rebuilt.get(0).role()).isEqualTo("assistant");
        assertThat(rebuilt.get(1).done()).isTrue();
        assertThat(rebuilt.get(1).finishReason()).isEqualTo("stop");
    }

    @Test
    void rebuildMaskedStreamDefaultsFinishReasonToStop() {
        List<UnifiedStreamChunk> chunks = List.of(UnifiedStreamChunk.content("c1", "m", 1L, "x"));

        List<UnifiedStreamChunk> rebuilt = SafetyTexts.rebuildMaskedStream(chunks, "y");

        assertThat(rebuilt).hasSize(3);
        assertThat(rebuilt.get(2).finishReason()).isEqualTo("stop");
        assertThat(rebuilt.get(2).usage()).isNull();
    }

    // ---- 审计标记合并 ----

    @Test
    void mergeHitDeduplicatesAndJoins() {
        assertThat(SafetyTexts.mergeHit(null, "PII")).isEqualTo("PII");
        assertThat(SafetyTexts.mergeHit("PII", null)).isEqualTo("PII");
        assertThat(SafetyTexts.mergeHit("PII", "  ")).isEqualTo("PII");
        assertThat(SafetyTexts.mergeHit(null, null)).isNull();
        assertThat(SafetyTexts.mergeHit("PII", "PII")).isEqualTo("PII");
        assertThat(SafetyTexts.mergeHit("PII", "SENSITIVE_WORD")).isEqualTo("PII,SENSITIVE_WORD");
    }

    @Test
    void mergeModerationKeepsMoreSevereVerdict() {
        assertThat(SafetyTexts.mergeModeration(null, "PASS")).isEqualTo("PASS");
        assertThat(SafetyTexts.mergeModeration("PASS", null)).isEqualTo("PASS");
        assertThat(SafetyTexts.mergeModeration("PASS", "REVIEW:广告")).isEqualTo("REVIEW:广告");
        assertThat(SafetyTexts.mergeModeration("REVIEW:广告", "PASS")).isEqualTo("REVIEW:广告");
        assertThat(SafetyTexts.mergeModeration("REVIEW:广告", "BLOCK:色情")).isEqualTo("BLOCK:色情");
        assertThat(SafetyTexts.mergeModeration("BLOCK:色情", "REVIEW:广告")).isEqualTo("BLOCK:色情");
        assertThat(SafetyTexts.mergeModeration("BLOCK:色情", "")).isEqualTo("BLOCK:色情");
        assertThat(SafetyTexts.mergeModeration("", "REVIEW:广告")).isEqualTo("REVIEW:广告");
    }
}
