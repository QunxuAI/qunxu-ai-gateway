/**
 * 模型网关管理台的展示字典：枚举 → 中文标签 / 色阶，与 naive-ui 的 NTag/NSelect 配合使用。
 *
 * <p>枚举取值与后端严格对齐（见 {@code gateway} 各实体/枚举），只做「值 → 展示」映射，
 * 不做任何业务判断。</p>
 */

import type { AuditStatus, Capability, ChannelProtocol, HealthStatus, PolicyAction, PolicyScope, ProviderType, WordLevel } from './types'

export const CAPABILITY_LABELS: Record<Capability, string> = {
  CHAT: '对话',
  EMBEDDING: '向量',
  RERANK: '重排',
  TTS: '语音合成',
  STT: '语音识别',
  IMAGE: '文生图',
}
export const CAPABILITY_OPTIONS = (Object.keys(CAPABILITY_LABELS) as Capability[]).map((v) => ({
  label: `${CAPABILITY_LABELS[v]}（${v}）`,
  value: v,
}))

export const PROTOCOL_LABELS: Record<ChannelProtocol, string> = {
  OPENAI_COMPAT: 'OpenAI 兼容',
  DASHSCOPE_NATIVE: 'DashScope 原生',
  SPARK_WS: '讯飞星火 WS',
}
export const PROTOCOL_OPTIONS = (Object.keys(PROTOCOL_LABELS) as ChannelProtocol[]).map((v) => ({
  label: PROTOCOL_LABELS[v],
  value: v,
}))

export const PROVIDER_TYPE_LABELS: Record<ProviderType, string> = {
  DEEPSEEK: 'DeepSeek',
  DASHSCOPE: '阿里云百炼',
  ZHIPU: '智谱',
  QIANFAN: '百度千帆',
  SPARK: '讯飞星火',
  OPENAI: 'OpenAI',
  CUSTOM: '自定义',
}
export const PROVIDER_TYPE_OPTIONS = (Object.keys(PROVIDER_TYPE_LABELS) as ProviderType[]).map((v) => ({
  label: `${PROVIDER_TYPE_LABELS[v]}（${v}）`,
  value: v,
}))

export const AUTH_TYPE_LABELS: Record<string, string> = {
  bearer: 'Bearer Token',
  api_key: 'API Key',
  qianfan: '千帆 Access/Secret',
  zhipu_jwt: '智谱 JWT',
  none: '无认证',
}

export const HEALTH_STATUS_LABELS: Record<HealthStatus, string> = {
  UP: '健康',
  DOWN: '故障',
  EJECTED: '已剔除',
  UNKNOWN: '未知',
}
export const HEALTH_STATUS_TONES: Record<HealthStatus, 'success' | 'error' | 'warning' | 'default'> = {
  UP: 'success',
  DOWN: 'error',
  EJECTED: 'warning',
  UNKNOWN: 'default',
}

export const AUDIT_STATUS_LABELS: Record<AuditStatus, string> = {
  SUCCESS: '成功',
  FAILURE: '失败',
  BLOCKED: '拦截',
}
export const AUDIT_STATUS_TONES: Record<AuditStatus, 'success' | 'error' | 'warning'> = {
  SUCCESS: 'success',
  FAILURE: 'error',
  BLOCKED: 'warning',
}

export const SCOPE_LABELS: Record<PolicyScope, string> = {
  GLOBAL: '全局',
  TENANT: '租户',
  MODEL: '模型',
}

export const ACTION_LABELS: Record<PolicyAction, string> = {
  BLOCK: '拦截',
  MASK: '脱敏',
  WARN: '告警',
  REVIEW: '送审',
  PASS: '放行',
}
export const ACTION_TONES: Record<PolicyAction, 'error' | 'warning' | 'default' | 'info' | 'success'> = {
  BLOCK: 'error',
  MASK: 'warning',
  WARN: 'default',
  REVIEW: 'info',
  PASS: 'success',
}
export const ACTION_OPTIONS = (Object.keys(ACTION_LABELS) as PolicyAction[]).map((v) => ({
  label: ACTION_LABELS[v],
  value: v,
}))

