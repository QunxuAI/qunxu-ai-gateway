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

    <!-- 搜索条件（响应式列数） -->
    <n-card :bordered="false" class="card-block">
      <n-grid :cols="searchCols" :x-gap="14" :y-gap="6" item-responsive>
        <n-grid-item>
          <n-form-item label="逻辑模型" :show-feedback="false">
            <n-select v-model:value="query.logicalModelId" placeholder="全部模型" clearable :options="logicalModelOptions" />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="供应商" :show-feedback="false">
            <n-select v-model:value="query.providerId" placeholder="全部供应商" clearable :options="providerOptions" />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="协议" :show-feedback="false">
            <n-select v-model:value="query.protocol" placeholder="全部协议" clearable :options="PROTOCOL_OPTIONS" />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="状态" :show-feedback="false">
            <n-select v-model:value="query.enabled" placeholder="全部状态" clearable :options="enabledOptions" />
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

    <!-- 通道列表 -->
    <n-card :bordered="false" class="card-block">
      <template #header>
        <div class="table-header">
          <span class="table-title">通道列表</span>
          <n-tag size="small" round>共 {{ total }} 条</n-tag>
        </div>
      </template>
      <template #header-extra>
        <n-space :size="8" wrap>
          <n-button size="small" @click="fetchData">
            <template #icon><n-icon :component="RefreshOutline" /></template>刷新
          </n-button>
          <n-button size="small" type="primary" @click="openBatch">
            <template #icon><n-icon :component="RocketOutline" /></template>批量上架
          </n-button>
          <n-button size="small" type="primary" secondary @click="openCreate">
            <template #icon><n-icon :component="AddOutline" /></template>新增通道
          </n-button>
        </n-space>
      </template>

      <n-data-table
        :columns="columns"
        :data="tableData"
        :loading="loading"
        :scroll-x="1150"
        :bordered="false"
      />

      <div class="pagination-wrap">
        <n-pagination
          v-model:page="query.page"
          v-model:page-size="query.size"
          :item-count="total"
          :page-sizes="[10, 20, 50, 100]"
          show-size-picker
          @update:page="fetchData"
          @update:page-size="onPageSizeChange"
        />
      </div>
    </n-card>

    <!-- 新增 / 编辑弹窗 -->
    <n-modal
      v-model:show="modalShow"
      preset="card"
      :title="editingId ? '编辑通道' : '新增通道'"
      :style="{ width: '640px', maxWidth: '94vw' }"
      :bordered="false"
      :mask-closable="false"
    >
      <n-form ref="formRef" :model="formModel" :rules="formRules" label-placement="left" label-width="90">
        <n-grid cols="1 640:2" :x-gap="14" :y-gap="0">
          <n-grid-item>
            <n-form-item label="逻辑模型" path="logicalModelId">
              <n-select v-model:value="formModel.logicalModelId" placeholder="请选择逻辑模型" clearable :options="logicalModelOptions" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="供应商" path="providerId">
              <n-select v-model:value="formModel.providerId" placeholder="请选择供应商" clearable :options="providerOptions" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="上游模型" path="upstreamModel">
              <n-input v-model:value="formModel.upstreamModel" placeholder="上游模型标识" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="协议" path="protocol">
              <n-select v-model:value="formModel.protocol" placeholder="请选择协议" :options="PROTOCOL_OPTIONS" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="权重" path="weight">
              <n-input-number v-model:value="formModel.weight" :min="1" :max="100" style="width: 100%" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="优先级" path="priority">
              <n-input-number v-model:value="formModel.priority" style="width: 100%" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="状态">
              <n-radio-group v-model:value="formModel.enabled">
                <n-radio-button :value="1">启用</n-radio-button>
                <n-radio-button :value="0">停用</n-radio-button>
              </n-radio-group>
            </n-form-item>
          </n-grid-item>
        </n-grid>
        <n-form-item label="配置 JSON">
          <n-input v-model:value="formModel.configJson" type="textarea" :rows="4" placeholder="JSON 配置（可选）" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end" :size="10">
          <n-button @click="modalShow = false">取消</n-button>
          <n-button type="primary" :loading="saving" @click="handleSave">保存</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 批量上架模型弹窗 -->
    <n-modal
      v-model:show="batchModalShow"
      preset="card"
      title="批量上架模型"
      :style="{ width: '720px', maxWidth: '94vw' }"
      :bordered="false"
      :mask-closable="false"
    >
      <n-form label-placement="left" label-width="90">
        <n-grid cols="1 640:2" :x-gap="14" :y-gap="0">
          <n-grid-item>
            <n-form-item label="供应商" required>
              <n-select v-model:value="batchProviderId" placeholder="请选择供应商" filterable :options="providerOptions" @update:value="onBatchProviderChange" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="协议">
              <n-select v-model:value="batchProtocol" :options="PROTOCOL_OPTIONS" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="状态">
              <n-radio-group v-model:value="batchEnabled">
                <n-radio-button :value="1">启用</n-radio-button>
                <n-radio-button :value="0">停用</n-radio-button>
              </n-radio-group>
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="权重">
              <n-input-number v-model:value="batchWeight" :min="1" :max="100" style="width: 100%" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="优先级">
              <n-input-number v-model:value="batchPriority" style="width: 100%" />
            </n-form-item>
          </n-grid-item>
        </n-grid>

        <n-form-item label="模型清单">
          <div class="batch-models">
            <div class="batch-models-bar">
              <span class="batch-models-tip">
                共 {{ fetchedModels.length }} 个模型，已选 {{ selectedModels.length }} 个（将自动创建逻辑模型）
              </span>
              <n-space :size="8">
                <n-button size="tiny" :disabled="!fetchedModels.length" @click="selectAllModels">全选</n-button>
                <n-button size="tiny" :disabled="!fetchedModels.length" @click="selectedModels = []">清空</n-button>
                <n-button
                  size="tiny" type="primary" ghost :loading="fetchingModels"
                  :disabled="!batchProviderId" @click="fetchModels"
                >
                  <template #icon><n-icon :component="DownloadOutline" /></template>拉取模型
                </n-button>
              </n-space>
            </div>
            <n-data-table
              v-if="fetchedModels.length"
              size="small"
              :columns="modelColumns"
              :data="fetchedModels"
              :row-key="modelRowKey"
              :checked-row-keys="selectedModels"
              @update:checked-row-keys="onModelChecked"
              :max-height="260"
              :scroll-x="480"
              :bordered="false"
            />
            <div v-else class="batch-models-empty">请先选择供应商并「拉取模型」，同名模型自动创建逻辑模型</div>
          </div>
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end" :size="10">
          <n-button @click="batchModalShow = false">取消</n-button>
          <n-button type="primary" :loading="batchSaving" :disabled="!selectedModels.length" @click="submitBatch">批量创建</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import {
  NCard, NButton, NIcon, NForm, NFormItem, NInput, NInputNumber, NSelect,
  NRadioGroup, NRadioButton, NDataTable, NPagination, NModal, NTag, NSpace,
  NGrid, NGridItem, useMessage, useDialog, useThemeVars,
  type FormInst, type FormRules, type DataTableColumns, type SelectOption,
} from 'naive-ui'
import {
  SearchOutline, RefreshOutline, AddOutline, RocketOutline, DownloadOutline,
  GitBranchOutline, CheckmarkCircleOutline, CloseCircleOutline, BusinessOutline,
} from '@vicons/ionicons5'
import { channelApi, providerApi, logicalModelApi, healthApi, gwErrorMessage } from '@/api/gateway'
import type { ModelChannelEntity, ProviderModelInfo, HealthStatus } from '@/api/gateway/types'
import { PROTOCOL_OPTIONS, protocolLabel, capabilityLabel, formatDateTime, HEALTH_STATUS_LABELS, HEALTH_STATUS_TONES, formatLatencyMs } from '@/api/gateway/labels'
import { useResponsive } from '@/utils/useResponsive'

