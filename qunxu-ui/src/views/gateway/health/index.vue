<template>
  <div class="gateway-health-page">
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

    <!-- 过滤条件 -->
    <n-card :bordered="false" class="card-block">
      <n-grid :cols="searchCols" :x-gap="14" :y-gap="6" item-responsive>
        <n-grid-item>
          <n-form-item label="供应商" :show-feedback="false">
            <n-select v-model:value="providerId" placeholder="全部供应商" clearable :options="providerOptions" />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="仅看异常" :show-feedback="false">
            <n-switch v-model:value="onlyUnhealthy">
              <template #checked>仅异常</template>
              <template #unchecked>全部</template>
            </n-switch>
          </n-form-item>
        </n-grid-item>
      </n-grid>
      <div class="search-actions">
        <n-button type="primary" :loading="channelLoading" @click="refreshAll">
          <template #icon><n-icon :component="RefreshOutline" /></template>刷新
        </n-button>
      </div>
    </n-card>

    <!-- 通道健康列表（服务端分页） -->
    <n-card :bordered="false" class="card-block">
      <template #header>
        <div class="table-header">
          <span class="table-title">通道健康</span>
          <n-tag size="small" round>共 {{ channelTotal }} 条</n-tag>
        </div>
      </template>
      <template #header-extra>
        <n-space :size="8" wrap>
          <n-button size="small" type="primary" :loading="probeRoundLoading" @click="handleProbeRound">
            <template #icon><n-icon :component="FlashOutline" /></template>全量探测
          </n-button>
        </n-space>
      </template>

      <n-data-table
        :columns="channelColumns"
        :data="channels"
        :loading="channelLoading"
        :scroll-x="1140"
        :bordered="false"
      />

      <div class="pagination-wrap">
        <n-pagination
          v-model:page="channelPage"
          v-model:page-size="channelPageSize"
          :item-count="channelTotal"
          :page-sizes="[10, 20, 50, 100]"
          show-size-picker
          @update:page="loadChannels"
          @update:page-size="onChannelPageSizeChange"
        />
      </div>
    </n-card>

    <!-- 健康事件（服务端分页） -->
    <n-card :bordered="false" class="card-block">
      <template #header>
        <div class="table-header">
          <span class="table-title">健康事件</span>
          <n-tag size="small" round>共 {{ eventTotal }} 条</n-tag>
        </div>
      </template>

      <n-data-table
        :columns="eventColumns"
        :data="events"
        :loading="eventLoading"
        :scroll-x="1000"
        :bordered="false"
      />

      <div class="pagination-wrap">
        <n-pagination
          v-model:page="eventPage"
          v-model:page-size="eventPageSize"
          :item-count="eventTotal"
          :page-sizes="[10, 20, 50, 100]"
          show-size-picker
          @update:page="loadEvents"
          @update:page-size="onEventPageSizeChange"
        />
      </div>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NCard, NButton, NIcon, NFormItem, NGrid, NGridItem, NSelect, NSwitch,
  NDataTable, NPagination, NTag, NSpace, NPopconfirm, useMessage, useThemeVars,
  type DataTableColumns, type SelectOption,
} from 'naive-ui'
import {
  RefreshOutline, FlashOutline, ServerOutline, CheckmarkCircleOutline,
  CloseCircleOutline, BanOutline, GitNetworkOutline,
} from '@vicons/ionicons5'
import { healthApi, providerApi, gwErrorMessage } from '@/api/gateway'
import {
  HEALTH_STATUS_LABELS, HEALTH_STATUS_TONES, healthLabel,
  formatDateTime, formatLatencyMs,
} from '@/api/gateway/labels'
import type { ChannelHealthView, HealthEventEntity, HealthSummary } from '@/api/gateway'
import { useResponsive } from '@/utils/useResponsive'

const message = useMessage()
const themeVars = useThemeVars()
const { width } = useResponsive()

/* ================= KPI ================= */
const kpis = ref([
  { label: '通道总数', value: 0, unit: '', icon: ServerOutline, bg: '#eef2ff', color: '#6366f1' },
  { label: '健康', value: 0, unit: '', icon: CheckmarkCircleOutline, bg: '#ecfdf5', color: '#10b981' },
  { label: '故障', value: 0, unit: '', icon: CloseCircleOutline, bg: '#fef2f2', color: '#ef4444' },
  { label: '已剔除', value: 0, unit: '', icon: BanOutline, bg: '#fffbeb', color: '#f59e0b' },
  { label: '可用路由', value: 0, unit: '', icon: GitNetworkOutline, bg: '#eff6ff', color: '#3b82f6' },
])

