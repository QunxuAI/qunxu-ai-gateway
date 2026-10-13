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
          <n-form-item label="敏感词" :show-feedback="false">
            <n-input v-model:value="query.keyword" placeholder="请输入敏感词" clearable @keyup.enter="handleSearch" />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="分类" :show-feedback="false">
            <n-input v-model:value="query.category" placeholder="如：政治、暴力" clearable @keyup.enter="handleSearch" />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="等级" :show-feedback="false">
            <n-select v-model:value="query.level" placeholder="全部等级" clearable :options="levelOptions" />
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

    <!-- 敏感词列表 -->
    <n-card :bordered="false" class="card-block">
      <template #header>
        <div class="table-header">
          <span class="table-title">敏感词库</span>
          <n-tag size="small" round>共 {{ total }} 条</n-tag>
        </div>
      </template>
      <template #header-extra>
        <n-space :size="8" wrap>
          <n-button size="small" @click="fetchData">
            <template #icon><n-icon :component="RefreshOutline" /></template>刷新
          </n-button>
          <n-button size="small" @click="openBatch">
            <template #icon><n-icon :component="DocumentTextOutline" /></template>批量导入
          </n-button>
          <n-button size="small" type="primary" @click="openCreate">
            <template #icon><n-icon :component="AddOutline" /></template>新增敏感词
          </n-button>
        </n-space>
      </template>

      <n-data-table :columns="columns" :data="tableData" :loading="loading" :scroll-x="940" :bordered="false" />

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
      :title="editingId ? '编辑敏感词' : '新增敏感词'"
      :style="{ width: '560px', maxWidth: '94vw' }"
      :bordered="false"
      :mask-closable="false"
    >
      <n-form ref="formRef" :model="formModel" :rules="formRules" label-placement="left" label-width="84">
        <n-form-item label="敏感词" path="word">
          <n-input v-model:value="formModel.word" placeholder="敏感词内容" />
        </n-form-item>
        <n-grid cols="1 640:2" :x-gap="14" :y-gap="0">
          <n-grid-item>
            <n-form-item label="分类" path="category">
              <n-input v-model:value="formModel.category" placeholder="可选，如：政治" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="等级" path="level">
              <n-select v-model:value="formModel.level" :options="levelOptions" placeholder="默认中危" clearable />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="动作" path="action">
              <n-select v-model:value="formModel.action" :options="ACTION_OPTIONS" placeholder="默认拦截" clearable />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="启用状态">
              <n-radio-group v-model:value="formModel.enabled">
                <n-radio-button :value="1">启用</n-radio-button>
                <n-radio-button :value="0">停用</n-radio-button>
              </n-radio-group>
            </n-form-item>
          </n-grid-item>
        </n-grid>
      </n-form>
      <template #footer>
        <n-space justify="end" :size="10">
          <n-button @click="modalShow = false">取消</n-button>
          <n-button type="primary" :loading="saving" @click="handleSave">保存</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 批量导入弹窗 -->
    <n-modal
      v-model:show="batchShow"
      preset="card"
      title="批量导入敏感词"
      :style="{ width: '620px', maxWidth: '94vw' }"
      :bordered="false"
      :mask-closable="false"
    >
      <n-alert v-if="batchWarnings.length > 0" type="warning" :show-icon="true" class="batch-alert">
        <div v-for="(w, i) in batchWarnings" :key="i">{{ w }}</div>
      </n-alert>
      <n-input
        v-model:value="batchText"
        type="textarea"
        :rows="10"
        placeholder="每行一个敏感词，支持格式：word 或 word|category|level|action&#10;示例：&#10;赌&#10;暴力|违法|HIGH|BLOCK"
      />
      <template #footer>
        <n-space justify="end" :size="10">
          <n-button @click="batchShow = false">关闭</n-button>
          <n-button type="primary" :loading="importing" @click="handleImport">开始导入</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import {
  NCard, NButton, NIcon, NForm, NFormItem, NInput, NSelect, NRadioGroup, NRadioButton,
  NDataTable, NPagination, NModal, NTag, NSpace, NGrid, NGridItem, NAlert,
  useMessage, useDialog, type FormInst, type FormRules, type DataTableColumns, type SelectOption,
} from 'naive-ui'
import {
  AddOutline, RefreshOutline, SearchOutline, DocumentTextOutline,
  AlertCircleOutline, CheckmarkCircleOutline, CloseCircleOutline,
} from '@vicons/ionicons5'
import { sensitiveWordApi, gwErrorMessage } from '@/api/gateway'
import {
  ACTION_TONES, ACTION_OPTIONS, actionLabel,
  WORD_LEVEL_LABELS, WORD_LEVEL_TONES, WORD_LEVEL_ORDER, formatDateTime,
} from '@/api/gateway/labels'
import type { PolicyAction, SensitiveWordEntity, WordLevel } from '@/api/gateway/types'
import { useResponsive } from '@/utils/useResponsive'