const message = useMessage()
const dialog = useDialog()
const themeVars = useThemeVars()
const { width } = useResponsive()

/* ================= KPI ================= */
const allChannels = ref<ModelChannelEntity[]>([])

const kpis = computed(() => [
  { label: '通道总数', value: allChannels.value.length, unit: '个', icon: GitBranchOutline, bg: '#eef2ff', color: '#6366f1' },
  { label: '已启用', value: allChannels.value.filter((c) => c.enabled === 1).length, unit: '个', icon: CheckmarkCircleOutline, bg: '#ecfdf5', color: '#10b981' },
  { label: '已停用', value: allChannels.value.filter((c) => c.enabled !== 1).length, unit: '个', icon: CloseCircleOutline, bg: '#fef2f2', color: '#ef4444' },
  { label: '供应商', value: providerOptions.value.length, unit: '家', icon: BusinessOutline, bg: '#eff6ff', color: '#3b82f6' },
])

async function loadStats() {
  try {
    allChannels.value = await channelApi.all()
  } catch {
    // KPI 为可选展示，拉取失败不阻塞页面
  }
}

/* ================= 健康状态 ================= */
const healthMap = ref<Record<number, string>>({})

// 拉取全部通道健康状态（分页循环），channelId -> status
async function loadHealthStatuses() {
  try {
    const map: Record<number, string> = {}
    let page = 1
    for (;;) {
      const res = await healthApi.channels({ page, size: 100 })
      for (const v of res.items) map[v.channelId] = v.status
      if (page * res.size >= res.total) break
      page++
    }
    healthMap.value = map
  } catch {
    // 健康状态为可选展示，拉取失败不阻塞列表
  }
}

