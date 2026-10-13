<template>
  <div class="gateway-audit-page">
    <!-- KPI 概览（近 7 日用量） -->
    <section class="kpi-grid">
      <div v-for="kpi in usageKpis" :key="kpi.label" class="kpi-card">
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

    <!-- 用量趋势（近 7 日，客户端表格） -->
    <n-card :bordered="false" class="card-block">
      <template #header>
        <div class="table-header">
          <span class="table-title">用量趋势（近 7 日）</span>
          <n-tag size="small" round>共 {{ usageRows.length }} 天</n-tag>
        </div>
      </template>
      <template #header-extra>
        <n-space :size="8" wrap>
          <n-button size="small" :loading="usageLoading" @click="loadUsage">
            <template #icon><n-icon :component="RefreshOutline" /></template>刷新
          </n-button>
        </n-space>
      </template>
      <n-data-table
        :columns="usageColumns"
        :data="usageRows"
        :loading="usageLoading"
        :scroll-x="620"
        :bordered="false"
      />
    </n-card>

    <!-- 搜索条件（响应式列数） -->
    <n-card :bordered="false" class="card-block">
      <n-grid :cols="searchCols" :x-gap="14" :y-gap="6" item-responsive>
        <n-grid-item>
          <n-form-item label="调用人" :show-feedback="false">
            <n-input v-model:value="query.callerId" placeholder="请输入调用人标识" clearable @keyup.enter="handleSearch" />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="租户" :show-feedback="false">
            <n-input v-model:value="query.tenant" placeholder="请输入租户" clearable @keyup.enter="handleSearch" />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="逻辑模型" :show-feedback="false">
            <n-select v-model:value="query.logicalModel" placeholder="全部模型" clearable :options="modelOptions" />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="供应商" :show-feedback="false">
            <n-select v-model:value="query.providerId" placeholder="全部供应商" clearable :options="providerOptions" />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="协议" :show-feedback="false">
            <n-input v-model:value="query.protocol" placeholder="如 OPENAI / ANTHROPIC" clearable @keyup.enter="handleSearch" />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="状态" :show-feedback="false">
            <n-select v-model:value="query.status" placeholder="全部状态" clearable :options="statusOptions" />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="错误码" :show-feedback="false">
            <n-input v-model:value="query.errorCode" placeholder="请输入错误码" clearable @keyup.enter="handleSearch" />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="时间段" :show-feedback="false">
            <n-date-picker v-model:value="dateRange" type="daterange" clearable style="width: 100%" />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="TraceId" :show-feedback="false">
            <n-input v-model:value="query.traceId" placeholder="请输入链路 ID" clearable @keyup.enter="handleSearch" />
          </n-form-item>
        </n-grid-item>
      </n-grid>
      <div class="search-actions">
        <n-button type="primary" @click="handleSearch">
          <template #icon><n-icon :component="SearchOutline" /></template>查询
        </n-button>
        <n-button @click="handleReset">
          <template #icon><n-icon :component="RefreshOutline" /></template>重置
        </n-button>
      </div>
    </n-card>

    <!-- 调用审计列表 -->
    <n-card :bordered="false" class="card-block">
      <template #header>
        <div class="table-header">
          <span class="table-title">调用审计</span>
          <n-tag size="small" round>共 {{ auditTotal }} 条</n-tag>
        </div>
      </template>
      <template #header-extra>
        <n-space :size="8" wrap>
          <n-button size="small" :loading="auditLoading" @click="fetchAudit">
            <template #icon><n-icon :component="RefreshOutline" /></template>刷新
          </n-button>
        </n-space>
      </template>

      <n-data-table
        :columns="auditColumns"
        :data="auditRows"
        :loading="auditLoading"
        :scroll-x="2400"
        :bordered="false"
      />

      <div class="pagination-wrap">
        <n-pagination
          v-model:page="page"
          v-model:page-size="size"
          :item-count="auditTotal"
          :page-sizes="[20, 50, 100, 200]"
          show-size-picker
          @update:page="fetchAudit"
          @update:page-size="onPageSizeChange"
        />
      </div>
    </n-card>

    <!-- 详情弹窗 -->
    <n-modal
      v-model:show="detailShow"
      preset="card"
      title="调用详情"
      :style="{ width: '760px', maxWidth: '94vw' }"
      :bordered="false"
    >
      <template v-if="detail">
        <n-descriptions :column="3" label-placement="left" size="small" bordered>
          <n-descriptions-item label="TraceId">
            <span class="tabular-nums">{{ detail.traceId || '-' }}</span>
          </n-descriptions-item>
          <n-descriptions-item label="调用人">{{ detail.callerId || '-' }}</n-descriptions-item>
          <n-descriptions-item label="调用人名称">{{ detail.callerName || '-' }}</n-descriptions-item>
          <n-descriptions-item label="租户">{{ detail.tenant || '-' }}</n-descriptions-item>
          <n-descriptions-item label="客户端 IP">{{ detail.clientIp || '-' }}</n-descriptions-item>
          <n-descriptions-item label="协议">{{ detail.protocol || '-' }}</n-descriptions-item>
          <n-descriptions-item label="端点">{{ detail.endpoint || '-' }}</n-descriptions-item>
          <n-descriptions-item label="逻辑模型">{{ detail.logicalModel || '-' }}</n-descriptions-item>
          <n-descriptions-item label="供应商">{{ detail.providerCode || '-' }}</n-descriptions-item>
          <n-descriptions-item label="上游模型">{{ detail.upstreamModel || '-' }}</n-descriptions-item>
          <n-descriptions-item label="状态">{{ auditLabel(detail.status) }}</n-descriptions-item>
          <n-descriptions-item label="提示词 Tokens">
            <span class="tabular-nums">{{ formatTokens(detail.promptTokens) }}</span>
          </n-descriptions-item>
          <n-descriptions-item label="输出 Tokens">
            <span class="tabular-nums">{{ formatTokens(detail.completionTokens) }}</span>
          </n-descriptions-item>
          <n-descriptions-item label="总 Tokens">
            <span class="tabular-nums">{{ formatTokens(detail.totalTokens) }}</span>
          </n-descriptions-item>
          <n-descriptions-item label="时延">
            <span class="tabular-nums">{{ formatLatency(detail.latencyMs) }}</span>
          </n-descriptions-item>
          <n-descriptions-item label="首 Token 时延">
            <span class="tabular-nums">{{ formatLatency(detail.firstTokenMs) }}</span>
          </n-descriptions-item>
          <n-descriptions-item label="错误码">{{ detail.errorCode || '-' }}</n-descriptions-item>
          <n-descriptions-item label="错误信息" :span="2">{{ detail.errorMsg || '-' }}</n-descriptions-item>
          <n-descriptions-item label="围栏命中">{{ detail.guardrailHit || '-' }}</n-descriptions-item>
          <n-descriptions-item label="内容审计">{{ detail.moderationResult || '-' }}</n-descriptions-item>
          <n-descriptions-item label="记录时间">
            <span class="tabular-nums">{{ formatDateTime(detail.createdAt) }}</span>
          </n-descriptions-item>
        </n-descriptions>
        <div class="body-block">
          <div class="body-label">请求体</div>
          <n-input :value="detail.requestBody || '-'" type="textarea" readonly :rows="8" />
        </div>
        <div class="body-block">
          <div class="body-label">响应体</div>
          <n-input :value="detail.responseBody || '-'" type="textarea" readonly :rows="8" />
        </div>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import {
  NCard, NButton, NIcon, NFormItem, NGrid, NGridItem, NInput, NSelect,
  NDatePicker, NDataTable, NPagination, NTag, NSpace, NModal,
  NDescriptions, NDescriptionsItem, useMessage, useThemeVars,
  type DataTableColumns, type SelectOption,
} from 'naive-ui'
import {
  SearchOutline, RefreshOutline, CallOutline, StatsChartOutline,
} from '@vicons/ionicons5'
import { auditApi, logicalModelApi, providerApi, gwErrorMessage } from '@/api/gateway'
import { AUDIT_STATUS_LABELS, AUDIT_STATUS_TONES, formatDateTime, formatLatency, formatTokens, toLocalIso } from '@/api/gateway/labels'
import type { AuditLogEntity, AuditStatus, TokenUsageStat } from '@/api/gateway'
import { useResponsive } from '@/utils/useResponsive'

