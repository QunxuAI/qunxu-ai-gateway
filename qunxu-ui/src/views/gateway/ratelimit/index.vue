<template>
  <div class="gateway-page">
    <!-- KPI 概览（响应式栅格） -->
    <section class="kpi-grid">
      <div v-for="kpi in kpis" :key="kpi.label" class="kpi-card">
        <div class="kpi-icon" :style="{ background: kpi.bg, color: kpi.color }">
          <n-icon :size="22"><component :is="kpi.icon" /></n-icon>
        </div>
        <div class="kpi-info">
          <div class="kpi-label">{{ kpi.label }}</div>
          <div class="kpi-num">
            {{ kpi.value }}<small>{{ kpi.unit }}</small>
          </div>
        </div>
      </div>
    </section>

    <!-- 开关状态 -->
    <n-card :bordered="false" class="card-block">
      <template #header>
        <div class="table-header">
          <span class="table-title">开关状态</span>
          <n-tag v-if="!dbMode" size="small" round type="warning">规则库不可用，当前展示 yaml 配置（只读）</n-tag>
        </div>
      </template>
      <n-descriptions v-if="config" label-placement="left" :column="3" size="medium">
        <n-descriptions-item label="限流开关">
          <n-tag size="small" round :type="boolType(config.rateLimitEnabled)">{{ boolText(config.rateLimitEnabled) }}</n-tag>
        </n-descriptions-item>
        <n-descriptions-item label="存储">{{ config.store }}</n-descriptions-item>
        <n-descriptions-item label="故障放行">
          <n-tag size="small" round :type="boolType(config.failOpen)">{{ boolText(config.failOpen) }}</n-tag>
        </n-descriptions-item>
        <n-descriptions-item label="突发系数">{{ config.burstRatio }}</n-descriptions-item>
        <n-descriptions-item label="按模型限流">
          <n-tag size="small" round :type="boolType(config.perModelEnabled)">{{ boolText(config.perModelEnabled) }}</n-tag>
        </n-descriptions-item>
        <n-descriptions-item label="配额开关">
          <n-tag size="small" round :type="boolType(config.quotaEnabled)">{{ boolText(config.quotaEnabled) }}</n-tag>
        </n-descriptions-item>
        <n-descriptions-item label="配额维度">{{ config.quotaScope }}</n-descriptions-item>
        <n-descriptions-item label="配额默认上限">{{ config.quotaDefaultDailyTokens.toLocaleString() }}</n-descriptions-item>
      </n-descriptions>
    </n-card>

    <!-- 生效规则 -->
    <n-card :bordered="false" class="card-block">
      <template #header>
        <div class="table-header">
          <span class="table-title">生效规则</span>
          <n-tag size="small" round>共 {{ config?.rules.length ?? 0 }} 条</n-tag>
        </div>
      </template>
      <template #header-extra>
        <n-space :size="8">
          <n-button v-if="dbMode" size="small" type="primary" @click="openCreate">
            <template #icon><n-icon :component="AddOutline" /></template>新增规则
          </n-button>
          <n-button size="small" :loading="loading" @click="fetchData">
            <template #icon><n-icon :component="RefreshOutline" /></template>刷新
          </n-button>
        </n-space>
      </template>

      <n-data-table
        :columns="rulesColumns"
        :data="config?.rules ?? []"
        :loading="loading"
        :scroll-x="990"
        :bordered="false"
      />
    </n-card>

    <!-- 拒绝统计（按作用域） -->
    <n-card :bordered="false" class="card-block">
      <template #header>
        <div class="table-header">
          <span class="table-title">拒绝统计（按作用域）</span>
          <n-tag size="small" round>累计 {{ stats.totalSinceStartup.toLocaleString() }} 次</n-tag>
        </div>
      </template>
      <div class="scope-stats">
        <div v-for="item in byScopeList" :key="item.scope" class="scope-stat">
          <n-tag size="small" round :type="rateScopeTone(item.scope)">{{ rateScopeLabel(item.scope) }}</n-tag>
          <span class="scope-count">{{ item.count.toLocaleString() }}</span>
        </div>
        <span v-if="byScopeList.length === 0" class="scope-empty">暂无拒绝记录</span>
      </div>
    </n-card>

    <!-- 新增/编辑规则弹窗 -->
    <n-modal
      v-model:show="modalShow"
      preset="card"
      :title="modalMode === 'edit' ? '编辑规则' : '新增规则'"
      style="width: 520px"
      :mask-closable="false"
    >
      <n-form ref="formRef" :model="formModel" :rules="formRules" label-placement="left" label-width="104">
        <n-form-item label="规则名称" path="name">
          <n-input v-model:value="formModel.name" placeholder="如：调用人默认限流" maxlength="128" clearable />
        </n-form-item>
        <n-form-item label="规则类型" path="ruleType">
          <n-select v-model:value="formModel.ruleType" :options="RULE_TYPE_OPTIONS" placeholder="选择规则类型" />
        </n-form-item>
        <n-form-item label="限流对象" path="target">
          <n-input
            v-model:value="formModel.target"
            :disabled="isDefaultType"
            :placeholder="isDefaultType ? '默认类规则无需设置' : '调用人 / 租户 / 模型标识'"
            clearable
          />
        </n-form-item>
        <n-form-item label="每分钟许可" path="permitsPerMinute">
          <n-input-number
            v-model:value="formModel.permitsPerMinute"
            :disabled="isQuotaType"
            :min="0"
            :max="99999999"
            style="width: 100%"
            placeholder="0 表示不限制"
          />
        </n-form-item>
        <n-form-item label="每日 Token 上限" path="dailyTokens">
          <n-input-number
            v-model:value="formModel.dailyTokens"
            :disabled="!isQuotaType"
            :min="0"
            style="width: 100%"
            placeholder="仅配额类规则，0 表示不限制"
          />
        </n-form-item>
        <n-form-item label="启用状态">
          <n-switch v-model:value="formModel.enabled" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="modalShow = false">取消</n-button>
          <n-button type="primary" :loading="saving" @click="saveRule">保存</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import {
  NCard, NButton, NIcon, NTag, NSpace, NDataTable, NDescriptions, NDescriptionsItem,
  NModal, NForm, NFormItem, NInput, NInputNumber, NSelect, NSwitch, NPopconfirm,
  useMessage, useThemeVars, type DataTableColumns, type FormInst, type FormRules,
} from 'naive-ui'
import {
  RefreshOutline, SpeedometerOutline, LayersOutline, BanOutline, FileTrayFullOutline, AddOutline,
} from '@vicons/ionicons5'
import { rateLimitApi, rateLimitRuleApi, gwErrorMessage } from '@/api/gateway'
import type { RateLimitConfigView, RateLimitRuleEntity, RateLimitRuleView, RateLimitStatsView } from '@/api/gateway/types'

