package tech.qunxu.module.gateway.common;

import tech.qunxu.module.gateway.common.enums.Capability;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型能力识别器单元测试：覆盖六种能力的常见命名约定、大小写不敏感与无法识别兜底。
 */
class CapabilityDetectorTest {

    @Test
    void detectsChatByDefault() {
        assertThat(CapabilityDetector.detect("gpt-4o")).isEqualTo(Capability.CHAT);
        assertThat(CapabilityDetector.detect("deepseek-chat")).isEqualTo(Capability.CHAT);
        assertThat(CapabilityDetector.detect("qwen-turbo")).isEqualTo(Capability.CHAT);
    }

    @Test
    void detectsEmbedding() {
        assertThat(CapabilityDetector.detect("text-embedding-3-small")).isEqualTo(Capability.EMBEDDING);
        assertThat(CapabilityDetector.detect("bge-m3")).isEqualTo(Capability.EMBEDDING);
        assertThat(CapabilityDetector.detect("text2vec-large")).isEqualTo(Capability.EMBEDDING);
        assertThat(CapabilityDetector.detect("m3e-base")).isEqualTo(Capability.EMBEDDING);
    }

    @Test
    void detectsRerank() {
        assertThat(CapabilityDetector.detect("bge-reranker-v2-m3")).isEqualTo(Capability.RERANK);
        assertThat(CapabilityDetector.detect("rerank-v1")).isEqualTo(Capability.RERANK);
    }

    @Test
    void detectsTts() {
        assertThat(CapabilityDetector.detect("tts-1")).isEqualTo(Capability.TTS);
        assertThat(CapabilityDetector.detect("cosyvoice-v1")).isEqualTo(Capability.TTS);
        assertThat(CapabilityDetector.detect("audio/speech")).isEqualTo(Capability.TTS);
    }

    @Test
    void detectsStt() {
        assertThat(CapabilityDetector.detect("whisper-1")).isEqualTo(Capability.STT);
        assertThat(CapabilityDetector.detect("audio/transcriptions")).isEqualTo(Capability.STT);
        assertThat(CapabilityDetector.detect("paraformer-v2")).isEqualTo(Capability.STT);
    }

    @Test
    void sttBeatsGenericSpeechKeyword() {
        // "speech" 同时出现在 TTS 关键词中，但 STT 命名（whisper/transcriptions）优先
        assertThat(CapabilityDetector.detect("whisper-speech-v1")).isEqualTo(Capability.STT);
    }

    @Test
    void detectsImage() {
        assertThat(CapabilityDetector.detect("dall-e-3")).isEqualTo(Capability.IMAGE);
        assertThat(CapabilityDetector.detect("flux-1-dev")).isEqualTo(Capability.IMAGE);
        assertThat(CapabilityDetector.detect("gpt-image-1")).isEqualTo(Capability.IMAGE);
        assertThat(CapabilityDetector.detect("cogview-3")).isEqualTo(Capability.IMAGE);
    }

    @Test
    void detectsImageSuffixAndWanFamily() {
        // 回归：wan2.7-image 的 "image" 在词尾（无后续连字符），此前漏判为 CHAT
        assertThat(CapabilityDetector.detect("wan2.7-image")).isEqualTo(Capability.IMAGE);
        assertThat(CapabilityDetector.detect("wan2.1-t2v-i2v")).isEqualTo(Capability.IMAGE);
        assertThat(CapabilityDetector.detect("wanx-2.1")).isEqualTo(Capability.IMAGE);
        assertThat(CapabilityDetector.detect("seedream-4.0")).isEqualTo(Capability.IMAGE);
        assertThat(CapabilityDetector.detect("ideogram-v2")).isEqualTo(Capability.IMAGE);
        assertThat(CapabilityDetector.detect("flux-1-image")).isEqualTo(Capability.IMAGE);
    }

    @Test
    void embeddingBeatsImageKeyword() {
        // 名字含 image 但实为向量模型：EMBEDDING 必须先于 IMAGE 匹配
        assertThat(CapabilityDetector.detect("text-image-embedding")).isEqualTo(Capability.EMBEDDING);
        assertThat(CapabilityDetector.detect("image-embedding-v1")).isEqualTo(Capability.EMBEDDING);
    }

    @Test
    void detectsCaseInsensitively() {
        assertThat(CapabilityDetector.detect("Text-Embedding-3-Small")).isEqualTo(Capability.EMBEDDING);
        assertThat(CapabilityDetector.detect("WHISPER-1")).isEqualTo(Capability.STT);
    }

    @Test
    void returnsNullForUnknown() {
        assertThat(CapabilityDetector.detect("")).isNull();
        assertThat(CapabilityDetector.detect(null)).isNull();
        assertThat(CapabilityDetector.detect("some-arbitrary-name")).isNull();
    }
}
