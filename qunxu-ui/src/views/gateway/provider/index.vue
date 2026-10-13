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
            <n-input v-model:value="query.keyword" placeholder="名称 / 编码" clearable @keyup.enter="handleSearch" />
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

    <!-- 供应商列表 -->
    <n-card :bordered="false" class="card-block">
      <template #header>
        <div class="table-header">
          <span class="table-title">供应商列表</span>
          <n-tag size="small" round>共 {{ total }} 条</n-tag>
        </div>
      </template>
      <template #header-extra>
        <n-space :size="8" wrap>
          <n-button size="small" @click="fetchData">
            <template #icon><n-icon :component="RefreshOutline" /></template>刷新
          </n-button>
          <n-button size="small" type="primary" @click="openCreate">
            <template #icon><n-icon :component="AddOutline" /></template>新增供应商
          </n-button>
        </n-space>
      </template>

      <n-data-table
        :columns="columns"
        :data="tableData"
        :loading="loading"
        :scroll-x="1180"
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
      :title="editingId ? '编辑供应商' : '新增供应商'"
      :style="{ width: '640px', maxWidth: '94vw' }"
      :bordered="false"
      :mask-closable="false"
    >
      <n-form ref="formRef" :model="formModel" :rules="formRules" label-placement="left" label-width="84">
        <n-grid cols="1 640:2" :x-gap="14" :y-gap="0">
          <n-grid-item>
            <n-form-item label="名称" path="name">
              <n-input v-model:value="formModel.name" placeholder="供应商名称，如 DeepSeek" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="编码" path="code">
              <n-select
                v-model:value="formModel.code"
                :disabled="!!editingId"
                placeholder="请选择供应商编码"
                filterable
                :options="PROVIDER_TYPE_OPTIONS"
              />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="Base URL" path="baseUrl">
              <n-input v-model:value="formModel.baseUrl" placeholder="http(s)://" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="认证方式" path="authType">
              <n-select v-model:value="formModel.authType" placeholder="请选择认证方式" :options="authTypeOptions" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="优先级">
              <n-input-number v-model:value="formModel.priority" :min="0" style="width: 100%" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="状态">
              <n-radio-group v-model:value="formModel.status">
                <n-radio-button :value="1">启用</n-radio-button>
                <n-radio-button :value="0">停用</n-radio-button>
              </n-radio-group>
            </n-form-item>
          </n-grid-item>
        </n-grid>
        <n-form-item label="凭证" path="credentials">
          <n-input
            v-model:value="formModel.credentials"
            type="textarea"
            :rows="2"
            :placeholder="editingId ? '编辑时留空或填 *** 表示不修改；新增时必填' : '请输入凭证（API Key / Token 等）'"
          />
        </n-form-item>
        <n-form-item label="备注">
          <n-input v-model:value="formModel.remark" type="textarea" :rows="2" placeholder="备注信息（可选）" />
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
  NCard, NButton, NIcon, NForm, NFormItem, NInput, NInputNumber, NSelect,
  NRadioGroup, NRadioButton, NDataTable, NPagination, NModal, NTag, NSpace,
  NGrid, NGridItem, useMessage, useDialog, useThemeVars,
  type FormInst, type FormRules, type DataTableColumns, type SelectOption,
} from 'naive-ui'
import {
  SearchOutline, RefreshOutline, AddOutline,
  ServerOutline, CheckmarkCircleOutline, CloseCircleOutline,
} from '@vicons/ionicons5'
import { providerApi, gwErrorMessage, CREDENTIAL_MASKED } from '@/api/gateway'
import type { ProviderEntity } from '@/api/gateway/types'
import { AUTH_TYPE_LABELS, PROVIDER_TYPE_OPTIONS, authTypeLabel, formatDateTime } from '@/api/gateway/labels'
import { useResponsive } from '@/utils/useResponsive'

const message = useMessage()
const dialog = useDialog()
const themeVars = useThemeVars()
const { width } = useResponsive()

/* ================= KPI ================= */
const stats = reactive({ total: 0, enabled: 0, disabled: 0 })

const kpis = computed(() => [
  { label: '供应商总数', value: stats.total, unit: '个', icon: ServerOutline, bg: '#eef2ff', color: '#6366f1' },
  { label: '已启用', value: stats.enabled, unit: '个', icon: CheckmarkCircleOutline, bg: '#ecfdf5', color: '#10b981' },
  { label: '已停用', value: stats.disabled, unit: '个', icon: CloseCircleOutline, bg: '#fef2f2', color: '#ef4444' },
])