/* ================= 下拉数据源 ================= */
const providerOptions = ref<SelectOption[]>([])
const logicalModelOptions = ref<SelectOption[]>([])
const providerDict = ref<Record<number, string>>({})
const logicalModelDict = ref<Record<number, string>>({})

function toDict(list: { id: number; name: string }[]): Record<number, string> {
  const dict: Record<number, string> = {}
  for (const item of list) dict[item.id] = item.name
  return dict
}

async function loadOptions() {
  try {
    const [providers, models] = await Promise.all([providerApi.all(), logicalModelApi.all()])
    providerOptions.value = providers.map((p) => ({ label: p.name, value: p.id }))
    providerDict.value = toDict(providers)
    logicalModelOptions.value = models.map((m) => ({ label: m.name, value: m.id }))
    logicalModelDict.value = toDict(models)
  } catch (err) {
    message.error(gwErrorMessage(err))
  }
}

function dictLabel(dict: Record<number, string>, id: number): string {
  return dict[id] ?? '-'
}

/* ================= 搜索 ================= */
const query = reactive({
  page: 1,
  size: 10,
  logicalModelId: null as number | null,
  providerId: null as number | null,
  protocol: null as string | null,
  enabled: null as number | null,
})

const enabledOptions: SelectOption[] = [
  { label: '启用', value: 1 },
  { label: '停用', value: 0 },
]

// 搜索表单响应式列数：<768 一列，<1200 两列，其余四列
const searchCols = computed(() => (width.value < 768 ? 1 : width.value < 1200 ? 2 : 4))

function handleSearch() {
  query.page = 1
  fetchData()
}
function handleReset() {
  query.logicalModelId = null
  query.providerId = null
  query.protocol = null
  query.enabled = null
  handleSearch()
}

/* ================= 表格 ================= */
const loading = ref(false)
const tableData = ref<ModelChannelEntity[]>([])
const total = ref(0)

const columns: DataTableColumns<ModelChannelEntity> = [
  { title: '逻辑模型', key: 'logicalModelId', width: 120, ellipsis: { tooltip: true }, render: (row) => dictLabel(logicalModelDict.value, row.logicalModelId) },
  {
    title: '供应商', key: 'providerId', width: 120, ellipsis: { tooltip: true },
    render: (row) => h(NTag, { size: 'small', round: true, bordered: false }, { default: () => dictLabel(providerDict.value, row.providerId) }),
  },
  { title: '上游模型', key: 'upstreamModel', width: 140, ellipsis: { tooltip: true } },
  {
    title: '协议', key: 'protocol', width: 130,
    render: (row) => h(NTag, { size: 'small', round: true, type: 'info', bordered: false }, { default: () => protocolLabel(row.protocol) }),
  },
  { title: '权重', key: 'weight', width: 80, className: 'tabular-nums' },
  { title: '优先级', key: 'priority', width: 80, className: 'tabular-nums' },
  {
    title: '状态', key: 'enabled', width: 90,
    render: (row) =>
      h(NTag, { size: 'small', round: true, type: row.enabled === 1 ? 'success' : 'error' }, { default: () => (row.enabled === 1 ? '启用' : '停用') }),
  },
  {
    title: '健康状态', key: 'healthStatus', width: 90,
    render: (row) => {
      const status = (healthMap.value[row.id] as HealthStatus) || 'UNKNOWN'
      return h(NTag, { size: 'small', round: true, type: HEALTH_STATUS_TONES[status], bordered: false }, { default: () => HEALTH_STATUS_LABELS[status] })
    },
  },
  { title: '创建时间', key: 'createdAt', width: 150, className: 'tabular-nums', render: (row) => formatDateTime(row.createdAt) },
  {
    title: '操作', key: 'actions', width: 150, align: 'right',
    render: (row) =>
      h('div', { style: 'display:flex;justify-content:flex-end;gap:4px' }, [
        h(NButton, { size: 'small', text: true, type: 'primary', onClick: () => probeChannel(row) }, { default: () => '探测' }),
        h(NButton, { size: 'small', text: true, type: 'primary', onClick: () => openEdit(row) }, { default: () => '编辑' }),
        h(
          NButton,
          { size: 'small', text: true, type: row.enabled === 1 ? 'warning' : 'success', onClick: () => toggleEnabled(row) },
          { default: () => (row.enabled === 1 ? '停用' : '启用') },
        ),
        h(NButton, { size: 'small', text: true, type: 'error', onClick: () => confirmDelete(row) }, { default: () => '删除' }),
      ]),
  },
]

