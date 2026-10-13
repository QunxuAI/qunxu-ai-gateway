<template>
  <div class="page">
    <n-card :bordered="false" class="card-block" title="菜单列表">
      <template #header-extra>
        <n-button size="small" type="primary" v-permission="'system:menu:create'" @click="openCreate(null)">
          <template #icon><n-icon :component="AddOutline" /></template>新增菜单
        </n-button>
      </template>
      <n-data-table
        :columns="columns"
        :data="tableData"
        :loading="loading"
        :scroll-x="900"
        :bordered="false"
        :row-key="(row: MenuVO) => row.id"
        :expanded-row-keys="expandedKeys"
        @update:expanded-row-keys="onExpandChange"
      />
    </n-card>

    <n-modal v-model:show="modalShow" preset="card" :title="editingId ? '编辑菜单' : '新增菜单'" :style="{ width: '560px', maxWidth: '94vw' }" :bordered="false" :mask-closable="false">
      <n-form ref="formRef" :model="formModel" :rules="formRules" label-placement="left" label-width="90">
        <n-form-item label="上级菜单" path="parentId">
          <n-tree-select
            v-model:value="formModel.parentId"
            :options="parentOptions"
            key-field="id"
            label-field="name"
            children-field="children"
            :default-expand-all="true"
            placeholder="选择上级菜单（根节点）"
          />
        </n-form-item>
        <n-form-item label="菜单类型">
          <n-radio-group v-model:value="formModel.type">
            <n-radio-button :value="1">目录</n-radio-button>
            <n-radio-button :value="2">菜单</n-radio-button>
            <n-radio-button :value="3">按钮</n-radio-button>
          </n-radio-group>
        </n-form-item>
        <n-form-item label="菜单名称" path="name">
          <n-input v-model:value="formModel.name" placeholder="如：用户管理" />
        </n-form-item>
        <n-form-item v-if="formModel.type !== 3" label="路由地址" path="path">
          <n-input v-model:value="formModel.path" placeholder="如：user（子级）或 /system（目录）" />
        </n-form-item>
        <n-form-item v-if="formModel.type === 2" label="组件路径" path="component">
          <n-input v-model:value="formModel.component" placeholder="如：system/user/index" />
        </n-form-item>
        <n-form-item v-if="formModel.type === 3" label="权限标识" path="permission">
          <n-input v-model:value="formModel.permission" placeholder="如：system:user:query" />
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
import { getMenuList, createMenu, updateMenu, deleteMenu } from '@/api/system/menu'
import type { MenuVO, MenuType } from '@/api/mock'

const message = useMessage()
const dialog = useDialog()

const loading = ref(false)
const tableData = ref<MenuVO[]>([])
const expandedKeys = ref<number[]>([])

const TYPE_TEXT: Record<MenuType, string> = { 1: '目录', 2: '菜单', 3: '按钮' }
const TYPE_TAG: Record<MenuType, 'default' | 'info' | 'success'> = { 1: 'info', 2: 'success', 3: 'default' }

const columns: DataTableColumns<MenuVO> = [
  {
    title: '菜单名称', key: 'name', minWidth: 220,
    render: (row) => h('div', { style: 'font-weight:600' }, row.name),
  },
  {
    title: '类型', key: 'type', width: 90,
    render: (row) => h(NTag, { size: 'small', round: true, type: TYPE_TAG[row.type] }, { default: () => TYPE_TEXT[row.type] }),
  },
  { title: '路由地址', key: 'path', width: 140, className: 'tabular-nums', render: (row) => row.path || '—' },
  { title: '组件路径', key: 'component', minWidth: 180, className: 'tabular-nums', render: (row) => row.component || '—' },
  { title: '权限标识', key: 'permission', minWidth: 180, className: 'tabular-nums', render: (row) => row.permission || '—' },
  { title: '排序', key: 'sort', width: 70, className: 'tabular-nums' },
  {
    title: '状态', key: 'status', width: 90,
    render: (row) => h(NTag, { size: 'small', round: true, type: row.status === 1 ? 'success' : 'default' }, { default: () => (row.status === 1 ? '开启' : '关闭') }),
  },
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

async function fetchData() {
  loading.value = true
  try {
    tableData.value = await getMenuList()
    collectKeys(tableData.value)
  } finally {
    loading.value = false
  }
}
function collectKeys(list: MenuVO[]) {
  list.forEach((m) => {
    if (m.children?.length) {
      expandedKeys.value.push(m.id)
      collectKeys(m.children)
    }
  })
}
function onExpandChange(keys: Array<string | number>) {
  expandedKeys.value = keys.filter((k): k is number => typeof k === 'number')
}

/* 上级菜单下拉（不含按钮节点） */
const parentOptions = ref<TreeSelectOption[]>([])
function buildParentOptions(list: MenuVO[]): TreeSelectOption[] {
  return list
    .filter((m) => m.type !== 3)
    .map((m) => ({
      id: m.id,
      name: m.name,
      children: m.children?.length ? buildParentOptions(m.children) : undefined,
    }))
}

const modalShow = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const formRef = ref<FormInst | null>(null)
const formModel = reactive<Partial<MenuVO>>({
  parentId: 0, type: 2, name: '', path: '', component: '', permission: '', sort: 1, status: 1,
})
const formRules: FormRules = {
  name: { required: true, message: '请输入菜单名称', trigger: ['input', 'blur'] },
}

function openCreate(parent?: MenuVO | null) {
  editingId.value = null
  Object.assign(formModel, { parentId: parent?.id ?? 0, type: 2, name: '', path: '', component: '', permission: '', sort: 1, status: 1 })
  modalShow.value = true
}
function openEdit(row: MenuVO) {
  editingId.value = row.id
  Object.assign(formModel, {
    parentId: row.parentId, type: row.type, name: row.name, path: row.path || '', component: row.component || '',
    permission: row.permission || '', sort: row.sort, status: row.status,
  })
  modalShow.value = true
}
async function handleSave() {
  try {
    await formRef.value?.validate()
  } catch { return }
  saving.value = true
  try {
    if (editingId.value) {
      await updateMenu(editingId.value, { ...formModel })
    } else {
      await createMenu({ ...formModel })
    }
    message.success('保存成功')
    modalShow.value = false
    fetchData()
  } finally {
    saving.value = false
  }
}
function confirmDelete(row: MenuVO) {
  dialog.warning({
    title: '删除确认',
    content: `确定要删除菜单「${row.name}」吗？其子级将一并删除。`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await deleteMenu(row.id)
      message.success('删除成功')
      fetchData()
    },
  })
}

onMounted(async () => {
  await fetchData()
  const tree = await getMenuList()
  parentOptions.value = buildParentOptions(tree)
})
</script>

<style scoped>
.page { max-width: 1240px; margin: 0 auto; }
.card-block { margin-bottom: 16px; border-radius: 14px; }
</style>