function applySummary(s: HealthSummary) {
  Object.assign(kpis.value[0], { value: s.total })
  Object.assign(kpis.value[1], { value: s.up })
  Object.assign(kpis.value[2], { value: s.down })
  Object.assign(kpis.value[3], { value: s.ejected })
  Object.assign(kpis.value[4], { value: s.routable })
}

/* ================= 过滤 ================= */
const providerId = ref<number | null>(null)
const onlyUnhealthy = ref(false)
const providerOptions = ref<SelectOption[]>([])

const searchCols = computed(() => (width.value < 768 ? 1 : width.value < 1200 ? 2 : 3))

async function loadProviders() {
  try {
    const list = await providerApi.all()
    providerOptions.value = list.map((p) => ({ label: p.name, value: p.id }))
  } catch (err) {
    message.error(gwErrorMessage(err))
  }
}

/* ================= 数据加载 ================= */
const channelLoading = ref(false)
const channels = ref<ChannelHealthView[]>([])
const channelTotal = ref(0)
const channelPage = ref(1)
const channelPageSize = ref(10)

const eventLoading = ref(false)
const events = ref<HealthEventEntity[]>([])
const eventTotal = ref(0)
const eventPage = ref(1)
const eventPageSize = ref(10)

async function loadSummary() {
  try {
    applySummary(await healthApi.summary())
  } catch (err) {
    message.error(gwErrorMessage(err))
  }
}

async function loadChannels() {
  channelLoading.value = true
  try {
    const params: Record<string, unknown> = { page: channelPage.value, size: channelPageSize.value }
    if (providerId.value != null) params.providerId = providerId.value
    if (onlyUnhealthy.value) params.onlyUnhealthy = true
    const res = await healthApi.channels(params)
    channels.value = res.items
    channelTotal.value = res.total
  } catch (err) {
    message.error(gwErrorMessage(err))
  } finally {
    channelLoading.value = false
  }
}

function onChannelPageSizeChange() {
  channelPage.value = 1
  loadChannels()
}

async function loadEvents() {
  eventLoading.value = true
  try {
    const res = await healthApi.events({ page: eventPage.value, size: eventPageSize.value })
    events.value = res.items
    eventTotal.value = res.total
  } catch (err) {
    message.error(gwErrorMessage(err))
  } finally {
    eventLoading.value = false
  }
}

function refreshAll() {
  loadSummary()
  loadChannels()
  loadEvents()
}

function onEventPageSizeChange() {
  eventPage.value = 1
  loadEvents()
}

/* ================= 手动干预 ================= */
const probeRoundLoading = ref(false)

async function probeChannel(row: ChannelHealthView) {
  try {
    const outcome = await healthApi.probe(row.channelId)
    const { result, up } = outcome
    const text = `${result.message ?? (up ? '通道正常' : '通道异常')}（${formatLatencyMs(result.latencyMs)}）`
    if (up) message.success(text)
    else message.error(text)
    refreshAll()
  } catch (err) {
    message.error(gwErrorMessage(err))
  }
}

async function recoverChannel(row: ChannelHealthView) {
  try {
    await healthApi.recover(row.channelId)
    message.success('通道已恢复')
    refreshAll()
  } catch (err) {
    message.error(gwErrorMessage(err))
  }
}

async function ejectChannel(row: ChannelHealthView) {
  try {
    await healthApi.eject(row.channelId, 60)
    message.success('通道已剔除（冷却 60 秒）')
    refreshAll()
  } catch (err) {
    message.error(gwErrorMessage(err))
  }
}

async function handleProbeRound() {
  probeRoundLoading.value = true
  try {
    const count = await healthApi.probeRound()
    message.success(`全量探测完成，本轮实际探测 ${count} 个通道`)
    refreshAll()
  } catch (err) {
    message.error(gwErrorMessage(err))
  } finally {
    probeRoundLoading.value = false
  }
}

/* ================= 通道表格 ================= */
function healthTone(v?: string | null): 'success' | 'error' | 'warning' | 'default' {
  return (v && HEALTH_STATUS_TONES[v as keyof typeof HEALTH_STATUS_TONES]) || 'default'
}

