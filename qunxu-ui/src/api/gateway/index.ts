/**
 * 模型网关 API 封装 —— 与后端 `/admin-api/gateway/**` 契约的唯一对照点。
 *
 * 端点形状高度同构（分页查询 + `/all` + CRUD），集中放在一个文件便于与后端
 * `admin/*Controller.java` 逐一 diff。分页页码从 1 开始；size 上限由
 * `gateway.admin.max-page-size`（默认 500）与审计侧（200）分别限制。
 *
 * 复用 `@/api/request`（自动携带 Bearer Token 与 tenant-id 请求头），
 * 并在错误时归一化提示文案：网关管理面错误体为 OpenAI 风格 `{error:{message}}`，
 * 统一错误体为 `{msg}`，统一提取后抛出便于页面展示。
 */

import request from '@/api/request'
import type {
  AdminPage,
  ApiKeyCreated,
  ApiKeyEntity,
  AuditLogEntity,
  AuditPage,
  AuditStatus,
  BatchChannelCreateRequest,
  BatchChannelCreateResult,
  BatchResult,
  CacheStatus,
  ChannelHealthView,
  GuardrailPolicyEntity,
  HealthEventEntity,
  HealthResult,
  HealthSummary,
  LogicalModelEntity,
  ModelChannelEntity,
  ModerationPolicyEntity,
  ProbeOutcome,
  ProviderEntity,
  ProviderModelInfo,
  RateLimitConfigView,
  RateLimitRuleEntity,
  RateLimitStatsView,
  SensitiveWordEntity,
  TokenUsageStat,
  UsageDimension,
} from './types'

/** 后端对「已设置但不回显」的凭证统一用这个占位值，编辑时原样回传即表示「不改」。 */
export const CREDENTIAL_MASKED = '***'

/** 审计分页单页上限，超过会被后端静默夹到 200。 */
export const AUDIT_MAX_PAGE_SIZE = 200

type Query = Record<string, unknown>

/** 错误归一化：网关管理面 OpenAI 风格错误体 + 统一 CommonResult 错误体，统一取提示文案。 */
export function gwErrorMessage(err: unknown): string {
  const e = err as { response?: { data?: { error?: { message?: string }; msg?: string } }; message?: string }
  const d = e?.response?.data
  if (d?.error?.message) return d.error.message
  if (d?.msg) return d.msg
  return e?.message || '请求失败'
}

// ------------------------------------------------------------------ 供应商

export const providerApi = {
  query: (q: Record<string, unknown> = {}) => request.get<AdminPage<ProviderEntity>>('/gateway/providers', { params: q }),
  all: (q: Record<string, unknown> = {}) => request.get<ProviderEntity[]>('/gateway/providers/all', { params: q }),
  get: (id: number) => request.get<ProviderEntity>(`/gateway/providers/${id}`),
  create: (body: Partial<ProviderEntity>) => request.post<ProviderEntity>('/gateway/providers', body),
  update: (id: number, body: Partial<ProviderEntity>) => request.put<ProviderEntity>(`/gateway/providers/${id}`, body),
  remove: (id: number) => request.delete(`/gateway/providers/${id}`),
  /** 拉取供应商上游模型清单（批量上架模型用），每个模型附带自动识别的能力。 */
  models: (id: number) => request.get<ProviderModelInfo[]>(`/gateway/providers/${id}/models`),
}

// ------------------------------------------------------------------ 逻辑模型

export const logicalModelApi = {
  query: (q: Record<string, unknown> = {}) => request.get<AdminPage<LogicalModelEntity>>('/gateway/logical-models', { params: q }),
  all: (q: Record<string, unknown> = {}) => request.get<LogicalModelEntity[]>('/gateway/logical-models/all', { params: q }),
  get: (id: number) => request.get<LogicalModelEntity>(`/gateway/logical-models/${id}`),
  create: (body: Partial<LogicalModelEntity>) => request.post<LogicalModelEntity>('/gateway/logical-models', body),
  update: (id: number, body: Partial<LogicalModelEntity>) => request.put<LogicalModelEntity>(`/gateway/logical-models/${id}`, body),
  remove: (id: number) => request.delete(`/gateway/logical-models/${id}`),
}