const message = useMessage()
const dialog = useDialog()
const { width } = useResponsive()

/* ================= KPI ================= */
const kpis = ref([
  { label: '敏感词总数', value: 0, unit: '条', icon: AlertCircleOutline, bg: '#eef2ff', color: '#6366f1' },
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
  category: string
  level: WordLevel | null
  enabled: number | null
}>({
  page: 1,
  size: 10,
  keyword: '',
  category: '',
  level: null,
  enabled: null,
})

const levelOptions: SelectOption[] = WORD_LEVEL_ORDER.map((v) => ({ label: WORD_LEVEL_LABELS[v], value: v }))
const enabledOptions: SelectOption[] = [
  { label: '启用', value: 1 },
  { label: '停用', value: 0 },
]

const searchCols = computed(() => (width.value < 768 ? 1 : width.value < 1200 ? 2 : 4))

function handleSearch() {
  query.page = 1
  fetchData()
}
function handleReset() {
  query.keyword = ''
  query.category = ''
  query.level = null
  query.enabled = null
  handleSearch()
}

/* ================= 表格 ================= */
const loading = ref(false)
const tableData = ref<SensitiveWordEntity[]>([])
const total = ref(0)

function buildParams(): Record<string, unknown> {
  const params: Record<string, unknown> = { page: query.page, size: query.size }
  if (query.keyword) params.keyword = query.keyword
  if (query.category) params.category = query.category
  if (query.level) params.level = query.level
  if (query.enabled != null) params.enabled = query.enabled
  return params
}

async function fetchData() {
  loading.value = true
  try {
    const res = await sensitiveWordApi.query(buildParams())
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

function renderActionTag(action?: PolicyAction | string | null) {
  if (!action) return '-'
  const tone = ACTION_TONES[action as PolicyAction] ?? 'default'
  return h(NTag, { size: 'small', round: true, type: tone, bordered: false }, { default: () => actionLabel(action) })
}

function renderLevelTag(level?: WordLevel | string | null) {
  if (!level) return '-'
  const key = level as WordLevel
  const tone = WORD_LEVEL_TONES[key] ?? 'default'
  const label = WORD_LEVEL_LABELS[key] ?? level
  return h(NTag, { size: 'small', round: true, type: tone, bordered: false }, { default: () => label })
}

const columns: DataTableColumns<SensitiveWordEntity> = [
  { title: '敏感词', key: 'word', minWidth: 180, render: (row) => h('span', { style: 'font-weight:600' }, row.word) },
  { title: '分类', key: 'category', width: 110, render: (row) => (row.category ? h(NTag, { size: 'small', round: true, bordered: false }, { default: () => row.category }) : '-') },
  { title: '等级', key: 'level', width: 100, render: (row) => renderLevelTag(row.level) },
  { title: '动作', key: 'action', width: 90, render: (row) => renderActionTag(row.action) },
  {
    title: '状态', key: 'enabled', width: 90,
    render: (row) =>
      h(NTag, { size: 'small', round: true, type: row.enabled === 1 ? 'success' : 'default', bordered: false }, { default: () => (row.enabled === 1 ? '启用' : '停用') }),
  },
  { title: '创建时间', key: 'createdAt', width: 150, className: 'tabular-nums', render: (row) => formatDateTime(row.createdAt) },
  {
    title: '操作', key: 'actions', width: 170, align: 'right',
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
interface SensitiveWordForm {
  word: string
  category: string
  level: WordLevel | string | null
  action: PolicyAction | string | null
  enabled: number
}

const modalShow = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const formRef = ref<FormInst | null>(null)

const formModel = reactive<SensitiveWordForm>({
  word: '',
  category: '',
  level: 'MEDIUM',
  action: 'BLOCK',
  enabled: 1,
})

const formRules: FormRules = {
  word: { required: true, message: '请输入敏感词', trigger: ['input', 'blur'] },
}

function openCreate() {
  editingId.value = null
  Object.assign(formModel, { word: '', category: '', level: 'MEDIUM', action: 'BLOCK', enabled: 1 })
  modalShow.value = true
}

function openEdit(row: SensitiveWordEntity) {
  editingId.value = row.id
  Object.assign(formModel, {
    word: row.word,
    category: row.category ?? '',
    level: row.level ?? 'MEDIUM',
    action: row.action ?? 'BLOCK',
    enabled: row.enabled ?? 1,
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
    const body: Partial<SensitiveWordEntity> = {
      word: formModel.word,
      category: formModel.category || null,
      level: formModel.level,
      action: formModel.action,
      enabled: formModel.enabled,
    }
    if (editingId.value) {
      await sensitiveWordApi.update(editingId.value, body)
    } else {
      await sensitiveWordApi.create(body)
    }
    message.success(editingId.value ? '敏感词已更新' : '敏感词已创建')
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
async function toggleEnabled(row: SensitiveWordEntity) {
  const next = row.enabled === 1 ? 0 : 1
  try {
    await sensitiveWordApi.update(row.id, { enabled: next })
    message.success(next === 1 ? '敏感词已启用' : '敏感词已停用')
    fetchData()
  } catch (err) {
    message.error(gwErrorMessage(err))
  }
}

function confirmDelete(row: SensitiveWordEntity) {
  dialog.warning({
    title: '删除确认',
    content: `确定要删除敏感词「${row.word}」吗？`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await sensitiveWordApi.remove(row.id)
        message.success('敏感词已删除')
        fetchData()
      } catch (err) {
        message.error(gwErrorMessage(err))
      }
    },
  })
}

/* ================= 批量导入 ================= */
const batchShow = ref(false)
const batchText = ref('')
const batchWarnings = ref<string[]>([])
const importing = ref(false)

function openBatch() {
  batchText.value = ''
  batchWarnings.value = []
  batchShow.value = true
}

function parseBatchText(text: string): Partial<SensitiveWordEntity>[] {
  const items: Partial<SensitiveWordEntity>[] = []
  for (const raw of text.split('\n')) {
    const line = raw.trim()
    if (!line) continue
    const parts = line.split('|').map((s) => s.trim())
    const word = parts[0]
    if (!word) continue
    const item: Partial<SensitiveWordEntity> = { word }
    if (parts[1]) item.category = parts[1]
    if (parts[2]) item.level = parts[2] as WordLevel | string
    if (parts[3]) item.action = parts[3] as PolicyAction | string
    items.push(item)
  }
  return items
}

async function handleImport() {
  const items = parseBatchText(batchText.value)
  if (items.length === 0) {
    message.warning('请至少输入一个敏感词')
    return
  }
  importing.value = true
  try {
    const res = await sensitiveWordApi.importBatch(items)
    batchWarnings.value = res.warnings || []
    message.success(`已提交 ${res.submitted}，新增 ${res.inserted}，跳过 ${res.skipped}`)
    if (batchWarnings.value.length === 0) {
      batchShow.value = false
    }
    fetchData()
  } catch (err) {
    message.error(gwErrorMessage(err))
  } finally {
    importing.value = false
  }
}

/* ================= 初始化 ================= */
onMounted(() => {
  fetchData()
})
</script>
