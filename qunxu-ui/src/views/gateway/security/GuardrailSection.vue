<template>
  <div class="security-section">
    <!-- KPI 概览（当前页统计） -->
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

    <!-- 搜索条件 -->
    <n-card :bordered="false" class="card-block">
      <n-grid :cols="searchCols" :x-gap="14" :y-gap="6" item-responsive>
        <n-grid-item>
          <n-form-item label="策略名称" :show-feedback="false">
            <n-input v-model:value="query.keyword" placeholder="请输入策略名称" clearable @keyup.enter="handleSearch" />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="作用域" :show-feedback="false">
            <n-select v-model:value="query.scope" placeholder="全部作用域" clearable :options="scopeOptions" />
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

    <!-- 策略列表 -->
    <n-card :bordered="false" class="card-block">
      <template #header>
        <div class="table-header">
          <span class="table-title">安全围栏策略</span>
          <n-tag size="small" round>共 {{ total }} 条</n-tag>
        </div>
      </template>
      <template #header-extra>
        <n-space :size="8" wrap>
          <n-button size="small" @click="fetchData">
            <template #icon><n-icon :component="RefreshOutline" /></template>刷新
          </n-button>
          <n-button size="small" type="primary" @click="openCreate">
            <template #icon><n-icon :component="AddOutline" /></template>新增策略
          </n-button>
        </n-space>
      </template>

      <n-data-table :columns="columns" :data="tableData" :loading="loading" :scroll-x="1130" :bordered="false" />

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
      :title="editingId ? '编辑围栏策略' : '新增围栏策略'"
      :style="{ width: '600px', maxWidth: '94vw' }"
      :bordered="false"
      :mask-closable="false"
    >
      <n-form ref="formRef" :model="formModel" :rules="formRules" label-placement="left" label-width="92">
        <n-form-item label="策略名称" path="name">
          <n-input v-model:value="formModel.name" placeholder="策略名称" />
        </n-form-item>
        <n-grid cols="1 640:2" :x-gap="14" :y-gap="0">
          <n-grid-item>
            <n-form-item label="作用域" path="scope">
              <n-select v-model:value="formModel.scope" :options="scopeOptions" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="作用域值" path="scopeValue">
              <n-input
                v-model:value="formModel.scopeValue"
                :disabled="formModel.scope === 'GLOBAL'"
                placeholder="租户 ID 或模型名称"
              />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="动作" path="action">
              <n-select v-model:value="formModel.action" :options="ACTION_OPTIONS" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="优先级" path="priority">
              <n-input-number v-model:value="formModel.priority" :min="0" style="width: 100%" placeholder="数值越大越优先" />
            </n-form-item>
          </n-grid-item>
        </n-grid>
        <n-form-item label="规则 JSON" path="rulesJson">
          <n-input
            v-model:value="formModel.rulesJson"
            type="textarea"
            :rows="5"
            placeholder='JSON，如 {"sensitiveWord":{"enabled":true}}'
          />
        </n-form-item>
        <n-form-item label="启用状态">
          <n-radio-group v-model:value="formModel.enabled">
            <n-radio-button :value="1">启用</n-radio-button>
            <n-radio-button :value="0">停用</n-radio-button>
          </n-radio-group>
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end" :size="10">
          <n-button @click="modalShow = false">取消</n-button>
          <n-button type="primary" :loading="saving" @click="handleSave">保存</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import {
  NCard, NButton, NIcon, NForm, NFormItem, NInput, NSelect, NRadioGroup, NRadioButton,
  NInputNumber, NDataTable, NPagination, NModal, NTag, NSpace, NGrid, NGridItem,
  useMessage, useDialog, type FormInst, type FormRules, type DataTableColumns, type SelectOption,
} from 'naive-ui'
import {
  AddOutline, RefreshOutline, SearchOutline,
  ShieldCheckmarkOutline, CheckmarkCircleOutline, CloseCircleOutline,
} from '@vicons/ionicons5'
import { guardrailApi, gwErrorMessage } from '@/api/gateway'
import { SCOPE_LABELS, ACTION_TONES, ACTION_OPTIONS, scopeLabel, actionLabel, guardrailRuleTypes, formatDateTime } from '@/api/gateway/labels'
import type { GuardrailPolicyEntity, PolicyAction, PolicyScope } from '@/api/gateway/types'
import { useResponsive } from '@/utils/useResponsive'