const message = useMessage()
const themeVars = useThemeVars()

/* ================= 数据 ================= */
const loading = ref(false)
const config = ref<RateLimitConfigView | null>(null)
const stats = ref<RateLimitStatsView>({ totalSinceStartup: 0, byScope: {} })

async function fetchData() {
  loading.value = true
  try {
    const [c, s] = await Promise.all([rateLimitApi.config(), rateLimitApi.stats()])
    config.value = c
    stats.value = s
  } catch (err) {
    message.error(gwErrorMessage(err))
  } finally {
    loading.value = false
  }
}

/** 是否存在数据库规则（有 id 即数据库管理模式；否则为 yaml 只读回退）。 */
const dbMode = computed(() => (config.value?.rules ?? []).some((r) => r.id != null))

/* ================= KPI ================= */
const kpis = computed(() => [
  { label: '每分钟默认许可', value: config.value?.defaultPermitsPerMinute ?? 0, unit: '次/分', icon: SpeedometerOutline, bg: '#eef2ff', color: '#6366f1' },
  { label: '模型级默认许可', value: config.value?.defaultModelPermitsPerMinute ?? 0, unit: '次/分', icon: LayersOutline, bg: '#ecfdf5', color: '#10b981' },
  { label: '累计拒绝次数', value: stats.value.totalSinceStartup, unit: '次', icon: BanOutline, bg: '#fef2f2', color: '#ef4444' },
  { label: '今日配额', value: config.value?.quotaDefaultDailyTokens ?? 0, unit: 'tokens', icon: FileTrayFullOutline, bg: '#eff6ff', color: '#3b82f6' },
])

