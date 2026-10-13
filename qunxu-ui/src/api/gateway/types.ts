/**
 * 模型网关 `/admin-api/gateway/**` 契约的 TypeScript 镜像。
 *
 * 字段名一律 camelCase，与后端 Jackson 默认序列化一致；新增字段时先改后端再同步这里。
 * 与后端 `admin/*Controller.java` + `persistence/entity/*` 一一对应。
 */

// ------------------------------------------------------------------ 枚举与字面量

/** 模型能力 */
export type Capability = 'CHAT' | 'EMBEDDING' | 'RERANK' | 'TTS' | 'STT' | 'IMAGE'

/** 通道下游协议 */
export type ChannelProtocol = 'OPENAI_COMPAT' | 'DASHSCOPE_NATIVE' | 'SPARK_WS'

/** 供应商类型 */
export type ProviderType = 'DEEPSEEK' | 'DASHSCOPE' | 'ZHIPU' | 'QIANFAN' | 'SPARK' | 'OPENAI' | 'CUSTOM'

/** 通道健康状态（只有这四个值） */
export type HealthStatus = 'UP' | 'DOWN' | 'EJECTED' | 'UNKNOWN'

/** 审计状态：BLOCKED 专指围栏/内容审计拦截，限流拒绝记为 FAILURE + RATE_LIMITED */
export type AuditStatus = 'SUCCESS' | 'FAILURE' | 'BLOCKED'

/** 策略作用域 */
export type PolicyScope = 'GLOBAL' | 'TENANT' | 'MODEL'

/** 围栏/内容审计动作 */
export type PolicyAction = 'BLOCK' | 'MASK' | 'WARN' | 'REVIEW' | 'PASS'

/** 敏感词风险等级（DB 为 VARCHAR，字典序 HIGH<LOW<MEDIUM，展示需自行排序） */
export type WordLevel = 'HIGH' | 'MEDIUM' | 'LOW'

// ------------------------------------------------------------------ 配置实体

export interface ProviderEntity {
  id: number
  name: string
  code: string
  baseUrl: string
  authType: string
  credentials?: string | null
  status: number
  priority: number
  remark?: string | null
  version?: number | null
  createdAt?: string
  updatedAt?: string
}

export interface LogicalModelEntity {
  id: number
  name: string
  capability: Capability
  description?: string | null
  status: number
  createdAt?: string
  updatedAt?: string
}

export interface ModelChannelEntity {
  id: number
  logicalModelId: number
  providerId: number
  upstreamModel: string
  protocol: ChannelProtocol | string
  weight: number
  priority: number
  enabled: number
  configJson?: string | null
  version?: number | null
  createdAt?: string
  updatedAt?: string
}

export interface GuardrailRules {
  sensitiveWord?: { enabled?: boolean }
  pii?: { enabled?: boolean; mask?: boolean }
  length?: { maxInput?: number; maxOutput?: number }
  promptInjection?: { enabled?: boolean; action?: string }
  regex?: { patterns?: string[]; action?: string }
  topic?: { keywords?: string[]; action?: string }
}

export interface GuardrailPolicyEntity {
  id: number
  name: string
  scope: PolicyScope | string
  scopeValue?: string | null
  rulesJson?: string | null
  action: PolicyAction | string
  enabled: number
  priority?: number | null
  createdAt?: string
  updatedAt?: string
}

export interface ModerationPolicyEntity {
  id: number
  name: string
  scope: PolicyScope | string
  scopeValue?: string | null
  provider: string
  configJson?: string | null
  action: PolicyAction | string
  enabled: number
  priority?: number | null
  createdAt?: string
  updatedAt?: string
}

export interface SensitiveWordEntity {
  id: number
  word: string
  category?: string | null
  level?: WordLevel | string | null
  action?: PolicyAction | string | null
  enabled?: number
  createdAt?: string
  updatedAt?: string
}

export interface BatchResult {
  submitted: number
  inserted: number
  skipped: number
  warnings: string[]
}

/** 供应商模型信息：模型 id 与自动识别的能力（无法识别时为 null，批量上架时按 CHAT 兜底）。 */
export interface ProviderModelInfo {
  id: string
  capability: string | null
}

/** 批量创建通道请求：公共字段对选中模型统一生效，逻辑模型按模型名自动创建/复用，能力自动识别。 */
export interface BatchChannelCreateRequest {
  providerId: number
  /** 自动识别无法覆盖时的兜底能力，默认 CHAT（识别出的能力优先）。 */
  capability?: string | null
  protocol?: string | null
  weight?: number | null
  priority?: number | null
  enabled?: number | null
  configJson?: string | null
  upstreamModels: string[]
}