const message = useMessage()
const dialog = useDialog()
const { width } = useResponsive()

/* ================= KPI ================= */
const kpis = ref([
  { label: '策略总数', value: 0, unit: '条', icon: ShieldCheckmarkOutline, bg: '#eef2ff', color: '#6366f1' },
  { label: '当前页启用', value: 0, unit: '条', icon: CheckmarkCircleOutline, bg: '#ecfdf5', color: '#10b981' },
  { label: '当前页停用', value: 0, unit: '条', icon: CloseCircleOutline, bg: '#fef2f2', color: '#ef4444' },
])

function syncKpis() {
  const enabled = tableData.value.filter((r) => r.enabled === 1).length
  kpis.value[0].value = total.value
  kpis.value[1].value = enabled
  kpis.value[2].value = tableData.value.length - enabled
}

/* ================= 搜索 ================= */
const query = reactive<{
  page: number
  size: number
  keyword: string
  scope: PolicyScope | null
  enabled: number | null
}>({
  page: 1,
  size: 10,
  keyword: '',
  scope: null,
  enabled: null,
})

const scopeOptions: SelectOption[] = (Object.keys(SCOPE_LABELS) as PolicyScope[]).map((v) => ({
  label: SCOPE_LABELS[v],
  value: v,
}))
const enabledOptions: SelectOption[] = [
  { label: '启用', value: 1 },
  { label: '停用', value: 0 },
]

const searchCols = computed(() => (width.value < 768 ? 1 : width.value < 1200 ? 2 : 3))

function handleSearch() {
  query.page = 1
  fetchData()
}
function handleReset() {
  query.keyword = ''
  query.scope = null
  query.enabled = null
  handleSearch()
}

/* ================= 表格 ================= */
const loading = ref(false)
const tableData = ref<GuardrailPolicyEntity[]>([])
const total = ref(0)

function buildParams(): Record<string, unknown> {
  const params: Record<string, unknown> = { page: query.page, size: query.size }
  if (query.keyword) params.keyword = query.keyword
  if (query.scope) params.scope = query.scope
  if (query.enabled != null) params.enabled = query.enabled
  return params
}

