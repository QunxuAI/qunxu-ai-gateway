<template>
  <div class="page">
    <n-card :bordered="false" class="card-block">
      <n-grid :cols="searchCols" :x-gap="14" :y-gap="6" item-responsive>
        <n-grid-item>
          <n-form-item label="字典名称" :show-feedback="false">
            <n-input v-model:value="query.name" placeholder="请输入字典名称" clearable @keyup.enter="handleSearch" />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="字典类型" :show-feedback="false">
            <n-input v-model:value="query.type" placeholder="请输入字典类型" clearable @keyup.enter="handleSearch" />
          </n-form-item>
        </n-grid-item>
      </n-grid>
      <div class="search-actions">
        <n-button type="primary" @click="handleSearch">查询</n-button>
        <n-button @click="handleReset">重置</n-button>
      </div>
    </n-card>

    <n-card :bordered="false" class="card-block" title="字典列表">
      <template #header-extra>
        <n-button size="small" type="primary" v-permission="'system:dict:create'" @click="openCreate">
          <template #icon><n-icon :component="AddOutline" /></template>新增字典
        </n-button>
      </template>
      <n-data-table :columns="columns" :data="tableData" :loading="loading" :scroll-x="900" :bordered="false" />
      <div class="pagination-wrap">
        <n-pagination
          v-model:page="query.pageNo"
          v-model:page-size="query.pageSize"
          :item-count="total"
          :page-sizes="[6, 10, 20, 50]"
          show-size-picker
          @update:page="fetchData"
          @update:page-size="onPageSizeChange"
        />
      </div>
    </n-card>

    <!-- 新增 / 编辑字典类型 -->
    <n-modal v-model:show="modalShow" preset="card" :title="editingId ? '编辑字典' : '新增字典'" :style="{ width: '460px', maxWidth: '94vw' }" :bordered="false" :mask-closable="false">
      <n-form ref="formRef" :model="formModel" :rules="formRules" label-placement="left" label-width="80">
        <n-form-item label="字典名称" path="name">
          <n-input v-model:value="formModel.name" placeholder="如：系统状态" />
        </n-form-item>
        <n-form-item label="字典类型" path="type">
          <n-input v-model:value="formModel.type" placeholder="如：common_status" />
        </n-form-item>
        <n-form-item label="状态">
          <n-radio-group v-model:value="formModel.status">
            <n-radio-button :value="1">开启</n-radio-button>
            <n-radio-button :value="0">关闭</n-radio-button>
          </n-radio-group>
        </n-form-item>
        <n-form-item label="备注">
          <n-input v-model:value="formModel.remark" type="textarea" :rows="2" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="modalShow = false">取消</n-button>
          <n-button type="primary" :loading="saving" @click="handleSave">保存</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 字典数据项下钻 -->
    <n-modal
      v-model:show="dataModalShow"
      preset="card"
      :title="`字典数据 · ${currentType?.name || ''}`"
      :style="{ width: '680px', maxWidth: '96vw' }"
      :bordered="false"
      :mask-closable="false"
    >
      <div class="data-toolbar">
        <n-button size="small" type="primary" v-permission="'system:dict:create'" @click="openDataCreate">
          <template #icon><n-icon :component="AddOutline" /></template>新增数据项
        </n-button>
      </div>
      <n-data-table :columns="dataColumns" :data="dataList" :loading="dataLoading" :scroll-x="620" :bordered="false" />
      <template #footer>
        <n-space justify="end">
          <n-button @click="dataModalShow = false">关闭</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 新增 / 编辑字典数据项 -->
    <n-modal v-model:show="dataFormShow" preset="card" :title="dataEditingId ? '编辑数据项' : '新增数据项'" :style="{ width: '460px', maxWidth: '94vw' }" :bordered="false" :mask-closable="false">
      <n-form ref="dataFormRef" :model="dataFormModel" :rules="dataFormRules" label-placement="left" label-width="80">
        <n-form-item label="标签" path="label">
          <n-input v-model:value="dataFormModel.label" placeholder="如：开启" />
        </n-form-item>
        <n-form-item label="键值" path="value">
          <n-input v-model:value="dataFormModel.value" placeholder="如：1" />
        </n-form-item>
        <n-form-item label="显示顺序" path="sort">
          <n-input-number v-model:value="dataFormModel.sort" :min="0" style="width: 100%" />
        </n-form-item>
        <n-form-item label="标签类型">
          <n-select v-model:value="dataFormModel.colorType" :options="colorOptions" />
        </n-form-item>
        <n-form-item label="状态">
          <n-radio-group v-model:value="dataFormModel.status">
            <n-radio-button :value="1">开启</n-radio-button>
            <n-radio-button :value="0">关闭</n-radio-button>
          </n-radio-group>
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="dataFormShow = false">取消</n-button>
          <n-button type="primary" :loading="dataSaving" @click="handleDataSave">保存</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import {
  NCard, NButton, NIcon, NForm, NFormItem, NInput, NInputNumber, NSelect, NRadioGroup, NRadioButton,
  NDataTable, NPagination, NModal, NTag, NSpace, NGrid, NGridItem, useMessage, useDialog,
  type FormInst, type FormRules, type DataTableColumns, type SelectOption,
} from 'naive-ui'
import { AddOutline } from '@vicons/ionicons5'
import {
  getDictTypePage, createDictType, updateDictType, deleteDictType,
  getDictDataPage, createDictData, updateDictData, deleteDictData,
  type DictType, type DictData,
} from '@/api/system/dict'
import { useResponsive } from '@/utils/useResponsive'