/** 批量创建通道结果。 */
export interface BatchChannelCreateResult {
  createdModels: LogicalModelEntity[]
  created: ModelChannelEntity[]
  skipped: string[]
  total: number
}

// ------------------------------------------------------------------ 分页容器

export interface AdminPage<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export type AuditPage = AdminPage<AuditLogEntity>

// ------------------------------------------------------------------ 审计与用量

export interface AuditLogEntity {
  id: number
  traceId?: string | null
  callerId?: string | null
  callerName?: string | null
  tenant?: string | null
  clientIp?: string | null
  protocol?: string | null
  endpoint?: string | null
  logicalModel?: string | null
  channelId?: number | null
  providerId?: number | null
  providerCode?: string | null
  upstreamModel?: string | null
  requestBody?: string | null
  responseBody?: string | null
  promptTokens?: number | null
  completionTokens?: number | null
  totalTokens?: number | null
  latencyMs?: number | null
  firstTokenMs?: number | null
  status?: AuditStatus | string | null
  errorCode?: string | null
  errorMsg?: string | null
  guardrailHit?: string | null
  moderationResult?: string | null
  createdAt?: string
}

export interface TokenUsageStat {
  key: string
  calls: number
  promptTokens: number
  completionTokens: number
  totalTokens: number
}

export type UsageDimension = 'caller' | 'model' | 'day'

// ------------------------------------------------------------------ 健康

export interface ChannelHealthView {
  channelId: number
  logicalModelId?: number | null
  logicalModel?: string | null
  providerId?: number | null
  providerName?: string | null
  providerCode?: string | null
  upstreamModel?: string | null
  protocol?: string | null
  weight?: number | null
  priority?: number | null
  enabled?: number | null
  status: HealthStatus
  routable: boolean
  ejected: boolean
  consecutiveFailures: number
  consecutiveSuccesses: number
  ejectedUntil?: number | null
  avgLatencyMs?: number | null
  healthUpdatedAt?: number | null
}

export interface HealthSummary {
  total: number
  up: number
  down: number
  ejected: number
  unknown: number
  routable: number
  disabled: number
}

export interface HealthResult {
  status: HealthStatus
  latencyMs: number
  message?: string | null
  errorCode?: string | null
}

export interface ProbeOutcome {
  result: HealthResult
  health: ChannelHealthView
  up: boolean
  authExpired?: boolean
}

export interface HealthEventEntity {
  id: number
  channelId?: number | null
  providerId?: number | null
  eventType?: string | null
  fromStatus?: string | null
  toStatus?: string | null
  reason?: string | null
  createdAt?: string
}

// ------------------------------------------------------------------ 网关 APIKey

export interface ApiKeyEntity {
  id: number
  userId: number
  userName?: string | null
  name: string
  keyPrefix: string
  tenant?: string | null
  status: number
  expiresAt?: string | null
  lastUsedAt?: string | null
  remark?: string | null
  createdAt?: string
  updatedAt?: string
}

/** 创建/轮换 APIKey 的一次性响应：apiKey 为明文，仅本次可见，请立即保存。 */
export interface ApiKeyCreated {
  key: ApiKeyEntity
  apiKey: string
}

// ------------------------------------------------------------------ 限流与缓存

/** 限流配额规则摊平视图（/rate-limit/config 只读展示用）。 */
export interface RateLimitRuleView {
  id?: number | null
  name: string
  scope: 'CALLER' | 'TENANT' | 'MODEL' | 'QUOTA'
  target: string
  permitsPerMinute?: number | null
  bucketCapacity?: number | null
  refillPerSecond?: number | null
  dailyTokens?: number | null
  source: string
  enabled: boolean
}

/** 限流配额规则（数据库实体，/rate-limit/rules CRUD 用）。 */
export interface RateLimitRuleEntity {
  id: number
  name: string
  ruleType: string
  target?: string | null
  permitsPerMinute?: number | null
  dailyTokens?: number | null
  enabled: number
  createdAt?: string
  updatedAt?: string
}

export interface RateLimitConfigView {
  rateLimitEnabled: boolean
  store: string
  failOpen: boolean
  burstRatio: number
  defaultPermitsPerMinute: number
  perModelEnabled: boolean
  defaultModelPermitsPerMinute: number
  quotaEnabled: boolean
  quotaScope: string
  quotaDefaultDailyTokens: number
  rules: RateLimitRuleView[]
}

export interface RateLimitStatsView {
  totalSinceStartup: number
  byScope: Record<string, number>
}

export interface CacheStatus {
  sensitiveWords: number
  pubsubActive: boolean
  pubsubChannel?: string | null
  rateLimitStore?: string | null
  rateLimitEnabled: boolean
  quotaEnabled: boolean
  healthStore?: string | null
  healthEnabled: boolean
}