// ------------------------------------------------------------------ 通道

export const channelApi = {
  query: (q: Record<string, unknown> = {}) => request.get<AdminPage<ModelChannelEntity>>('/gateway/channels', { params: q }),
  all: (q: Record<string, unknown> = {}) => request.get<ModelChannelEntity[]>('/gateway/channels/all', { params: q }),
  get: (id: number) => request.get<ModelChannelEntity>(`/gateway/channels/${id}`),
  create: (body: Partial<ModelChannelEntity>) => request.post<ModelChannelEntity>('/gateway/channels', body),
  update: (id: number, body: Partial<ModelChannelEntity>) => request.put<ModelChannelEntity>(`/gateway/channels/${id}`, body),
  remove: (id: number) => request.delete(`/gateway/channels/${id}`),
  /** 批量上架模型：把一个供应商的多个模型一次性创建为通道。 */
  batchCreate: (body: BatchChannelCreateRequest) =>
    request.post<BatchChannelCreateResult>('/gateway/channels/batch', body),
}

// ------------------------------------------------------------------ 健康

export const healthApi = {
  summary: () => request.get<HealthSummary>('/gateway/health/summary'),
  /** 通道健康分页（先过滤后分页，page 从 1 起）。 */
  channels: (q: Record<string, unknown> = {}) => request.get<AdminPage<ChannelHealthView>>('/gateway/health/channels', { params: q }),
  channel: (id: number) => request.get<ChannelHealthView>(`/gateway/health/channels/${id}`),
  probe: (id: number) => request.post<ProbeOutcome>(`/gateway/health/channels/${id}/probe`),
  recover: (id: number) => request.post<ChannelHealthView>(`/gateway/health/channels/${id}/recover`),
  eject: (id: number, cooldownSeconds?: number) =>
    request.post<ChannelHealthView>(`/gateway/health/channels/${id}/eject`, null, { params: { cooldownSeconds } }),
  probeRound: () => request.post<number>('/gateway/health/probe-round'),
  events: (q: Record<string, unknown> = {}) => request.get<AdminPage<HealthEventEntity>>('/gateway/health/events', { params: q }),
}

// ------------------------------------------------------------------ 审计

export const auditApi = {
  query: (q: Record<string, unknown> = {}) => request.get<AuditPage>('/gateway/audit', { params: q }),
  usage: (dimension: UsageDimension, q: Record<string, unknown> = {}) =>
    request.get<TokenUsageStat[]>('/gateway/audit/usage', { params: { dimension, ...q } }),
}

// ------------------------------------------------------------------ 安全围栏策略

export const guardrailApi = {
  query: (q: Record<string, unknown> = {}) => request.get<AdminPage<GuardrailPolicyEntity>>('/gateway/guardrail-policies', { params: q }),
  all: (q: Record<string, unknown> = {}) => request.get<GuardrailPolicyEntity[]>('/gateway/guardrail-policies/all', { params: q }),
  get: (id: number) => request.get<GuardrailPolicyEntity>(`/gateway/guardrail-policies/${id}`),
  create: (body: Partial<GuardrailPolicyEntity>) => request.post<GuardrailPolicyEntity>('/gateway/guardrail-policies', body),
  update: (id: number, body: Partial<GuardrailPolicyEntity>) =>
    request.put<GuardrailPolicyEntity>(`/gateway/guardrail-policies/${id}`, body),
  remove: (id: number) => request.delete(`/gateway/guardrail-policies/${id}`),
}

// ------------------------------------------------------------------ 内容审计策略

export const moderationApi = {
  query: (q: Record<string, unknown> = {}) => request.get<AdminPage<ModerationPolicyEntity>>('/gateway/moderation-policies', { params: q }),
  all: (q: Record<string, unknown> = {}) => request.get<ModerationPolicyEntity[]>('/gateway/moderation-policies/all', { params: q }),
  get: (id: number) => request.get<ModerationPolicyEntity>(`/gateway/moderation-policies/${id}`),
  create: (body: Partial<ModerationPolicyEntity>) => request.post<ModerationPolicyEntity>('/gateway/moderation-policies', body),
  update: (id: number, body: Partial<ModerationPolicyEntity>) =>
    request.put<ModerationPolicyEntity>(`/gateway/moderation-policies/${id}`, body),
  remove: (id: number) => request.delete(`/gateway/moderation-policies/${id}`),
  providers: () => request.get<string[]>('/gateway/moderation-policies/providers'),
}