const message = useMessage()
const dialog = useDialog()
const { width } = useResponsive()

const searchCols = computed(() => (width.value < 768 ? 1 : 2))
const colorOptions: SelectOption[] = [
  { label: '默认', value: 'default' },
  { label: '成功', value: 'success' },
  { label: '警告', value: 'warning' },
  { label: '信息', value: 'info' },
  { label: '错误', value: 'error' },
]

/* ---------- 字典类型 ---------- */
const query = reactive({ pageNo: 1, pageSize: 6, name: '', type: '' })
const loading = ref(false)
const tableData = ref<DictType[]>([])
const total = ref(0)

const columns: DataTableColumns<DictType> = [
  { title: '字典名称', key: 'name', minWidth: 140, render: (row) => h('div', { style: 'font-weight:600' }, row.name) },
  { title: '字典类型', key: 'type', width: 160, className: 'tabular-nums' },
  {
    title: '状态', key: 'status', width: 90,
    render: (row) => h(NTag, { size: 'small', round: true, type: row.status === 1 ? 'success' : 'default' }, { default: () => (row.status === 1 ? '开启' : '关闭') }),
  },
  { title: '备注', key: 'remark', minWidth: 140, ellipsis: true },
  { title: '创建时间', key: 'createTime', width: 150, className: 'tabular-nums' },
  {
    title: '操作', key: 'actions', width: 190, align: 'right',
    render: (row) =>
      h('div', { style: 'display:flex;justify-content:flex-end;gap:4px' }, [
        h(NButton, { size: 'small', text: true, type: 'primary', onClick: () => openData(row) }, { default: () => '字典数据' }),
        h(NButton, { size: 'small', text: true, type: 'primary', onClick: () => openEdit(row) }, { default: () => '编辑' }),
        h(NButton, { size: 'small', text: true, type: 'error', onClick: () => confirmDelete(row) }, { default: () => '删除' }),
      ]),
  },
]

async function fetchData() {
  loading.value = true
  try {
    const res = await getDictTypePage(query)
    tableData.value = res.list
    total.value = res.total
  } finally {
    loading.value = false
  }
}
function handleSearch() { query.pageNo = 1; fetchData() }
function handleReset() { query.name = ''; query.type = ''; handleSearch() }
function onPageSizeChange() { query.pageNo = 1; fetchData() }

const modalShow = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const formRef = ref<FormInst | null>(null)
const formModel = reactive({ name: '', type: '', status: 1 as 0 | 1, remark: '' })
const formRules: FormRules = {
  name: { required: true, message: '请输入字典名称', trigger: ['input', 'blur'] },
  type: { required: true, message: '请输入字典类型', trigger: ['input', 'blur'] },
}

function openCreate() {
  editingId.value = null
  Object.assign(formModel, { name: '', type: '', status: 1, remark: '' })
  modalShow.value = true
}
function openEdit(row: DictType) {
  editingId.value = row.id
  Object.assign(formModel, { name: row.name, type: row.type, status: row.status, remark: row.remark })
  modalShow.value = true
}
async function handleSave() {
  try {
    await formRef.value?.validate()
  } catch { return }
  saving.value = true
  try {
    if (editingId.value) {
      await updateDictType(editingId.value, { ...formModel })
    } else {
      await createDictType({ ...formModel })
    }
    message.success('保存成功')
    modalShow.value = false
    fetchData()
  } finally {
    saving.value = false
  }
}
function confirmDelete(row: DictType) {
  dialog.warning({
    title: '删除确认',
    content: `确定要删除字典「${row.name}」吗？其数据项将一并删除。`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await deleteDictType(row.id)
      message.success('删除成功')
      fetchData()
    },
  })
}