const channelColumns: DataTableColumns<ChannelHealthView> = [
  {
    title: '逻辑模型', key: 'logicalModel', width: 150, ellipsis: { tooltip: true },
    render: (row) => row.logicalModel || '-',
  },
  {
    title: '供应商', key: 'providerName', width: 130, ellipsis: { tooltip: true },
    render: (row) => row.providerName || '-',
  },
  {
    title: '状态', key: 'status', width: 90,
    render: (row) => h(NTag, { size: 'small', round: true, type: HEALTH_STATUS_TONES[row.status] }, { default: () => HEALTH_STATUS_LABELS[row.status] }),
  },
  {
    title: '可路由', key: 'routable', width: 90,
    render: (row) => h(NTag, { size: 'small', round: true, type: row.routable ? 'success' : 'error', bordered: false }, { default: () => (row.routable ? '可路由' : '不可路由') }),
  },
  {
    title: '已剔除', key: 'ejected', width: 90,
    render: (row) => h(NTag, { size: 'small', round: true, type: row.ejected ? 'warning' : 'default', bordered: false }, { default: () => (row.ejected ? '已剔除' : '正常') }),
  },
  {
    title: '连续失败', key: 'consecutiveFailures', width: 90, className: 'tabular-nums',
    render: (row) =>
      h('span', { style: row.consecutiveFailures > 0 ? 'color:#ef4444;font-weight:600' : '' }, row.consecutiveFailures),
  },
  { title: '连续成功', key: 'consecutiveSuccesses', width: 90, className: 'tabular-nums' },
  { title: '平均时延', key: 'avgLatencyMs', width: 100, className: 'tabular-nums', render: (row) => formatLatencyMs(row.avgLatencyMs) },
  { title: '权重', key: 'weight', width: 70, className: 'tabular-nums', render: (row) => row.weight ?? '-' },
  { title: '优先级', key: 'priority', width: 70, className: 'tabular-nums', render: (row) => row.priority ?? '-' },
  {
    title: '操作', key: 'actions', width: 170, align: 'right',
    render: (row) =>
      h('div', { style: 'display:flex;justify-content:flex-end;align-items:center;gap:4px' }, [
        h(NButton, { size: 'small', text: true, type: 'primary', onClick: () => probeChannel(row) }, { default: () => '探测' }),
        h(NButton, { size: 'small', text: true, type: 'success', onClick: () => recoverChannel(row) }, { default: () => '恢复' }),
        h(NPopconfirm, { onPositiveClick: () => ejectChannel(row) }, {
          trigger: () => h(NButton, { size: 'small', text: true, type: 'error' }, { default: () => '剔除' }),
          default: () => `确定剔除通道「${row.logicalModel || row.channelId}」并进入 60 秒冷却吗？`,
        }),
      ]),
  },
]

/* ================= 健康事件表格 ================= */
const EVENT_TYPE_META: Record<string, { label: string; type: 'success' | 'error' | 'warning' | 'default' }> = {
  EJECT: { label: '剔除', type: 'warning' },
  RECOVER: { label: '恢复', type: 'success' },
  PROBE_FAIL: { label: '探测失败', type: 'error' },
  AUTH_EXPIRED: { label: '凭证过期', type: 'warning' },
}

const eventColumns: DataTableColumns<HealthEventEntity> = [
  { title: 'ID', key: 'id', width: 80, className: 'tabular-nums' },
  { title: '通道 ID', key: 'channelId', width: 90, className: 'tabular-nums', render: (row) => row.channelId ?? '-' },
  {
    title: '事件类型', key: 'eventType', width: 110,
    render: (row) => {
      const meta = (row.eventType && EVENT_TYPE_META[row.eventType]) || { label: row.eventType || '-', type: 'default' as const }
      return h(NTag, { size: 'small', round: true, type: meta.type }, { default: () => meta.label })
    },
  },
  {
    title: '状态变迁', key: 'statusChange', width: 190,
    render: (row) =>
      h('div', { style: 'display:flex;align-items:center;gap:6px' }, [
        h(NTag, { size: 'small', round: true, type: healthTone(row.fromStatus) }, { default: () => healthLabel(row.fromStatus) }),
        h('span', { style: 'color:#98a2b3' }, '→'),
        h(NTag, { size: 'small', round: true, type: healthTone(row.toStatus) }, { default: () => healthLabel(row.toStatus) }),
      ]),
  },
  { title: '原因', key: 'reason', minWidth: 240, render: (row) => row.reason || '-' },
  { title: '时间', key: 'createdAt', width: 160, className: 'tabular-nums', render: (row) => formatDateTime(row.createdAt) },
]

/* ================= 初始化 ================= */
onMounted(() => {
  loadProviders()
  refreshAll()
})
</script>

<style scoped>
.gateway-health-page {
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