async function fetchData() {
  loading.value = true
  try {
    const res = await guardrailApi.query(buildParams())
    tableData.value = res.items
    total.value = res.total
    syncKpis()
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

function renderActionTag(action: PolicyAction | string) {
  const tone = ACTION_TONES[action as PolicyAction] ?? 'default'
  return h(NTag, { size: 'small', round: true, type: tone, bordered: false }, { default: () => actionLabel(action) })
}

const columns: DataTableColumns<GuardrailPolicyEntity> = [
  { title: '策略名称', key: 'name', width: 150, ellipsis: { tooltip: true } },
  {
    title: '作用域', key: 'scope', width: 100,
    render: (row) => h(NTag, { size: 'small', round: true, type: 'info', bordered: false }, { default: () => scopeLabel(row.scope) }),
  },
  { title: '作用域值', key: 'scopeValue', width: 120, ellipsis: { tooltip: true }, render: (row) => row.scopeValue || '-' },
  { title: '规则类型', key: 'ruleTypes', width: 180, ellipsis: { tooltip: true }, render: (row) => guardrailRuleTypes(row.rulesJson) },
  { title: '动作', key: 'action', width: 90, render: (row) => renderActionTag(row.action) },
  {
    title: '状态', key: 'enabled', width: 90,
    render: (row) =>
      h(NTag, { size: 'small', round: true, type: row.enabled === 1 ? 'success' : 'default', bordered: false }, { default: () => (row.enabled === 1 ? '启用' : '停用') }),
  },
  { title: '优先级', key: 'priority', width: 90, className: 'tabular-nums', render: (row) => row.priority ?? '-' },
  { title: '创建时间', key: 'createdAt', width: 150, className: 'tabular-nums', render: (row) => formatDateTime(row.createdAt) },
  {
    title: '操作', key: 'actions', width: 160, align: 'right',
    render: (row) =>
      h('div', { style: 'display:flex;justify-content:flex-end;gap:4px' }, [
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

/* ================= 新增 / 编辑 ================= */
interface GuardrailForm {
  name: string
  scope: PolicyScope | string
  scopeValue: string
  action: PolicyAction | string
  rulesJson: string
  enabled: number
  priority: number | null
}

const modalShow = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const formRef = ref<FormInst | null>(null)

const formModel = reactive<GuardrailForm>({
  name: '',
  scope: 'GLOBAL',
  scopeValue: '',
  action: 'BLOCK',
  rulesJson: '',
  enabled: 1,
  priority: 10,
})

const formRules: FormRules = {
  name: { required: true, message: '请输入策略名称', trigger: ['input', 'blur'] },
  scopeValue: {
    validator: (_rule, value: string) => {
      if (formModel.scope !== 'GLOBAL' && !value) return new Error('请填写租户 ID 或模型名称')
      return true
    },
    trigger: ['input', 'blur'],
  },
}

function openCreate() {
  editingId.value = null
  Object.assign(formModel, {
    name: '', scope: 'GLOBAL', scopeValue: '', action: 'BLOCK', rulesJson: '', enabled: 1, priority: 10,
  })
  modalShow.value = true
}

function openEdit(row: GuardrailPolicyEntity) {
  editingId.value = row.id
  Object.assign(formModel, {
    name: row.name,
    scope: row.scope,
    scopeValue: row.scopeValue ?? '',
    action: row.action,
    rulesJson: row.rulesJson ?? '',
    enabled: row.enabled,
    priority: row.priority ?? 10,
  })
  modalShow.value = true
}

async function handleSave() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  saving.value = true
  try {
    const body: Partial<GuardrailPolicyEntity> = {
      name: formModel.name,
      scope: formModel.scope,
      scopeValue: formModel.scope === 'GLOBAL' ? null : formModel.scopeValue || null,
      action: formModel.action,
      rulesJson: formModel.rulesJson || null,
      enabled: formModel.enabled,
      priority: formModel.priority,
    }
    if (editingId.value) {
      await guardrailApi.update(editingId.value, body)
    } else {
      await guardrailApi.create(body)
    }
    message.success(editingId.value ? '围栏策略已更新' : '围栏策略已创建')
    modalShow.value = false
    fetchData()
  } catch (err) {
    message.error(gwErrorMessage(err))
  } finally {
    saving.value = false
  }
}

/* ================= 删除 ================= */
/* ================= 启用 / 停用 ================= */
async function toggleEnabled(row: GuardrailPolicyEntity) {
  const next = row.enabled === 1 ? 0 : 1
  try {
    await guardrailApi.update(row.id, { enabled: next })
    message.success(next === 1 ? '围栏策略已启用' : '围栏策略已停用')
    fetchData()
  } catch (err) {
    message.error(gwErrorMessage(err))
  }
}

function confirmDelete(row: GuardrailPolicyEntity) {
  dialog.warning({
    title: '删除确认',
    content: `确定要删除围栏策略「${row.name}」吗？`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await guardrailApi.remove(row.id)
        message.success('围栏策略已删除')
        fetchData()
      } catch (err) {
        message.error(gwErrorMessage(err))
      }
    },
  })
}

/* ================= 初始化 ================= */
onMounted(() => {
  fetchData()
})
</script>