/* ---------- 字典数据项 ---------- */
const dataModalShow = ref(false)
const dataLoading = ref(false)
const dataList = ref<DictData[]>([])
const currentType = ref<DictType | null>(null)
const dataQuery = reactive({ pageNo: 1, pageSize: 10, label: '' })

const dataColumns: DataTableColumns<DictData> = [
  {
    title: '标签', key: 'label', width: 140,
    render: (row) => h(NTag, { size: 'small', round: true, type: (row.colorType as 'success' | 'warning' | 'info' | 'error') || 'default' }, { default: () => row.label }),
  },
  { title: '键值', key: 'value', width: 100, className: 'tabular-nums' },
  { title: '排序', key: 'sort', width: 70, className: 'tabular-nums' },
  {
    title: '状态', key: 'status', width: 90,
    render: (row) => h(NTag, { size: 'small', round: true, type: row.status === 1 ? 'success' : 'default' }, { default: () => (row.status === 1 ? '开启' : '关闭') }),
  },
  { title: '创建时间', key: 'createTime', width: 150, className: 'tabular-nums' },
  {
    title: '操作', key: 'actions', width: 120, align: 'right',
    render: (row) =>
      h('div', { style: 'display:flex;justify-content:flex-end;gap:4px' }, [
        h(NButton, { size: 'small', text: true, type: 'primary', onClick: () => openDataEdit(row) }, { default: () => '编辑' }),
        h(NButton, { size: 'small', text: true, type: 'error', onClick: () => confirmDataDelete(row) }, { default: () => '删除' }),
      ]),
  },
]

async function openData(row: DictType) {
  currentType.value = row
  dataQuery.label = ''
  dataModalShow.value = true
  await fetchDataItems()
}
async function fetchDataItems() {
  if (!currentType.value) return
  dataLoading.value = true
  try {
    const res = await getDictDataPage({ ...dataQuery, dictType: currentType.value.type })
    dataList.value = res.list
  } finally {
    dataLoading.value = false
  }
}

const dataFormShow = ref(false)
const dataEditingId = ref<number | null>(null)
const dataSaving = ref(false)
const dataFormRef = ref<FormInst | null>(null)
const dataFormModel = reactive({ label: '', value: '', sort: 1, colorType: 'default', status: 1 as 0 | 1 })
const dataFormRules: FormRules = {
  label: { required: true, message: '请输入标签', trigger: ['input', 'blur'] },
  value: { required: true, message: '请输入键值', trigger: ['input', 'blur'] },
}

function openDataCreate() {
  dataEditingId.value = null
  Object.assign(dataFormModel, { label: '', value: '', sort: 1, colorType: 'default', status: 1 })
  dataFormShow.value = true
}
function openDataEdit(row: DictData) {
  dataEditingId.value = row.id
  Object.assign(dataFormModel, { label: row.label, value: row.value, sort: row.sort, colorType: row.colorType || 'default', status: row.status })
  dataFormShow.value = true
}
async function handleDataSave() {
  try {
    await dataFormRef.value?.validate()
  } catch { return }
  dataSaving.value = true
  try {
    const payload = { ...dataFormModel, dictType: currentType.value!.type }
    if (dataEditingId.value) {
      await updateDictData(dataEditingId.value, payload)
    } else {
      await createDictData(payload)
    }
    message.success('保存成功')
    dataFormShow.value = false
    fetchDataItems()
  } finally {
    dataSaving.value = false
  }
}
function confirmDataDelete(row: DictData) {
  dialog.warning({
    title: '删除确认',
    content: `确定要删除数据项「${row.label}」吗？`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await deleteDictData(row.id)
      message.success('删除成功')
      fetchDataItems()
    },
  })
}

onMounted(fetchData)
</script>

<style scoped>
.page { max-width: 1240px; margin: 0 auto; }
.card-block { margin-bottom: 16px; border-radius: 14px; }
.search-actions { display: flex; gap: 10px; margin-top: 6px; }
.pagination-wrap { display: flex; justify-content: flex-end; margin-top: 16px; }
.data-toolbar { display: flex; justify-content: flex-end; margin-bottom: 12px; }
@media (max-width: 768px) { .pagination-wrap { justify-content: center; } }
</style>
