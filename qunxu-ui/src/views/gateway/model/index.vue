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
          <n-form-item label="关键词" :show-feedback="false">
            <n-input v-model:value="query.keyword" placeholder="名称 / 描述" clearable @keyup.enter="handleSearch" />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="能力" :show-feedback="false">
            <n-select v-model:value="query.capability" placeholder="全部能力" clearable :options="CAPABILITY_OPTIONS" />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="状态" :show-feedback="false">
            <n-select v-model:value="query.status" placeholder="全部状态" clearable :options="statusOptions" />
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

    <!-- 逻辑模型列表 -->
    <n-card :bordered="false" class="card-block">
      <template #header>
        <div class="table-header">
          <span class="table-title">逻辑模型列表</span>
          <n-tag size="small" round>共 {{ total }} 条</n-tag>
        </div>
      </template>
      <template #header-extra>
        <n-space :size="8" wrap>
          <n-button size="small" @click="fetchData">
            <template #icon><n-icon :component="RefreshOutline" /></template>刷新
          </n-button>
          <n-button size="small" type="primary" @click="openCreate">
            <template #icon><n-icon :component="AddOutline" /></template>新增模型
          </n-button>
        </n-space>
      </template>

      <n-data-table
        :columns="columns"
        :data="tableData"
        :loading="loading"
        :scroll-x="940"
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
      :title="editingId ? '编辑逻辑模型' : '新增逻辑模型'"
      :style="{ width: '560px', maxWidth: '94vw' }"
      :bordered="false"
      :mask-closable="false"
    >
      <n-form ref="formRef" :model="formModel" :rules="formRules" label-placement="left" label-width="84">
        <n-grid cols="1 640:2" :x-gap="14" :y-gap="0">
          <n-grid-item>
            <n-form-item label="名称" path="name">
              <n-input v-model:value="formModel.name" placeholder="逻辑模型名称，如 通用对话" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="能力" path="capability">
              <n-select v-model:value="formModel.capability" :disabled="!!editingId" placeholder="请选择能力" :options="CAPABILITY_OPTIONS" />
            </n-form-item>
          </n-grid-item>
        </n-grid>
        <n-form-item label="描述">
          <n-input v-model:value="formModel.description" type="textarea" :rows="3" placeholder="模型用途 / 说明（可选）" />
        </n-form-item>
        <n-form-item label="状态">
          <n-radio-group v-model:value="formModel.status">
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
  NCard, NButton, NIcon, NForm, NFormItem, NInput, NSelect,
  NRadioGroup, NRadioButton, NDataTable, NPagination, NModal, NTag, NSpace,
  NGrid, NGridItem, useMessage, useDialog, useThemeVars,
  type FormInst, type FormRules, type DataTableColumns, type SelectOption,
} from 'naive-ui'
import {
  SearchOutline, RefreshOutline, AddOutline,
  CubeOutline, CheckmarkCircleOutline, CloseCircleOutline,
} from '@vicons/ionicons5'
import { logicalModelApi, gwErrorMessage } from '@/api/gateway'
import type { Capability, LogicalModelEntity } from '@/api/gateway/types'
import { CAPABILITY_OPTIONS, capabilityLabel, formatDateTime } from '@/api/gateway/labels'
import { useResponsive } from '@/utils/useResponsive'

const message = useMessage()
const dialog = useDialog()
const themeVars = useThemeVars()
const { width } = useResponsive()

/* ================= KPI ================= */
const stats = reactive({ total: 0, enabled: 0, disabled: 0 })

const kpis = computed(() => [
  { label: '模型总数', value: stats.total, unit: '个', icon: CubeOutline, bg: '#eef2ff', color: '#6366f1' },
  { label: '已启用', value: stats.enabled, unit: '个', icon: CheckmarkCircleOutline, bg: '#ecfdf5', color: '#10b981' },
  { label: '已停用', value: stats.disabled, unit: '个', icon: CloseCircleOutline, bg: '#fef2f2', color: '#ef4444' },
])

async function loadStats() {
  try {
    const list = await logicalModelApi.all()
    stats.total = list.length
    stats.enabled = list.filter((m) => m.status === 1).length
    stats.disabled = list.filter((m) => m.status === 0).length
  } catch {
    // KPI 为可选展示，拉取失败不阻塞页面
  }
}