async function fetchData() {
  loading.value = true
  try {
    const res = await channelApi.query({
      page: query.page,
      size: query.size,
      logicalModelId: query.logicalModelId ?? undefined,
      providerId: query.providerId ?? undefined,
      protocol: query.protocol ?? undefined,
      enabled: query.enabled ?? undefined,
    })
    tableData.value = res.items
    total.value = res.total
    loadHealthStatuses()
  } catch (err) {
    message.error(gwErrorMessage(err))
  } finally {
    loading.value = false
  }
}

function onPageSizeChange() {
  query.page = 1
  fetchData()
}

/* ================= 探测 / 启用停用 ================= */
async function probeChannel(row: ModelChannelEntity) {
  try {
    const outcome = await healthApi.probe(row.id)
    const { result, up } = outcome
    const text = `${result.message ?? (up ? '通道正常' : '通道异常')}（${formatLatencyMs(result.latencyMs)}）`
    if (up) message.success(text)
    else message.error(text)
  } catch (err) {
    message.error(gwErrorMessage(err))
  } finally {
    loadHealthStatuses()
  }
}

async function toggleEnabled(row: ModelChannelEntity) {
  const next = row.enabled === 1 ? 0 : 1
  try {
    await channelApi.update(row.id, { enabled: next })
    message.success(next === 1 ? '通道已启用' : '通道已停用')
    fetchData()
    loadStats()
    loadHealthStatuses()
  } catch (err) {
    message.error(gwErrorMessage(err))
  }
}

/* ================= 新增 / 编辑 ================= */
const modalShow = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const formRef = ref<FormInst | null>(null)

const formModel = reactive({
  logicalModelId: null as number | null,
  providerId: null as number | null,
  upstreamModel: '',
  protocol: '' as string,
  weight: 10,
  priority: 0,
  enabled: 1,
  configJson: '',
})

const formRules: FormRules = {
  logicalModelId: { required: true, message: '请选择逻辑模型', trigger: ['change', 'blur'] },
  providerId: { required: true, message: '请选择供应商', trigger: ['change', 'blur'] },
  upstreamModel: { required: true, message: '请输入上游模型', trigger: ['input', 'blur'] },
  protocol: { required: true, message: '请选择协议', trigger: ['change', 'blur'] },
}

function openCreate() {
  editingId.value = null
  Object.assign(formModel, {
    logicalModelId: null,
    providerId: null,
    upstreamModel: '',
    protocol: '',
    weight: 10,
    priority: 0,
    enabled: 1,
    configJson: '',
  })
  modalShow.value = true
}

function openEdit(row: ModelChannelEntity) {
  editingId.value = row.id
  Object.assign(formModel, {
    logicalModelId: row.logicalModelId,
    providerId: row.providerId,
    upstreamModel: row.upstreamModel,
    protocol: row.protocol,
    weight: row.weight,
    priority: row.priority,
    enabled: row.enabled,
    configJson: row.configJson ?? '',
  })
  modalShow.value = true
}

async function handleSave() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  const { logicalModelId, providerId, protocol } = formModel
  if (logicalModelId == null || providerId == null || protocol === '') return
  saving.value = true
  try {
    const body: Partial<ModelChannelEntity> = {
      logicalModelId,
      providerId,
      upstreamModel: formModel.upstreamModel,
      protocol,
      weight: formModel.weight,
      priority: formModel.priority,
      enabled: formModel.enabled,
      configJson: formModel.configJson || undefined,
    }
    if (editingId.value) {
      await channelApi.update(editingId.value, body)
      message.success('通道已更新')
    } else {
      await channelApi.create(body)
      message.success('通道创建成功')
    }
    modalShow.value = false
    fetchData()
    loadStats()
    loadOptions()
  } catch (err) {
    message.error(gwErrorMessage(err))
  } finally {
    saving.value = false
  }
}

/* ================= 删除 ================= */
function confirmDelete(row: ModelChannelEntity) {
  dialog.warning({
    title: '删除确认',
    content: `确定要删除通道「${row.upstreamModel}」吗？删除后该通道将无法参与路由。`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await channelApi.remove(row.id)
        message.success('通道已删除')
        fetchData()
        loadStats()
      } catch (err) {
        message.error(gwErrorMessage(err))
      }
    },
  })
}