const message = useMessage()
const themeVars = useThemeVars()
const { width } = useResponsive()

/* ================= 近 7 日用量 ================= */
const usageLoading = ref(false)
const usageRows = ref<TokenUsageStat[]>([])

const totalCalls = computed(() => usageRows.value.reduce((sum, r) => sum + r.calls, 0))
const totalTokens = computed(() => usageRows.value.reduce((sum, r) => sum + r.totalTokens, 0))

const usageKpis = computed(() => [
  { label: '近 7 日调用', value: totalCalls.value.toLocaleString(), unit: '次', icon: CallOutline, bg: '#eef2ff', color: '#6366f1' },
  { label: '近 7 日 Token', value: totalTokens.value.toLocaleString(), unit: '', icon: StatsChartOutline, bg: '#eff6ff', color: '#3b82f6' },
])

async function loadUsage() {
  usageLoading.value = true
  try {
    const now = new Date()
    const from = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000)
    usageRows.value = await auditApi.usage('day', { from: toLocalIso(from), to: toLocalIso(now) })
  } catch (err) {
    message.error(gwErrorMessage(err))
  } finally {
    usageLoading.value = false
  }
}

const usageColumns: DataTableColumns<TokenUsageStat> = [
  { title: '日期', key: 'key', minWidth: 120, className: 'tabular-nums' },
  { title: '调用次数', key: 'calls', width: 100, className: 'tabular-nums' },
  { title: '提示词 Tokens', key: 'promptTokens', width: 130, className: 'tabular-nums', render: (row) => formatTokens(row.promptTokens) },
  { title: '输出 Tokens', key: 'completionTokens', width: 130, className: 'tabular-nums', render: (row) => formatTokens(row.completionTokens) },
  { title: '总 Tokens', key: 'totalTokens', width: 130, className: 'tabular-nums', render: (row) => formatTokens(row.totalTokens) },
]