/* ================= 开关标签 ================= */
function boolType(v: boolean): 'success' | 'error' {
  return v ? 'success' : 'error'
}
function boolText(v: boolean): string {
  return v ? '开启' : '关闭'
}

/* ================= 生效规则 ================= */
const RATE_SCOPE_LABELS: Record<string, string> = {
  CALLER: '调用方',
  TENANT: '租户',
  MODEL: '模型',
  QUOTA: '配额',
}
type ScopeTone = 'info' | 'success' | 'warning' | 'error' | 'default'
const RATE_SCOPE_TONES: Record<string, Exclude<ScopeTone, 'default'>> = {
  CALLER: 'info',
  TENANT: 'warning',
  MODEL: 'success',
  QUOTA: 'error',
}
function rateScopeLabel(v: string): string {
  return RATE_SCOPE_LABELS[v] ?? v
}
function rateScopeTone(v: string): ScopeTone {
  return RATE_SCOPE_TONES[v] ?? 'default'
}

function fmtNum(v: number | null | undefined): string {
  return v == null ? '-' : v.toLocaleString()
}

/* ================= 规则 CRUD ================= */
const RULE_TYPE_OPTIONS = [
  { label: '调用人默认限流', value: 'CALLER_DEFAULT' },
  { label: '调用人覆盖', value: 'CALLER_OVERRIDE' },
  { label: '租户覆盖', value: 'TENANT_OVERRIDE' },
  { label: '模型默认限流', value: 'MODEL_DEFAULT' },
  { label: '模型覆盖', value: 'MODEL_OVERRIDE' },
  { label: '每日配额默认', value: 'QUOTA_DEFAULT' },
  { label: '调用人配额覆盖', value: 'QUOTA_CALLER' },
  { label: '租户配额覆盖', value: 'QUOTA_TENANT' },
]
const DEFAULT_TYPES = ['CALLER_DEFAULT', 'MODEL_DEFAULT', 'QUOTA_DEFAULT']
const QUOTA_TYPES = ['QUOTA_DEFAULT', 'QUOTA_CALLER', 'QUOTA_TENANT']

const modalShow = ref(false)
const modalMode = ref<'create' | 'edit'>('create')
const saving = ref(false)
const formRef = ref<FormInst | null>(null)
const formModel = reactive<{
  id?: number
  name: string
  ruleType: string
  target: string
  permitsPerMinute: number | null
  dailyTokens: number | null
  enabled: boolean
}>({
  id: undefined,
  name: '',
  ruleType: 'CALLER_DEFAULT',
  target: '',
  permitsPerMinute: 600,
  dailyTokens: 0,
  enabled: true,
})

const isDefaultType = computed(() => DEFAULT_TYPES.includes(formModel.ruleType))
const isQuotaType = computed(() => QUOTA_TYPES.includes(formModel.ruleType))