/* ================= 批量上架 ================= */
const batchModalShow = ref(false)
const batchSaving = ref(false)
const fetchingModels = ref(false)
const batchProviderId = ref<number | null>(null)
const batchProtocol = ref('OPENAI_COMPAT')
const batchWeight = ref(10)
const batchPriority = ref(0)
const batchEnabled = ref(1)
const fetchedModels = ref<ProviderModelInfo[]>([])
const selectedModels = ref<string[]>([])

function openBatch() {
  batchProviderId.value = null
  batchProtocol.value = 'OPENAI_COMPAT'
  batchWeight.value = 10
  batchPriority.value = 0
  batchEnabled.value = 1
  fetchedModels.value = []
  selectedModels.value = []
  batchModalShow.value = true
}

function onBatchProviderChange() {
  // 切换供应商后旧模型清单失效
  fetchedModels.value = []
  selectedModels.value = []
}

async function fetchModels() {
  if (!batchProviderId.value) {
    message.warning('请先选择供应商')
    return
  }
  fetchingModels.value = true
  try {
    fetchedModels.value = await providerApi.models(batchProviderId.value)
    selectedModels.value = fetchedModels.value.map((m) => m.id)
    if (!fetchedModels.value.length) {
      message.info('该供应商未返回可用模型')
    }
  } catch (err) {
    message.error(gwErrorMessage(err))
    fetchedModels.value = []
    selectedModels.value = []
  } finally {
    fetchingModels.value = false
  }
}

function selectAllModels() {
  selectedModels.value = fetchedModels.value.map((m) => m.id)
}

// 模型清单表格：勾选列 + 模型 ID + 自动识别的能力
function modelRowKey(row: ProviderModelInfo): string {
  return row.id
}

function onModelChecked(keys: Array<string | number>) {
  selectedModels.value = keys.map(String)
}

const modelColumns: DataTableColumns<ProviderModelInfo> = [
  { type: 'selection', fixed: 'left', width: 46 },
  { title: '模型 ID', key: 'id', minWidth: 240, ellipsis: { tooltip: true } },
  {
    title: '能力', key: 'capability', width: 150,
    render: (row) =>
      h(NTag, { size: 'small', round: true, bordered: false, type: capabilityTagType(row.capability) },
        { default: () => capabilityLabel(row.capability ?? 'CHAT') }),
  },
]

function capabilityTagType(capability: string | null): 'success' | 'info' | 'warning' | 'primary' | 'default' {
  switch (capability) {
    case 'CHAT': return 'success'
    case 'EMBEDDING': return 'info'
    case 'RERANK': return 'warning'
    case 'TTS': return 'primary'
    case 'STT': return 'primary'
    case 'IMAGE': return 'warning'
    default: return 'default'
  }
}

async function submitBatch() {
  if (batchProviderId.value == null) {
    message.warning('请先选择供应商')
    return
  }
  if (!selectedModels.value.length) {
    message.warning('请至少选择一个模型')
    return
  }
  batchSaving.value = true
  try {
    const res = await channelApi.batchCreate({
      providerId: batchProviderId.value,
      protocol: batchProtocol.value,
      weight: batchWeight.value,
      priority: batchPriority.value,
      enabled: batchEnabled.value,
      upstreamModels: selectedModels.value,
    })
    const modelText = res.createdModels.length ? `，自动创建逻辑模型 ${res.createdModels.length} 个（能力自动识别）` : ''
    if (res.skipped.length) {
      message.success(`批量上架完成：新建 ${res.created.length} 个通道${modelText}，跳过 ${res.skipped.length} 个（已存在）`)
    } else {
      message.success(`批量上架完成：新建 ${res.created.length} 个模型通道${modelText}`)
    }
    batchModalShow.value = false
    fetchData()
    loadStats()
    loadOptions()
  } catch (err) {
    message.error(gwErrorMessage(err))
  } finally {
    batchSaving.value = false
  }
}

/* ================= 初始化 ================= */
onMounted(() => {
  fetchData()
  loadStats()
  loadOptions()
  loadHealthStatuses()
})
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

/* 批量上架：模型清单选择框 */
.batch-models {
  width: 100%;
  border: 1px solid rgba(128, 138, 158, 0.25);
  border-radius: 8px;
  overflow: hidden;
}
.batch-models-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 10px;
  background: rgba(128, 138, 158, 0.06);
  border-bottom: 1px solid rgba(128, 138, 158, 0.15);
}
.batch-models-tip {
  font-size: 12px;
  color: #98a2b3;
}
.batch-models-empty {
  padding: 22px 12px;
  text-align: center;
  font-size: 13px;
  color: #98a2b3;
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
