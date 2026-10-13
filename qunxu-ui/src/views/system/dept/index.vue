<template>
  <div class="page">
    <n-card :bordered="false" class="card-block" title="部门列表">
      <template #header-extra>
        <n-button size="small" type="primary" v-permission="'system:dept:create'" @click="openCreate(null)">
          <template #icon><n-icon :component="AddOutline" /></template>新增部门
        </n-button>
      </template>
      <n-data-table
        :columns="columns"
        :data="tableData"
        :loading="loading"
        :scroll-x="820"
        :bordered="false"
        :row-key="(row: Dept) => row.id"
        :expanded-row-keys="expandedKeys"
        @update:expanded-row-keys="onExpandChange"
      />
    </n-card>

    <n-modal v-model:show="modalShow" preset="card" :title="editingId ? '编辑部门' : '新增部门'" :style="{ width: '500px', maxWidth: '94vw' }" :bordered="false" :mask-closable="false">
      <n-form ref="formRef" :model="formModel" :rules="formRules" label-placement="left" label-width="80">
        <n-form-item label="上级部门" path="parentId">
          <n-tree-select
            v-model:value="formModel.parentId"
            :options="parentOptions"
            key-field="id"
            label-field="name"
            children-field="children"
            :default-expand-all="true"
            placeholder="选择上级部门（根节点）"
          />
        </n-form-item>
        <n-form-item label="部门名称" path="name">
          <n-input v-model:value="formModel.name" placeholder="如：研发部" />
        </n-form-item>
        <n-form-item label="负责人">
          <n-input v-model:value="formModel.leader" placeholder="负责人姓名" />
        </n-form-item>
        <n-form-item label="联系电话">
          <n-input v-model:value="formModel.phone" placeholder="联系电话" />
        </n-form-item>
        <n-form-item label="显示顺序" path="sort">
          <n-input-number v-model:value="formModel.sort" :min="0" style="width: 100%" />
        </n-form-item>
        <n-form-item label="状态">
          <n-radio-group v-model:value="formModel.status">
            <n-radio-button :value="1">开启</n-radio-button>
            <n-radio-button :value="0">关闭</n-radio-button>
          </n-radio-group>
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="modalShow = false">取消</n-button>
          <n-button type="primary" :loading="saving" @click="handleSave">保存</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { h, onMounted, reactive, ref } from 'vue'
import {
  NCard, NButton, NIcon, NForm, NFormItem, NInput, NInputNumber, NRadioGroup, NRadioButton, NTreeSelect,
  NDataTable, NModal, NTag, NSpace, useMessage, useDialog,
  type FormInst, type FormRules, type DataTableColumns, type TreeSelectOption,
} from 'naive-ui'
import { AddOutline } from '@vicons/ionicons5'
import { getDeptList, createDept, updateDept, deleteDept, type Dept } from '@/api/system/dept'

const message = useMessage()
const dialog = useDialog()

const loading = ref(false)
const tableData = ref<Dept[]>([])

const columns: DataTableColumns<Dept> = [
  { title: '部门名称', key: 'name', minWidth: 200, render: (row) => h('div', { style: 'font-weight:600' }, row.name) },
  { title: '负责人', key: 'leader', width: 110, render: (row) => row.leader || '—' },
  { title: '联系电话', key: 'phone', width: 140, className: 'tabular-nums', render: (row) => row.phone || '—' },
  { title: '排序', key: 'sort', width: 70, className: 'tabular-nums' },
  {
    title: '状态', key: 'status', width: 90,
    render: (row) => h(NTag, { size: 'small', round: true, type: row.status === 1 ? 'success' : 'default' }, { default: () => (row.status === 1 ? '开启' : '关闭') }),
  },
  { title: '创建时间', key: 'createTime', width: 150, className: 'tabular-nums' },
  {
    title: '操作', key: 'actions', width: 160, align: 'right',
    render: (row) =>
      h('div', { style: 'display:flex;justify-content:flex-end;gap:4px' }, [
        h(NButton, { size: 'small', text: true, type: 'primary', onClick: () => openCreate(row) }, { default: () => '新增下级' }),
        h(NButton, { size: 'small', text: true, type: 'primary', onClick: () => openEdit(row) }, { default: () => '编辑' }),
        h(NButton, { size: 'small', text: true, type: 'error', onClick: () => confirmDelete(row) }, { default: () => '删除' }),
      ]),
  },
]

const expandedKeys = ref<number[]>([])

async function fetchData() {
  loading.value = true
  try {
    tableData.value = await getDeptList()
    collectKeys(tableData.value)
  } finally {
    loading.value = false
  }
}
function collectKeys(list: Dept[]) {
  list.forEach((d) => {
    if (d.children?.length) {
      expandedKeys.value.push(d.id)
      collectKeys(d.children)
    }
  })
}
function onExpandChange(keys: Array<string | number>) {
  expandedKeys.value = keys.filter((k): k is number => typeof k === 'number')
}

const parentOptions = ref<TreeSelectOption[]>([])
function buildParentOptions(list: Dept[]): TreeSelectOption[] {
  return list.map((d) => ({
    id: d.id,
    name: d.name,
    children: d.children?.length ? buildParentOptions(d.children) : undefined,
  }))
}

const modalShow = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const formRef = ref<FormInst | null>(null)
const formModel = reactive({ parentId: 0, name: '', leader: '', phone: '', sort: 1, status: 1 as 0 | 1 })
const formRules: FormRules = {
  name: { required: true, message: '请输入部门名称', trigger: ['input', 'blur'] },
}

function openCreate(parent?: Dept | null) {
  editingId.value = null
  Object.assign(formModel, { parentId: parent?.id ?? 0, name: '', leader: '', phone: '', sort: 1, status: 1 })
  modalShow.value = true
}
function openEdit(row: Dept) {
  editingId.value = row.id
  Object.assign(formModel, { parentId: row.parentId, name: row.name, leader: row.leader, phone: row.phone, sort: row.sort, status: row.status })
  modalShow.value = true
}
async function handleSave() {
  try {
    await formRef.value?.validate()
  } catch { return }
  saving.value = true
  try {
    if (editingId.value) {
      await updateDept(editingId.value, { ...formModel })
    } else {
      await createDept({ ...formModel })
    }
    message.success('保存成功')
    modalShow.value = false
    fetchData()
  } finally {
    saving.value = false
  }
}
function confirmDelete(row: Dept) {
  dialog.warning({
    title: '删除确认',
    content: `确定要删除部门「${row.name}」吗？其子部门将一并删除。`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await deleteDept(row.id)
      message.success('删除成功')
      fetchData()
    },
  })
}

onMounted(async () => {
  await fetchData()
  const tree = await getDeptList()
  parentOptions.value = buildParentOptions(tree)
})
</script>

<style scoped>
.page { max-width: 1240px; margin: 0 auto; }
.card-block { margin-bottom: 16px; border-radius: 14px; }
</style>