const formRules: FormRules = {
  name: { required: true, message: '请输入规则名称', trigger: ['blur', 'input'] },
  ruleType: { required: true, message: '请选择规则类型', trigger: ['blur', 'change'] },
  target: {
    validator: () => {
      if (!isDefaultType.value && !formModel.target?.trim()) {
        return new Error('覆盖类规则必须填写限流对象')
      }
      return true
    },
    trigger: ['blur', 'input'],
  },
  permitsPerMinute: {
    validator: () => {
      if (!isQuotaType.value && (formModel.permitsPerMinute == null || formModel.permitsPerMinute < 0)) {
        return new Error('请输入每分钟许可（>=0，0 表示不限制）')
      }
      return true
    },
    trigger: ['blur', 'change'],
  },
  dailyTokens: {
    validator: () => {
      if (isQuotaType.value && (formModel.dailyTokens == null || formModel.dailyTokens < 0)) {
        return new Error('请输入每日 Token 上限（>=0，0 表示不限制）')
      }
      return true
    },
    trigger: ['blur', 'change'],
  },
}

function openCreate() {
  formModel.id = undefined
  formModel.name = ''
  formModel.ruleType = 'CALLER_DEFAULT'
  formModel.target = ''
  formModel.permitsPerMinute = 600
  formModel.dailyTokens = 0
  formModel.enabled = true
  modalMode.value = 'create'
  modalShow.value = true
}

async function openEdit(row: RateLimitRuleView) {
  if (row.id == null) return
  try {
    // request 拦截器运行时已解包响应体，此处仅做类型收窄
    const entity = (await rateLimitRuleApi.get(row.id)) as RateLimitRuleEntity
    formModel.id = entity.id
    formModel.name = entity.name
    formModel.ruleType = entity.ruleType
    formModel.target = entity.target ?? ''
    formModel.permitsPerMinute = entity.permitsPerMinute ?? 0
    formModel.dailyTokens = entity.dailyTokens ?? 0
    formModel.enabled = entity.enabled === 1
    modalMode.value = 'edit'
    modalShow.value = true
  } catch (err) {
    message.error(gwErrorMessage(err))
  }
}

async function saveRule() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  const body = {
    name: formModel.name.trim(),
    ruleType: formModel.ruleType,
    target: formModel.target?.trim() || null,
    permitsPerMinute: isQuotaType.value ? null : (formModel.permitsPerMinute ?? 0),
    dailyTokens: isQuotaType.value ? (formModel.dailyTokens ?? 0) : null,
    enabled: formModel.enabled ? 1 : 0,
  }
  saving.value = true
  try {
    if (modalMode.value === 'edit' && formModel.id != null) {
      await rateLimitRuleApi.update(formModel.id, body)
      message.success('规则已更新')
    } else {
      await rateLimitRuleApi.create(body)
      message.success('规则已创建')
    }
    modalShow.value = false
    await fetchData()
  } catch (err) {
    message.error(gwErrorMessage(err))
  } finally {
    saving.value = false
  }
}

async function toggleEnabled(row: RateLimitRuleView) {
  if (row.id == null) return
  const next = row.enabled ? 0 : 1
  try {
    await rateLimitRuleApi.update(row.id, { enabled: next })
    message.success(row.enabled ? '规则已停用' : '规则已启用')
    await fetchData()
  } catch (err) {
    message.error(gwErrorMessage(err))
  }
}

async function removeRule(row: RateLimitRuleView) {
  if (row.id == null) return
  try {
    await rateLimitRuleApi.remove(row.id)
    message.success('规则已删除')
    await fetchData()
  } catch (err) {
    message.error(gwErrorMessage(err))
  }
}