/* ================= 搜索 ================= */
const query = reactive({
  page: 1,
  size: 10,
  keyword: '',
  capability: null as Capability | null,
  status: null as number | null,
})

const statusOptions: SelectOption[] = [
  { label: '启用', value: 1 },
  { label: '停用', value: 0 },
]

// 搜索表单响应式列数：<768 一列，<1200 两列，其余三列
const searchCols = computed(() => (width.value < 768 ? 1 : width.value < 1200 ? 2 : 3))

function handleSearch() {
  query.page = 1
  fetchData()
}
function handleReset() {
  query.keyword = ''
  query.capability = null
  query.status = null
  handleSearch()
}

/* ================= 表格 ================= */
const loading = ref(false)
const tableData = ref<LogicalModelEntity[]>([])
const total = ref(0)

const columns: DataTableColumns<LogicalModelEntity> = [
  { title: '名称', key: 'name', minWidth: 160, render: (row) => h('span', { style: 'font-weight:600' }, row.name) },
  {
    title: '能力', key: 'capability', width: 160,
    render: (row) => h(NTag, { size: 'small', round: true, type: 'info' }, { default: () => capabilityLabel(row.capability) }),
  },
  { title: '描述', key: 'description', minWidth: 240, ellipsis: { tooltip: true } },
  {
    title: '状态', key: 'status', width: 90,
    render: (row) =>
      h(NTag, { size: 'small', round: true, type: row.status === 1 ? 'success' : 'default' }, { default: () => (row.status === 1 ? '启用' : '停用') }),
  },
  { title: '创建时间', key: 'createdAt', width: 160, className: 'tabular-nums', render: (row) => formatDateTime(row.createdAt) },
  {
    title: '操作', key: 'actions', width: 110, align: 'right',
    render: (row) =>
      h('div', { style: 'display:flex;justify-content:flex-end;gap:4px' }, [
        h(NButton, { size: 'small', text: true, type: 'primary', onClick: () => openEdit(row) }, { default: () => '编辑' }),
        h(NButton, { size: 'small', text: true, type: 'error', onClick: () => confirmDelete(row) }, { default: () => '删除' }),
      ]),
  },
]

async function fetchData() {
  loading.value = true
  try {
    const res = await logicalModelApi.query({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      capability: query.capability ?? undefined,
      status: query.status ?? undefined,
    })
    tableData.value = res.items
    total.value = res.total
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

/* ================= 新增 / 编辑 ================= */
const modalShow = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const formRef = ref<FormInst | null>(null)

const formModel = reactive({
  name: '',
  capability: 'CHAT' as Capability,
  description: '',
  status: 1,
})

const formRules: FormRules = {
  name: { required: true, message: '请输入模型名称', trigger: ['input', 'blur'] },
  capability: { required: true, message: '请选择模型能力', trigger: ['change', 'blur'] },
}

function openCreate() {
  editingId.value = null
  Object.assign(formModel, {
    name: '',
    capability: 'CHAT' as Capability,
    description: '',
    status: 1,
  })
  modalShow.value = true
}

function openEdit(row: LogicalModelEntity) {
  editingId.value = row.id
  Object.assign(formModel, {
    name: row.name,
    capability: row.capability,
    description: row.description ?? '',
    status: row.status,
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
    const body: Partial<LogicalModelEntity> = {
      name: formModel.name,
      capability: formModel.capability,
      description: formModel.description,
      status: formModel.status,
    }
    if (editingId.value) {
      await logicalModelApi.update(editingId.value, body)
      message.success('逻辑模型已更新')
    } else {
      await logicalModelApi.create(body)
      message.success('逻辑模型创建成功')
    }
    modalShow.value = false
    fetchData()
    loadStats()
  } catch (err) {
    message.error(gwErrorMessage(err))
  } finally {
    saving.value = false
  }
}

/* ================= 删除 ================= */
function confirmDelete(row: LogicalModelEntity) {
  dialog.warning({
    title: '删除确认',
    content: `确定要删除逻辑模型「${row.name}」吗？删除后关联通道将不可用。`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await logicalModelApi.remove(row.id)
        message.success('逻辑模型已删除')
        fetchData()
        loadStats()
      } catch (err) {
        message.error(gwErrorMessage(err))
      }
    },
  })
}

/* ================= 初始化 ================= */
onMounted(() => {
  fetchData()
  loadStats()
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