/* ================= 搜索 ================= */
const query = reactive({
  callerId: '',
  tenant: '',
  logicalModel: null as string | null,
  providerId: null as number | null,
  protocol: '',
  status: null as string | null,
  errorCode: '',
  traceId: '',
})
const dateRange = ref<[number, number] | null>(null)
const page = ref(1)
const size = ref(20)

const searchCols = computed(() => (width.value < 768 ? 1 : width.value < 1200 ? 2 : 4))

const statusOptions: SelectOption[] = [
  { label: AUDIT_STATUS_LABELS.SUCCESS, value: 'SUCCESS' },
  { label: AUDIT_STATUS_LABELS.FAILURE, value: 'FAILURE' },
  { label: AUDIT_STATUS_LABELS.BLOCKED, value: 'BLOCKED' },
]

const modelOptions = ref<SelectOption[]>([])
const providerOptions = ref<SelectOption[]>([])

async function loadOptions() {
  try {
    const models = await logicalModelApi.all()
    modelOptions.value = models.map((m) => ({ label: m.name, value: m.name }))
  } catch (err) {
    message.error(gwErrorMessage(err))
  }
  try {
    const providers = await providerApi.all()
    providerOptions.value = providers.map((p) => ({ label: p.name, value: p.id }))
  } catch (err) {
    message.error(gwErrorMessage(err))
  }
}

function buildParams(): Record<string, unknown> {
  const params: Record<string, unknown> = { page: page.value, size: size.value }
  if (query.callerId.trim()) params.callerId = query.callerId.trim()
  if (query.tenant.trim()) params.tenant = query.tenant.trim()
  if (query.logicalModel) params.logicalModel = query.logicalModel
  if (query.providerId != null) params.providerId = query.providerId
  if (query.protocol.trim()) params.protocol = query.protocol.trim()
  if (query.status) params.status = query.status
  if (query.errorCode.trim()) params.errorCode = query.errorCode.trim()
  if (query.traceId.trim()) params.traceId = query.traceId.trim()
  if (dateRange.value) {
    const start = new Date(dateRange.value[0])
    start.setHours(0, 0, 0, 0)
    const end = new Date(dateRange.value[1])
    end.setHours(23, 59, 59, 999)
    params.from = toLocalIso(start)
    params.to = toLocalIso(end)
  }
  return params
}

function handleSearch() {
  page.value = 1
  fetchAudit()
}

function handleReset() {
  query.callerId = ''
  query.tenant = ''
  query.logicalModel = null
  query.providerId = null
  query.protocol = ''
  query.status = null
  query.errorCode = ''
  query.traceId = ''
  dateRange.value = null
  handleSearch()
}