const rulesColumns: DataTableColumns<RateLimitRuleView> = [
  { title: '规则名', key: 'name', minWidth: 140 },
  {
    title: '作用域', key: 'scope', width: 100,
    render: (row) => h(NTag, { size: 'small', round: true, type: rateScopeTone(row.scope) }, { default: () => rateScopeLabel(row.scope) }),
  },
  { title: '目标', key: 'target', minWidth: 130, render: (row) => row.target || '-' },
  { title: '每分钟许可', key: 'permitsPerMinute', width: 100, className: 'tabular-nums', render: (row) => fmtNum(row.permitsPerMinute) },
  { title: '桶容量', key: 'bucketCapacity', width: 90, className: 'tabular-nums', render: (row) => fmtNum(row.bucketCapacity) },
  { title: '每秒补充', key: 'refillPerSecond', width: 90, className: 'tabular-nums', render: (row) => fmtNum(row.refillPerSecond) },
  { title: '每日配额', key: 'dailyTokens', width: 100, className: 'tabular-nums', render: (row) => fmtNum(row.dailyTokens) },
  { title: '来源', key: 'source', width: 100, render: (row) => row.source || '-' },
  {
    title: '状态', key: 'enabled', width: 80,
    render: (row) =>
      h(NTag, { size: 'small', round: true, type: row.enabled ? 'success' : 'error' }, { default: () => (row.enabled ? '启用' : '停用') }),
  },
  {
    title: '操作', key: 'actions', width: 160,
    render: (row) => {
      if (row.id == null) return '-'
      return h(NSpace, { size: 4, justify: 'center' }, {
        default: () => [
          h(NButton, { size: 'tiny', text: true, type: 'primary', onClick: () => openEdit(row) }, { default: () => '编辑' }),
          h(NButton, { size: 'tiny', text: true, type: row.enabled ? 'warning' : 'success', onClick: () => toggleEnabled(row) },
            { default: () => (row.enabled ? '停用' : '启用') }),
          h(NPopconfirm,
            { onPositiveClick: () => removeRule(row), positiveText: '删除', negativeText: '取消' },
            {
              trigger: () => h(NButton, { size: 'tiny', text: true, type: 'error' }, { default: () => '删除' }),
              default: () => `确定删除「${row.name}」？删除后该维度将回退到默认/配置值。`,
            }),
        ],
      })
    },
  },
]

/* ================= 拒绝统计 ================= */
const byScopeList = computed(() => Object.entries(stats.value.byScope).map(([scope, count]) => ({ scope, count })))

/* ================= 初始化 ================= */
onMounted(fetchData)
</script>

<style scoped>
.gateway-page {
  max-width: 1240px;
  margin: 0 auto;
}

/* KPI 响应式栅格：自动适配列数 */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 14px;
  margin-bottom: 16px;
}
.kpi-card {
  background: v-bind('themeVars.cardColor');
  border: 1px solid rgba(128, 138, 158, 0.18);
  border-radius: 14px;
  padding: 16px 18px;
  display: flex;
  align-items: center;
  gap: 14px;
  transition: box-shadow 0.2s, transform 0.2s;
}
.kpi-card:hover {
  box-shadow: 0 6px 20px rgba(16, 24, 40, 0.08);
  transform: translateY(-1px);
}
.kpi-icon {
  width: 44px;
  height: 44px;
  border-radius: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.kpi-label {
  font-size: 12px;
  color: #98a2b3;
  font-weight: 500;
}
.kpi-num {
  font-size: 23px;
  font-weight: 700;
  line-height: 1.25;
  font-variant-numeric: tabular-nums;
}
.kpi-num small {
  font-size: 13px;
  font-weight: 600;
  margin-left: 2px;
}

.card-block {
  margin-bottom: 16px;
  border-radius: 14px;
}
.table-header {
  display: flex;
  align-items: center;
  gap: 10px;
}
.table-title {
  font-size: 15px;
  font-weight: 700;
}

/* 拒绝统计作用域卡片 */
.scope-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.scope-stat {
  display: flex;
  align-items: center;
  gap: 8px;
  background: rgba(128, 138, 158, 0.06);
  border: 1px solid rgba(128, 138, 158, 0.14);
  border-radius: 10px;
  padding: 8px 14px;
  min-width: 120px;
}
.scope-count {
  font-size: 16px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.scope-empty {
  color: #98a2b3;
  font-size: 13px;
  padding: 8px 0;
}

/* 小屏适配 */
@media (max-width: 768px) {
  .kpi-grid {
    grid-template-columns: 1fr 1fr;
    gap: 10px;
  }
  .kpi-card {
    padding: 12px;
  }
}
</style>