export const WORD_LEVEL_LABELS: Record<WordLevel, string> = {
  HIGH: '高危',
  MEDIUM: '中危',
  LOW: '低危',
}
export const WORD_LEVEL_TONES: Record<WordLevel, 'error' | 'warning' | 'default'> = {
  HIGH: 'error',
  MEDIUM: 'warning',
  LOW: 'default',
}
/** 字典序 HIGH < LOW < MEDIUM，这里显式给出展示顺序 */
export const WORD_LEVEL_ORDER: WordLevel[] = ['HIGH', 'MEDIUM', 'LOW']

export function capabilityLabel(v?: string): string {
  return (v && CAPABILITY_LABELS[v as Capability]) || v || '-'
}
export function protocolLabel(v?: string): string {
  return (v && PROTOCOL_LABELS[v as ChannelProtocol]) || v || '-'
}
export function authTypeLabel(v?: string): string {
  return (v && AUTH_TYPE_LABELS[v]) || v || '-'
}
export function scopeLabel(v?: string): string {
  return (v && SCOPE_LABELS[v as PolicyScope]) || v || '-'
}
export function actionLabel(v?: string): string {
  return (v && ACTION_LABELS[v as PolicyAction]) || v || '-'
}
export function healthLabel(v?: string | null): string {
  return (v && HEALTH_STATUS_LABELS[v as HealthStatus]) || v || '-'
}

/* ================= 围栏规则类型 ================= */
/** rules_json 顶层键 -> 中文规则类型。 */
export const GUARDRAIL_RULE_TYPE_LABELS: Record<string, string> = {
  sensitiveWord: '敏感词',
  pii: 'PII 脱敏',
  length: '长度限制',
  promptInjection: '提示词注入',
  regex: '正则规则',
  topic: '主题拦截',
}

/** 解析围栏策略 rules_json，返回已配置的规则类型中文名（如「敏感词、PII 脱敏、长度限制」）。 */
export function guardrailRuleTypes(rulesJson?: string | null): string {
  if (!rulesJson) return '-'
  try {
    const obj = JSON.parse(rulesJson)
    if (!obj || typeof obj !== 'object') return '-'
    const names = Object.keys(GUARDRAIL_RULE_TYPE_LABELS)
      .filter((key) => {
        const value = obj[key]
        if (value == null) return false
        // 对象规则块内的 enabled=false 视为未启用，不展示
        if (typeof value === 'object' && !Array.isArray(value) && 'enabled' in value) {
          return value.enabled !== false
        }
        return true
      })
      .map((key) => GUARDRAIL_RULE_TYPE_LABELS[key])
    return names.length ? names.join('、') : '-'
  } catch {
    return '-'
  }
}

export function formatDateTime(v?: string | number | null): string {
  if (v == null || v === '') return '-'
  const d = typeof v === 'number' ? new Date(v) : new Date(v)
  if (Number.isNaN(d.getTime())) return String(v)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/**
 * 本地时区 ISO（无时区偏移，如 `2026-10-13T07:52:03`）。
 * 后端审计查询把 from/to 按字面 LocalDateTime 与「本地时间存储」的 created_at 比较，
 * 因此必须传本地时间；用 toISOString()（UTC+Z）会把「今天」截断在本地 00:00，导致当天数据被过滤掉。
 */
export function toLocalIso(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

export function formatLatency(ms?: number | null): string {
  if (ms == null) return '-'
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(2)}s`
}

/** 时延（毫秒，保留整数）：如 51.456 → "51ms"、1423 → "1423ms"。 */
export function formatLatencyMs(ms?: number | null): string {
  if (ms == null || Number.isNaN(ms)) return '-'
  return `${Math.round(ms)}ms`
}

export function formatTokens(n?: number | null): string {
  if (n == null) return '-'
  return n.toLocaleString()
}
