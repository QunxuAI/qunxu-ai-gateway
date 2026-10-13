package tech.qunxu.module.gateway.common;

import tech.qunxu.module.gateway.common.enums.Capability;

import java.util.Locale;

/**
 * 模型能力识别器：按模型名启发式推断能力，供「批量上架」自动识别使用。
 *
 * <p>大部分 OpenAI 兼容供应商的 {@code GET /models} 只返回模型 id，不含能力信息，
 * 因此按常见命名约定推断：含 {@code embed/bge/m3e/gte/e5/text2vec} 的为向量化，
 * 含 {@code rerank/ranker/cross-encoder} 的为重排序，TTS/STT/IMAGE 同理，其余返回 {@code null} 由调用方兜底。</p>
 *
 * <p>规则按「更具体优先」排列：例如先匹配 {@code audio/transcriptions}（STT）再匹配 {@code audio/speech}（TTS），
 * 避免通用词 {@code speech} 把语音识别误判为语音合成；EMBEDDING 排在 IMAGE 之前，
 * 避免 {@code text-image-embedding} 这类「名字含 image 的向量模型」被误判为图片生成。</p>
 */
public final class CapabilityDetector {

    private CapabilityDetector() {
    }

    /**
     * 推断模型能力。
     *
     * @return 命中的能力；无法识别返回 {@code null}（由调用方决定兜底，通常为 CHAT）
     */
    public static Capability detect(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return null;
        }
        String name = modelName.trim().toLowerCase(Locale.ROOT);

        // STT 优先于 TTS：audio/transcriptions、asr、whisper、s2t 等
        if (containsAny(name, "audio/transcriptions", "audio/transcription", "/asr", "whisper", "s2t",
                "stt-", "transcrib", "paraformer", "speech-to-text", "语音识别")) {
            return Capability.STT;
        }
        // TTS：audio/speech、tts、voice、synthesis 等
        if (containsAny(name, "audio/speech", "audio/tts", "/tts", "tts-", "tts_", "cosyvoice", "voice",
                "speech-synthesis", "vits", "suno", "文字转语音")) {
            return Capability.TTS;
        }
        // RERANK
        if (containsAny(name, "rerank", "ranker", "cross-encoder", "bge-rerank")) {
            return Capability.RERANK;
        }
        // EMBEDDING：embed 一词覆盖 text-embedding / embedding 等。
        // 必须排在 IMAGE 之前：如 text-image-embedding 名字里含 image 但实为向量模型。
        if (containsAny(name, "embed", "bge-", "m3e", "gte-", "e5-", "text2vec", "text-vec", "向量")) {
            return Capability.EMBEDDING;
        }
        // IMAGE：dall-e、flux、sdxl、stable-diffusion、cogview、wanx/wan（万相）、seedream、ideogram 等；
        // 以及 -image/_image 或 image 结尾的命名（如 wan2.7-image）
        if (containsAny(name, "dall-e", "dalle", "flux", "sdxl", "stable-diffusion", "midjourney", "cogview",
                "wanx", "wan2", "wan-", "seedream", "ideogram", "recraft", "nano-banana",
                "painter", "image-generat", "text-to-image", "gpt-image", "imagen", "image-",
                "文生图", "图生图")
                || name.endsWith("image") || name.contains("_image")) {
            return Capability.IMAGE;
        }
        // CHAT：常见对话模型命名
        if (containsAny(name, "chat", "gpt", "llama", "claude", "glm", "kimi", "moonshot", "qwen",
                "deepseek", "gemini", "mistral", "minimax", "ernie", "doubao", "hunyuan", "spark",
                "yi-", "abab")) {
            return Capability.CHAT;
        }
        // 其余无法识别，由调用方兜底（通常按 CHAT 处理）
        return null;
    }

    private static boolean containsAny(String name, String... keywords) {
        for (String keyword : keywords) {
            if (name.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
