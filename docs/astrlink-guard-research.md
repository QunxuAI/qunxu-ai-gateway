# AstrLink Guard 研究：与 QunxuAI-Gateway 的可借鉴点

> 研究对象：AstrLink v0.1.0（[GitHub](https://github.com/Calcium-Ion/AstrLink)）及其本地隐私检测模型
> [AstrLink Guard](https://huggingface.co/QuantumNous/astrlink-guard)（QuantumNous）。
> 对照系统：`QunxuAI-Gateway`（下文简称「本网关」）。
> 结论先行：AstrLink 与我们是**同一条「AI 网关」赛道、不同部署形态**（本地桌面单用户 vs 中心化多租户服务），
> 它的安全面核心是**「出门前本地脱敏 + 返回时本地还原」的可逆隐私管线**，这正是本网关目前最缺、也最值得借鉴的一块。

> **实现状态**：P0（占位符脱敏 + 响应还原）、P1（协议感知保护）、P2 会话级供应商粘性、P3 协议面扩展（Gemini / Responses 入向）已在本仓库落地并随 `mvn install` 全量 660 测试通过；P3 的「订阅聚合」形态（Codex/Claude/Grok 登录授权）未实现，见 §3.7 说明。

---

## 1. AstrLink / AstrLink Guard 是什么

- **AstrLink**：开源本地 AI 网关桌面应用（macOS/Win/Linux）。统一接入各家订阅与 API（Codex、Claude、Grok、New API 兼容网关、主流按量 API），对外暴露 OpenAI Responses / Chat Completions / Anthropic Messages / Gemini 多协议端点，内置模型路由、供应商优先级、重试/故障转移、请求记录与用量成本追踪。
- **AstrLink Guard**：配套的**本地隐私检测模型**（Hugging Face，ONNX 权重，应用内下载/本地导入，中英双语、针对 prompt 与代码调优）。与内置/自定义规则共同完成敏感内容识别。
- **核心隐私链路**（对应用最重要）：
  1. **本机检测**：规则或本地模型识别密钥、邮箱、手机号等敏感内容，全程不出本机；
  2. **策略动作**：warn / block / **redact（脱敏）**，脱敏用**占位符**替换；
  3. **响应还原**：模型返回的占位符在本地**替换回原值**再给客户端 → agent 能继续正常工作（真名实姓只留在自己机器上）；
  4. **dry run 试运行**：上线策略前先试跑看效果。
- 另有一个很细但很重要的设计：**协议感知的续传/思考块保护** —— 对 OpenAI Responses 的 `encrypted_content`、Anthropic 思考块 `signature`、Gemini `thoughtSignature`、OpenRouter `reasoning.*` 等**按协议结构识别并排除出检测范围**，原样转发，避免改几个字符就导致上游 `invalid_encrypted_content` / 签名校验失败。

参考：[GitHub 仓库](https://github.com/Calcium-Ion/AstrLink) ·
[隐私保护说明](https://github.com/Calcium-Ion/AstrLink) ·
[思考与续传兼容文档](https://github.com/Calcium-Ion/AstrLink/blob/main/docs/guides/privacy-reasoning-continuation.md) ·
[桌面设置（隐私模型/诊断）](https://github.com/Calcium-Ion/AstrLink/blob/main/apps/desktop/README.md) ·
[AstrLink Guard 模型页](https://huggingface.co/QuantumNous/astrlink-guard) ·
[介绍帖（aiproducthub）](https://aiproducthub.cn/status/62124.html)

---

## 2. 能力对照表

| 能力 | AstrLink | 本网关（QunxuAI-Gateway） | 差距 |
| --- | --- | --- | --- |
| 输入安全围栏 | 规则/本地模型检测，warn/block/redact | 规则链（长度/PII/提示注入/正则/敏感词/主题），BLOCK 短路、MASK 等长 `*` 脱敏（`GuardrailEngine`） | 无占位符还原 |
| **脱敏方式** | **占位符 + 本地响应还原（可逆）** | 等长 `*` 掩码（`Masking`，[Masking.java:26-44](qunxu-module-gateway/src/main/java/tech/qunxu/module/gateway/guardrail/Masking.java)），**不可逆、不还原** | ★ 最大差距 |
| 输出围栏 | 同输入（redact 后可还原） | MASK 等长脱敏后回填 / 流式重建帧（[OutputGuardrailFilter.java:92-95](qunxu-module-gateway/src/main/java/tech/qunxu/module/gateway/pipeline/filters/OutputGuardrailFilter.java)、[:118-120](qunxu-module-gateway/src/main/java/tech/qunxu/module/gateway/pipeline/filters/OutputGuardrailFilter.java)） | 无还原 |
| 内容审计 | — | 内置关键词 / 阿里云绿网 / **远程 LLM 裁判**（`LlmJudgeModerationProvider`） | 远程裁判会把文本发到第三方（隐私面相反） |
| **检测模型形态** | **本地 ONNX 隐私模型，零数据出境** | 正则 PII（[PiiRule.java:28-31](qunxu-module-gateway/src/main/java/tech/qunxu/module/gateway/guardrail/rules/PiiRule.java)）+ 敏感词 Trie + 云/远程 LLM | 无本地语义级隐私检测 |
| 失败语义 | 隐私模型不可用时**拒绝请求（fail-closed）** | LLM 裁判失败**放行（fail-open）**（[LlmJudgeModerationProvider.java:86-90](qunxu-module-gateway/src/main/java/tech/qunxu/module/gateway/moderation/LlmJudgeModerationProvider.java)） | 语义不同，需按场景区分 |
| **协议感知保护** | 按协议结构排除思考签名/加密续传数据 | 思考内容原样透传（[OpenAiProtocolHandler.java:31](qunxu-module-gateway/src/main/java/tech/qunxu/module/gateway/protocol/OpenAiProtocolHandler.java)、[:468](qunxu-module-gateway/src/main/java/tech/qunxu/module/gateway/protocol/OpenAiProtocolHandler.java)），但围栏**无协议位置感知** | ★ 技术含量高、实用 |
| 策略试运行 | dry run | 无（仅健康探测） | 可加 |
| 路由 | 模型重定向 + 供应商优先级 + 重试 + **会话内粘性** | 优先级 / 权重轮询 / 随机 / 最近延迟（`router/lb/*`），无显式重试与粘性 | 多轮会话续接/缓存有隐患 |
| 观测 | 请求记录 + **上游尝试级 trace** + 按 provider/model/token 成本估算 | 调用审计（批写、敏感字段掩码）+ 按 callerId 用量统计 | 缺尝试级与成本估算 |
| 协议面 | Responses / Chat / Anthropic / **Gemini** + 按上游能力做协议转换 | OpenAI / Anthropic | 缺 Gemini / Responses |
| 鉴权/配额 | 每 agent 独立 token + 用量 | 静态 token / 登录用户 + 限流 + Token 配额（`gateway.quota.*`） | 已基本对齐 |

部署形态提醒：AstrLink 是**单机本地应用**（网关、检测、脱敏全在本机），本网关是**中心化多租户服务**。
因此「本地/本机检测」落在我们架构里应演化为「**sidecar / 独立检测服务或 worker 内嵌本地模型**」，
核心价值是**「检测与脱敏不把敏感文本发往第三方」**——这条思路在任何部署形态下都成立。

---

## 3. 可借鉴点（按优先级）

### P0 — 占位符脱敏 + 响应本地还原（最大差距，最值得先做）

**现状**：本网关输入/输出围栏的 MASK 是把命中区间替换为等长 `*`（[Masking.java](qunxu-module-gateway/src/main/java/tech/qunxu/module/gateway/guardrail/Masking.java)）。
`*` 数量能保结构，但：
- 模型读到的是一串无意义的 `*`，上下文信息被破坏（尤其是 PII 脱敏后，模型经常「接不住」被挖掉的槽位）；
- 响应里的 `*` 无法映射回原值，用户拿到的还是脱敏件，agent 无法继续引用真实数据。

**AstrLink 做法**：`{{EMAIL_1}}`、`{{PHONE_2}}` 这类**占位符**替换 → 模型基于占位符正常推理 → 返回结果中的占位符在本地替换回原值再交付。

**借鉴落点（改动可控，不动现有规则链）**：
1. 新增 `PlaceholderMasking`（替代/并存于 `Masking`）：把命中区间替换为按类型自增的 `{{TYPE_n}}`，同时产出 `Map<占位符, 原文>` 映射（存请求上下文，不落审计日志，避免二次泄露）；
2. 输入侧：`InputGuardrailFilter` 用占位符替换后下行，映射挂到 `RequestContext`；
3. 输出侧：`OutputGuardrailFilter` / 流式重建帧时，先做输出围栏（防模型输出违规），再把**输入阶段建立的映射**反查替换回原文；未匹配的模型自造占位符原样保留；
4. 配置开关：`guardrail.mask.mode = star | placeholder`，默认 `star` 保持现状，`placeholder` 为可逆隐私模式——兼顾「内容审计脱敏」与「隐私可逆脱敏」两种诉求。

### P1 — 协议感知的续传/思考块保护（技术含量高、直接防线上事故）

**现状**：`OpenAiProtocolHandler` 对 DeepSeek-R1 等 `reasoning_content` 原样透传（[OpenAiProtocolHandler.java:31](qunxu-module-gateway/src/main/java/tech/qunxu/module/gateway/protocol/OpenAiProtocolHandler.java)），Anthropic thinking 增量在 M6 完善（[AnthropicProtocolHandler.java:33](qunxu-module-gateway/src/main/java/tech/qunxu/module/gateway/protocol/AnthropicProtocolHandler.java)）。但**围栏是按纯文本跑的**：一旦某条规则（如通用正则/主题规则）命中 thinking 内容并 MASK/BLOCK，可能改坏：
- Anthropic 思考块必须**签名 + 全文完整**回传，否则上游 400；
- OpenAI Responses `encrypted_content`、Gemini `thoughtSignature`、OpenRouter `reasoning.encrypted` 属协议状态，改几个字符即失败。

**AstrLink 做法**：按**协议结构位置、角色、类型**识别受保护字段（不是按字段名），在检测前排除、原样保留；同时「用户消息里粘贴同名 JSON」仍照常检测（防绕过）。

**借鉴落点**：
1. 在 `UnifiedRequest`/`UnifiedResponse` 里给内容片段标注 `protected-by-protocol: boolean`（解析层对 thinking/signature/encrypted 片段打标）；
2. `GuardrailContext` 增加按片段跳过语义：命中打标片段的规则（尤其 MASK/BLOCK）跳过，只对普通正文生效；
3. 优先覆盖当前已支持的 OpenAI（`reasoning_content`）+ Anthropic（`thinking`/`redacted_thinking`），再扩展 OpenRouter/Gemini 兼容格式；
4. 这是**纯后端、无前端改动**的点，且能直接避免「开启围栏后多轮推理对话莫名 400」的线上事故。

### P1 — 本地语义级隐私检测模型（把「隐私」从规则升级为可不出境的 AI 检测）

**现状**：PII 靠正则（[PiiRule.java:28-31](qunxu-module-gateway/src/main/java/tech/qunxu/module/gateway/guardrail/rules/PiiRule.java)），只覆盖固定格式；语义级审核走 `LlmJudgeModerationProvider`，但它是**把文本发到第三方 LLM**——对「隐私脱敏」这个目标而言方向相反。

**借鉴落点**：
1. 复用现成的 `ModerationProvider` 抽象（`BuiltinKeyword → AliyunGreen → LlmJudge` 已是 provider 链），**新增一个 `LocalOrtModerationProvider` / `GuardrailRule`**：加载 ONNX 模型（如直接集成 [AstrLink Guard](https://huggingface.co/QuantumNous/astrlink-guard)，Apache-2.0 可再分发），本地跑推理识别密钥/邮箱/手机号/姓名等，**文本不出内网**；
2. 生产环境形态：中心化部署下建议独立检测服务或 sidecar（挂 `/v1/guard/scan`），避免把模型推理塞进网关请求线程；规则兜底 + 模型增强，模型未就绪时降级到规则（**但隐私场景建议 fail-closed**，见下条）；
3. 与 P0 打通：模型产出的命中区间直接喂给占位符脱敏模块。

### P2 — 安全策略「试运行（dry run）」

**现状**：围栏/审计策略只能上线后看线上命中，无预演手段。

**借鉴落点**：给 `GuardrailPolicyService` / `ModerationPolicyService` 加 `dryRun=true` 参数：只评估、记录命中和（脱敏预览后的）结果，不修改请求、不阻断、不落正式审计（落 dry-run 单独视图）。对「P0 占位符模式」「本地模型上线」这种高风险变更尤其有用，能先看效果再全量。可与现有 `/admin-api/gateway/health` probe 同一管理面暴露。

### P2 — 会话级供应商粘性（session stickiness + 绑定审计）

**现状**：路由仅按请求做优先级/权重选择（`router/lb/*`），无会话内粘性。多轮对话若在 provider 间漂移，Anthropic 续传数据 / 服务商侧缓存 / 上下文压缩会话可能失效。

**借鉴落点**：以 `callerId + logicalModel + conversationId`（或客户端回传的 `x-session-id`）为键做会话级粘性路由：首轮按权重选，会话内复用同一 provider；支持手动重新绑定；审计里记录「绑定/重选」事件。实现上在 `RoutingService` 前加一层 `StickyRouter`，Redis 存绑定、TTL 可配。

**✅ 已实现（本迭代）**：[StickyRouter](qunxu-module-gateway/src/main/java/tech/qunxu/module/gateway/router/StickyRouter.java)
（Redis 绑定 `gw:sticky:{modelId}:{callerId}:{sessionId}`，TTL 默认 1h 命中续期，Redis 异常按 `gateway.sticky.fail-open` 降级普通路由）、
[RoutingService](qunxu-module-gateway/src/main/java/tech/qunxu/module/gateway/router/RoutingService.java)
新增偏好通道路由（命中复用 / 失效回退负载均衡）、[RouteFilter](qunxu-module-gateway/src/main/java/tech/qunxu/module/gateway/pipeline/filters/RouteFilter.java)
集成粘性分支（首轮绑定 / 命中续期 / 失效自动重绑），会话 ID 由 [AuthFilter](qunxu-module-gateway/src/main/java/tech/qunxu/module/gateway/pipeline/filters/AuthFilter.java)
从可配置请求头（默认 `X-Session-Id`）解析；绑定/重选事件以 `gw.sticky.event` 属性 + INFO 日志记录（traceId 可关联审计）。
「支持手动重新绑定（管理面 API）」未实现；审计表落库绑定事件需加 Flyway 迁移列，暂以日志 + 上下文标记承载。

### P2 — 上游尝试级请求记录 + 成本估算

**现状**：审计记录单条调用（tokens/latency/status/provider_detail），无「重试/故障转移后的多次上游尝试」与成本。

**借鉴落点**：
1. 审计实体增加 `attempts` 子表/字段：每次上游尝试（endpoint、status、耗时、error）可展开，故障定位从「这条调用失败了」升级为「第几次尝试、打给谁、为何失败」；
2. 在 `TokenUsageStat`/`UsageDimension` 基础上增加 `providerId`、`logicalModel` 维度的**成本估算**（单价表 + tokens → 预估成本，仅参考、上游账单为准——沿用 AstrLink 的「参考不背书」口径）。

### P3 — 协议面扩展（Gemini / Responses）与「订阅聚合」形态

- ✅ **已实现（本迭代）**：新增 [GeminiProtocolHandler](qunxu-module-gateway/src/main/java/tech/qunxu/module/gateway/protocol/GeminiProtocolHandler.java)
  （`/v1beta/models/{model}:generateContent` + `:streamGenerateContent`，SSE）与
  [ResponsesProtocolHandler](qunxu-module-gateway/src/main/java/tech/qunxu/module/gateway/protocol/ResponsesProtocolHandler.java)
  （`/v1/responses`，支持 message / string / function_call_output / function_call / reasoning 条目，SSE 事件序列），
  均复用统一流水线路由到既有上游通道；`/v1beta/**` 已加入免登录放行。Responses 的 `reasoning` 条目同时受益于 P1 协议感知保护。
- ⏳ **未实现（需按供应商逐一开发，建议单独立项）**：「订阅聚合」形态（Codex / Claude / Grok / Coding Plan 订阅登录授权）。
  它依赖各家专属鉴权流程（如 Grok 的 device-code 登录、订阅凭据刷新），无法泛化编码；本网关已有 provider + model_channel 抽象，
  后续作为新 `channelType`（订阅类）增量接入即可，无需推倒架构。
- 本网关已支持 OpenAI + Anthropic，配合本迭代的 Gemini / Responses 共覆盖四种入向协议，出向仍走 OpenAI 兼容/原生适配器，
  协议转换（按上游能力双向翻译）可作为后续路线图项。

---

## 4. 落地建议小结

1. **先做 P0（占位符脱敏 + 响应还原）**：改动集中在 `Masking`/`InputGuardrailFilter`/`OutputGuardrailFilter`/`RequestContext`，不动规则链与前端，收益最大、风险最低。
2. **紧跟 P1-协议感知保护**：为当前 OpenAI `reasoning_content` 与 Anthropic thinking 打标并在围栏跳过，防止「开围栏 → 多轮推理 400」。
3. **P1-本地模型**：复用 `ModerationProvider` 抽象接 ONNX 本地模型（可先直接集成 AstrLink Guard 权重），解决「隐私检测反而要数据出境」的悖论；隐私检测建议 **fail-closed**（模型不可用即拒绝），内容审计可保持现有 fail-open。
4. **P2 三项**（dry run / 会话粘性 / 尝试级记录+成本）按排期插入，均不改变对外协议。
5. 形态差异注意：AstrLink 是「本机」，我们是「中心化」——借鉴的是**思路与协议语义**（可逆脱敏、协议保护、不出境检测），落地形态按 sidecar/独立服务适配。

---

## 5. 参考资料

- [Calcium-Ion/AstrLink (GitHub)](https://github.com/Calcium-Ion/AstrLink) — 仓库主页/特性
- [AstrLink 思考与续传兼容说明](https://github.com/Calcium-Ion/AstrLink/blob/main/docs/guides/privacy-reasoning-continuation.md) — 协议字段保留表
- [AstrLink 桌面设置：隐私模型/诊断](https://github.com/Calcium-Ion/AstrLink/blob/main/apps/desktop/README.md) — ONNX 本地导入、fail-closed
- [QuantumNous/astrlink-guard (Hugging Face)](https://huggingface.co/QuantumNous/astrlink-guard) — 隐私检测模型
- [敏感信息出门前，先在本机换个名字（aiproducthub 拆解帖）](https://aiproducthub.cn/status/62124.html)
- [AstrLink v0.1.0 Release](https://github.com/Calcium-Ion/AstrLink/releases/tag/v0.1.0)