/* ================= 表格 ================= */
const auditLoading = ref(false)
const auditRows = ref<AuditLogEntity[]>([])
const auditTotal = ref(0)

function auditTone(v?: string | null): 'success' | 'error' | 'warning' | 'default' {
  return (v && AUDIT_STATUS_TONES[v as AuditStatus]) || 'default'
}

function auditLabel(v?: string | null): string {
  return (v && AUDIT_STATUS_LABELS[v as AuditStatus]) || v || '-'
}

const auditColumns: DataTableColumns<AuditLogEntity> = [
  { title: '时间', key: 'createdAt', width: 155, className: 'tabular-nums', render: (row) => formatDateTime(row.createdAt) },
  { title: '调用人', key: 'callerId', width: 110, render: (row) => row.callerId || '-' },
  { title: '调用人名称', key: 'callerName', width: 110, render: (row) => row.callerName || '-' },
  { title: '租户', key: 'tenant', width: 90, render: (row) => row.tenant || '-' },
  { title: '协议', key: 'protocol', width: 100, render: (row) => row.protocol || '-' },
  { title: '端点', key: 'endpoint', minWidth: 150, render: (row) => row.endpoint || '-' },
  { title: '逻辑模型', key: 'logicalModel', minWidth: 110, render: (row) => row.logicalModel || '-' },
  { title: '供应商', key: 'providerCode', width: 100, render: (row) => row.providerCode || '-' },
  { title: '上游模型', key: 'upstreamModel', minWidth: 130, render: (row) => row.upstreamModel || '-' },
  {
    title: '状态', key: 'status', width: 80,
    render: (row) => h(NTag, { size: 'small', round: true, type: auditTone(row.status) }, { default: () => auditLabel(row.status) }),
  },
  { title: '提示词 Tokens', key: 'promptTokens', width: 110, className: 'tabular-nums', render: (row) => formatTokens(row.promptTokens) },
  { title: '输出 Tokens', key: 'completionTokens', width: 110, className: 'tabular-nums', render: (row) => formatTokens(row.completionTokens) },
  { title: '总 Tokens', key: 'totalTokens', width: 100, className: 'tabular-nums', render: (row) => formatTokens(row.totalTokens) },
  { title: '时延', key: 'latencyMs', width: 90, className: 'tabular-nums', render: (row) => formatLatency(row.latencyMs) },
  {
    title: '错误码', key: 'errorCode', width: 100,
    render: (row) => (row.errorCode ? h(NTag, { size: 'small', round: true, type: 'error', bordered: false }, { default: () => row.errorCode }) : '-'),
  },
  { title: '错误信息', key: 'errorMsg', minWidth: 160, ellipsis: { tooltip: true }, render: (row) => row.errorMsg || '-' },
  { title: '围栏命中', key: 'guardrailHit', width: 90, render: (row) => row.guardrailHit || '-' },
  { title: '内容审计', key: 'moderationResult', width: 100, render: (row) => row.moderationResult || '-' },
  {
    title: '操作', key: 'actions', width: 80, align: 'right',
    render: (row) => h(NButton, { size: 'small', text: true, type: 'primary', onClick: () => openDetail(row) }, { default: () => '详情' }),
  },
]

async function fetchAudit() {
  auditLoading.value = true
  try {
    const res = await auditApi.query(buildParams())
    auditRows.value = res.items
    auditTotal.value = res.total
  } catch (err) {
    message.error(gwErrorMessage(err))
  } finally {
    auditLoading.value = false
  }
}

function onPageSizeChange() {
  page.value = 1
  fetchAudit()
}

/* ================= 详情 ================= */
const detailShow = ref(false)
const detail = ref<AuditLogEntity | null>(null)

function openDetail(row: AuditLogEntity) {
  detail.value = row
  detailShow.value = true
}

/* ================= 初始化 ================= */
onMounted(() => {
  loadOptions()
  loadUsage()
  fetchAudit()
})
</script>

<style scoped>
.gateway-audit-page {
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
.search-actions {
  display: flex;
  gap: 10px;
  margin-top: 6px;
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
.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.body-block {
  margin-top: 14px;
}
.body-label {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 6px;
  color: #475467;
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
  .pagination-wrap {
    justify-content: center;
  }
}
</style>