async function loadStats() {
  try {
    const list = await providerApi.all()
    stats.total = list.length
    stats.enabled = list.filter((p) => p.status === 1).length
    stats.disabled = list.filter((p) => p.status === 0).length
  } catch {
    // KPI 为可选展示，拉取失败不阻塞页面
  }
}

/* ================= 搜索 ================= */
const query = reactive({
  page: 1,
  size: 10,
  keyword: '',
  status: null as number | null,
})

const statusOptions: SelectOption[] = [
  { label: '启用', value: 1 },
  { label: '停用', value: 0 },
]

// 搜索表单响应式列数：<768 一列，其余两列
const searchCols = computed(() => (width.value < 768 ? 1 : 2))

function handleSearch() {
  query.page = 1
  fetchData()
}
function handleReset() {
  query.keyword = ''
  query.status = null
  handleSearch()
}

/* ================= 表格 ================= */
const loading = ref(false)
const tableData = ref<ProviderEntity[]>([])
const total = ref(0)

const columns: DataTableColumns<ProviderEntity> = [
  { title: '名称', key: 'name', minWidth: 150, render: (row) => h('span', { style: 'font-weight:600' }, row.name) },
  { title: '编码', key: 'code', width: 130, className: 'tabular-nums' },
  { title: 'Base URL', key: 'baseUrl', minWidth: 220, ellipsis: { tooltip: true } },
  {
    title: '认证方式', key: 'authType', width: 150,
    render: (row) => h(NTag, { size: 'small', round: true, type: 'default' }, { default: () => authTypeLabel(row.authType) }),
  },
  {
    title: '凭证', key: 'credentials', width: 90,
    render: (row) => {
      const has = row.credentials === CREDENTIAL_MASKED || !!row.credentials
      return h(NTag, { size: 'small', round: true, type: has ? 'success' : 'default' }, { default: () => (has ? '已设置' : '未设置') })
    },
  },
  {
    title: '状态', key: 'status', width: 90,
    render: (row) =>
      h(NTag, { size: 'small', round: true, type: row.status === 1 ? 'success' : 'default' }, { default: () => (row.status === 1 ? '启用' : '停用') }),
  },
  { title: '优先级', key: 'priority', width: 90, className: 'tabular-nums' },
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
    const res = await providerApi.query({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
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

const authTypeOptions: SelectOption[] = Object.entries(AUTH_TYPE_LABELS).map(([value, label]) => ({ label, value }))

const formModel = reactive({
  name: '',
  code: '',
  baseUrl: '',
  authType: 'bearer',
  credentials: '',
  status: 1,
  priority: 0,
  remark: '',
})

const formRules: FormRules = {
  name: { required: true, message: '请输入供应商名称', trigger: ['input', 'blur'] },
  code: { required: true, message: '请选择供应商编码', trigger: ['change', 'blur'] },
  baseUrl: { required: true, message: '请输入 Base URL', trigger: ['input', 'blur'] },
  credentials: {
    validator: (_rule, value: string) => {
      if (!editingId.value && !value) return new Error('新增时请输入凭证')
      return true
    },
    trigger: ['input', 'blur'],
  },
}

function openCreate() {
  editingId.value = null
  Object.assign(formModel, {
    name: '',
    code: '',
    baseUrl: '',
    authType: 'bearer',
    credentials: '',
    status: 1,
    priority: 0,
    remark: '',
  })
  modalShow.value = true
}

function openEdit(row: ProviderEntity) {
  editingId.value = row.id
  Object.assign(formModel, {
    name: row.name,
    code: row.code,
    baseUrl: row.baseUrl,
    authType: row.authType || 'bearer',
    credentials: '',
    status: row.status,
    priority: row.priority,
    remark: row.remark ?? '',
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
    const body: Partial<ProviderEntity> = {
      name: formModel.name,
      code: formModel.code,
      baseUrl: formModel.baseUrl,
      authType: formModel.authType,
      status: formModel.status,
      priority: formModel.priority,
      remark: formModel.remark,
    }
    if (editingId.value) {
      // 编辑时凭证留空或填 *** 表示不修改，不随请求体提交
      if (formModel.credentials && formModel.credentials !== CREDENTIAL_MASKED) {
        body.credentials = formModel.credentials
      }
      await providerApi.update(editingId.value, body)
      message.success('供应商已更新')
    } else {
      body.credentials = formModel.credentials
      await providerApi.create(body)
      message.success('供应商创建成功')
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
function confirmDelete(row: ProviderEntity) {
  dialog.warning({
    title: '删除确认',
    content: `确定要删除供应商「${row.name}」吗？删除后关联通道将不可用。`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await providerApi.remove(row.id)
        message.success('供应商已删除')
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