// ------------------------------------------------------------------ 敏感词

export const sensitiveWordApi = {
  query: (q: Record<string, unknown> = {}) => request.get<AdminPage<SensitiveWordEntity>>('/gateway/sensitive-words', { params: q }),
  all: (q: Record<string, unknown> = {}) => request.get<SensitiveWordEntity[]>('/gateway/sensitive-words/all', { params: q }),
  get: (id: number) => request.get<SensitiveWordEntity>(`/gateway/sensitive-words/${id}`),
  create: (body: Partial<SensitiveWordEntity>) => request.post<SensitiveWordEntity>('/gateway/sensitive-words', body),
  update: (id: number, body: Partial<SensitiveWordEntity>) =>
    request.put<SensitiveWordEntity>(`/gateway/sensitive-words/${id}`, body),
  remove: (id: number) => request.delete(`/gateway/sensitive-words/${id}`),
  importBatch: (words: Partial<SensitiveWordEntity>[]) => request.post<BatchResult>('/gateway/sensitive-words/batch', words),
}

// ------------------------------------------------------------------ 限流（配置视图只读 + 规则库 CRUD）

export const rateLimitApi = {
  config: () => request.get<RateLimitConfigView>('/gateway/rate-limit/config'),
  stats: () => request.get<RateLimitStatsView>('/gateway/rate-limit/stats'),
}

/** 限流配额规则库管理（数据库 {@code rate_limit_rule}，写入后秒级生效）。 */
export const rateLimitRuleApi = {
  query: (q: Record<string, unknown> = {}) =>
    request.get<AdminPage<RateLimitRuleEntity>>('/gateway/rate-limit/rules', { params: q }),
  get: (id: number) => request.get<RateLimitRuleEntity>(`/gateway/rate-limit/rules/${id}`),
  create: (body: Partial<RateLimitRuleEntity>) =>
    request.post<RateLimitRuleEntity>('/gateway/rate-limit/rules', body),
  update: (id: number, body: Partial<RateLimitRuleEntity>) =>
    request.put<RateLimitRuleEntity>(`/gateway/rate-limit/rules/${id}`, body),
  remove: (id: number) => request.delete(`/gateway/rate-limit/rules/${id}`),
}

// ------------------------------------------------------------------ 网关 APIKey

/**
 * 令牌管理：管理员（super_admin）可管理所有用户，普通用户只能管理自己的。
 * 创建/轮换响应中的 apiKey 为明文，仅一次可见。
 */
export const apiKeyApi = {
  query: (q: Record<string, unknown> = {}) => request.get<AdminPage<ApiKeyEntity>>('/gateway/api-keys', { params: q }),
  all: (q: Record<string, unknown> = {}) => request.get<ApiKeyEntity[]>('/gateway/api-keys/all', { params: q }),
  get: (id: number) => request.get<ApiKeyEntity>(`/gateway/api-keys/${id}`),
  create: (body: Partial<ApiKeyEntity>) => request.post<ApiKeyCreated>('/gateway/api-keys', body),
  update: (id: number, body: Partial<ApiKeyEntity>) => request.put<ApiKeyEntity>(`/gateway/api-keys/${id}`, body),
  remove: (id: number) => request.delete(`/gateway/api-keys/${id}`),
  rotate: (id: number) => request.post<ApiKeyCreated>(`/gateway/api-keys/${id}/rotate`),
}

// ------------------------------------------------------------------ 缓存与运行时概况

export const cacheApi = {
  status: () => request.get<CacheStatus>('/gateway/cache/status'),
  invalidate: (type?: string) => request.post<string>('/gateway/cache/invalidate', null, { params: { type } }),
  invalidateLocal: (type?: string) => request.post<string>('/gateway/cache/invalidate-local', null, { params: { type } }),
}

// ------------------------------------------------------------------ 导出（供页面复用）

export type { AdminPage, AuditLogEntity, AuditStatus, ChannelHealthView, HealthEventEntity, HealthResult, HealthSummary, TokenUsageStat }
