package tech.qunxu.module.gateway.api.openai;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tech.qunxu.module.gateway.api.GatewayInvoker;
import tech.qunxu.module.gateway.common.enums.Capability;
import tech.qunxu.module.gateway.common.exception.ErrorCode;
import tech.qunxu.module.gateway.common.exception.GatewayException;
import tech.qunxu.module.gateway.common.model.UnifiedRequest;
import tech.qunxu.module.gateway.protocol.OpenAiProtocolHandler;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

import java.io.IOException;

/**
 * OpenAI 兼容语音端点：{@code POST /v1/audio/speech}（TTS，JSON 入、二进制音频出）与
 * {@code POST /v1/audio/transcriptions}（STT，multipart 音频入、JSON 文本出）。
 *
 * <p>TTS 走标准 JSON 入口，出向二进制音频由 {@link GatewayInvoker} 直接回写；STT 为 {@code multipart/form-data}
 * 上传，控制器自行读出音频字节并构造 Unified IR（音频字节不进入审计原文，仅记录概要），再委派编排器。</p>
 *
 * <p>STT 由 WebFlux 版 {@code MultipartFile}({@code FilePart}) 迁移为 Servlet 版
 * {@link org.springframework.web.multipart.MultipartFile}。</p>
 */
@RestController
@RequestMapping("/v1")
public class AudioController {

    static final String TTS_PATH = "/v1/audio/speech";
    static final String TTS_ENDPOINT = "audio.speech";
    static final String STT_PATH = "/v1/audio/transcriptions";
    static final String STT_ENDPOINT = "audio.transcriptions";

    private final GatewayInvoker invoker;
    private final OpenAiProtocolHandler handler;

    public AudioController(GatewayInvoker invoker, OpenAiProtocolHandler handler) {
        this.invoker = invoker;
        this.handler = handler;
    }

    @PostMapping("/audio/speech")
    public Mono<Void> speech(@RequestBody(required = false) String body,
                             HttpServletRequest request, HttpServletResponse response) {
        return invoker.invoke(request, response, handler, TTS_PATH, TTS_ENDPOINT, Capability.TTS, body);
    }

    @PostMapping(value = "/audio/transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Void> transcriptions(@RequestParam("model") String model,
                                     @RequestParam(value = "file") MultipartFile file,
                                     @RequestParam(value = "language", required = false) String language,
                                     @RequestParam(value = "response_format", required = false) String responseFormat,
                                     HttpServletRequest request, HttpServletResponse response) {
        byte[] audioBytes = readAudio(file);
        UnifiedRequest unified = UnifiedRequest.stt(model,
                new UnifiedRequest.SttPayload(audioBytes, file.getOriginalFilename(), language, responseFormat));
        return invoker.invokeWithRequest(request, response, handler, STT_PATH, STT_ENDPOINT,
                unified, auditSummary(unified));
    }

    private byte[] readAudio(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new GatewayException(ErrorCode.BAD_REQUEST, "缺少音频文件字段 file");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new GatewayException(ErrorCode.BAD_REQUEST, "读取音频文件失败", e);
        }
    }

    /** 审计概要：避免把音频二进制写入审计原文，仅保留模型/文件名/字节数/语言。 */
    private String auditSummary(UnifiedRequest request) {
        UnifiedRequest.SttPayload stt = request.stt();
        int bytes = stt != null && stt.audio() != null ? stt.audio().length : 0;
        StringBuilder sb = new StringBuilder("{\"model\":\"").append(request.model()).append('"');
        sb.append(",\"bytes\":").append(bytes);
        if (stt != null && stt.filename() != null) {
            sb.append(",\"file\":\"").append(sanitize(stt.filename())).append('"');
        }
        if (stt != null && stt.language() != null) {
            sb.append(",\"language\":\"").append(sanitize(stt.language())).append('"');
        }
        return sb.append('}').toString();
    }

    private String sanitize(String value) {
        return value.replace("\\", "").replace("\"", "");
    }
}
