<template>
  <div class="page">
    <!-- 搜索 -->
    <n-card :bordered="false" class="card-block">
      <n-grid :cols="searchCols" :x-gap="14" :y-gap="6" item-responsive>
        <n-grid-item>
          <n-form-item label="角色名称" :show-feedback="false">
            <n-input v-model:value="query.name" placeholder="请输入角色名称" clearable @keyup.enter="handleSearch" />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="角色编码" :show-feedback="false">
            <n-input v-model:value="query.code" placeholder="请输入角色编码" clearable @keyup.enter="handleSearch" />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="状态" :show-feedback="false">
            <n-select v-model:value="query.status" placeholder="全部状态" clearable :options="statusOptions" />
          </n-form-item>
        </n-grid-item>
      </n-grid>
      <div class="search-actions">
        <n-button type="primary" @click="handleSearch">查询</n-button>
        <n-button @click="handleReset">重置</n-button>
      </div>
    </n-card>

    <!-- 列表 -->
    <n-card :bordered="false" class="card-block" title="角色列表">
      <template #header-extra>
        <n-button size="small" type="primary" v-permission="'system:role:create'" @click="openCreate">
          <template #icon><n-icon :component="AddOutline" /></template>新增角色
        </n-button>
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

    <!-- 新增 / 编辑弹窗 -->
    <n-modal v-model:show="modalShow" preset="card" :title="editingId ? '编辑角色' : '新增角色'" :style="{ width: '480px', maxWidth: '94vw' }" :bordered="false" :mask-closable="false">
      <n-form ref="formRef" :model="formModel" :rules="formRules" label-placement="left" label-width="80">
        <n-form-item label="角色名称" path="name">
          <n-input v-model:value="formModel.name" placeholder="如：超级管理员" />
        </n-form-item>
        <n-form-item label="角色编码" path="code">
          <n-input v-model:value="formModel.code" placeholder="如：super_admin" />
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
        <n-form-item label="备注">
          <n-input v-model:value="formModel.remark" type="textarea" :rows="2" placeholder="备注信息" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="modalShow = false">取消</n-button>
          <n-button type="primary" :loading="saving" @click="handleSave">保存</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 分配菜单弹窗 -->
    <n-modal v-model:show="assignModalShow" preset="card" :title="assignRole ? `分配菜单 - ${assignRole.name}` : '分配菜单'" :style="{ width: '520px', maxWidth: '94vw' }" :bordered="false" :mask-closable="false">
      <n-tree
        ref="menuTreeRef"
        :data="menuTreeData"
        key-field="id"
        label-field="name"
        children-field="children"
        checkable
        cascade
        block-line
        :default-expand-all="true"
        :checked-keys="menuCheckedKeys"
        @update:checked-keys="onCheckedKeysChange"
        style="max-height: 56vh; overflow-y: auto"
      />
      <template #footer>
        <n-space justify="end">
          <n-button @click="assignModalShow = false">取消</n-button>
          <n-button type="primary" :loading="assignSaving" @click="handleAssignMenu">保存</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import {
  NCard, NButton, NIcon, NForm, NFormItem, NInput, NInputNumber, NSelect, NRadioGroup, NRadioButton,
  NDataTable, NPagination, NModal, NTag, NSpace, NGrid, NGridItem, NTree, useMessage, useDialog,
  type FormInst, type FormRules, type DataTableColumns, type SelectOption,
} from 'naive-ui'
import { AddOutline, RefreshOutline } from '@vicons/ionicons5'
import { getRolePage, createRole, updateRole, deleteRole, getRoleMenuList, assignRoleMenu, type Role, type RolePageQuery } from '@/api/system/role'
import { getMenuList } from '@/api/system/menu'
import { formatDateTime } from '@/api/gateway/labels'
import type { MenuVO } from '@/api/mock'
import { useResponsive } from '@/utils/useResponsive'

const message = useMessage()
const dialog = useDialog()
const { width } = useResponsive()

const query = reactive<RolePageQuery>({ pageNo: 1, pageSize: 6, name: '', code: '', status: null })
const statusOptions: SelectOption[] = [
  { label: '开启', value: 1 },
  { label: '关闭', value: 0 },
]
const searchCols = computed(() => (width.value < 768 ? 1 : width.value < 1200 ? 2 : 3))

const loading = ref(false)
const tableData = ref<Role[]>([])
const total = ref(0)

const columns: DataTableColumns<Role> = [
  { title: '角色名称', key: 'name', minWidth: 140, render: (row) => h('div', { style: 'font-weight:600' }, row.name) },
  { title: '角色编码', key: 'code', width: 150, className: 'tabular-nums' },
  { title: '排序', key: 'sort', width: 70, className: 'tabular-nums' },
  {
    title: '状态', key: 'status', width: 90,
    render: (row) => h(NTag, { size: 'small', round: true, type: row.status === 1 ? 'success' : 'default' }, { default: () => (row.status === 1 ? '开启' : '关闭') }),
  },
  { title: '备注', key: 'remark', minWidth: 160, ellipsis: true },
  { title: '创建时间', key: 'createTime', width: 170, className: 'tabular-nums', render: (row) => formatDateTime(row.createTime) },
  {
    title: '操作', key: 'actions', width: 200, align: 'right',
    render: (row) =>
      h('div', { style: 'display:flex;justify-content:flex-end;gap:4px' }, [
        h(NButton, { size: 'small', text: true, type: 'primary', directives: [{ name: 'permission', value: 'system:role:assign-menu' }], onClick: () => openAssignMenu(row) }, { default: () => '分配菜单' }),
        h(NButton, { size: 'small', text: true, type: 'primary', onClick: () => openEdit(row) }, { default: () => '编辑' }),
        h(NButton, { size: 'small', text: true, type: 'error', onClick: () => confirmDelete(row) }, { default: () => '删除' }),
      ]),
  },
]

async function fetchData() {
  loading.value = true
  try {
    const res = await getRolePage(query)
    tableData.value = res.list
    total.value = res.total
  } finally {
    loading.value = false
  }
}
function handleSearch() { query.pageNo = 1; fetchData() }
function handleReset() { query.name = ''; query.code = ''; query.status = null; handleSearch() }
function onPageSizeChange() { query.pageNo = 1; fetchData() }

const modalShow = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const formRef = ref<FormInst | null>(null)
const formModel = reactive({ name: '', code: '', sort: 1, status: 1 as 0 | 1, remark: '' })
const formRules: FormRules = {
  name: { required: true, message: '请输入角色名称', trigger: ['input', 'blur'] },
  code: { required: true, message: '请输入角色编码', trigger: ['input', 'blur'] },
}

function openCreate() {
  editingId.value = null
  Object.assign(formModel, { name: '', code: '', sort: 1, status: 1, remark: '' })
  modalShow.value = true
}
function openEdit(row: Role) {
  editingId.value = row.id
  Object.assign(formModel, { name: row.name, code: row.code, sort: row.sort, status: row.status, remark: row.remark })
  modalShow.value = true
}
async function handleSave() {
  try {
    await formRef.value?.validate()
  } catch { return }
  saving.value = true
  try {
    if (editingId.value) {
      await updateRole(editingId.value, { ...formModel })
    } else {
      await createRole({ ...formModel })
    }
    message.success('保存成功')
    modalShow.value = false
    fetchData()
  } finally {
    saving.value = false
  }
}
function confirmDelete(row: Role) {
  dialog.warning({
    title: '删除确认',
    content: `确定要删除角色「${row.name}」吗？`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await deleteRole(row.id)
      message.success('删除成功')
      fetchData()
    },
  })
}

/* ================= 分配菜单 ================= */
const assignModalShow = ref(false)
const assignRole = ref<Role | null>(null)
const assignSaving = ref(false)
const menuTreeRef = ref<InstanceType<typeof NTree> | null>(null)
const menuTreeData = ref<MenuVO[]>([])
const menuCheckedKeys = ref<number[]>([])

function onCheckedKeysChange(keys: Array<string | number>) {
  menuCheckedKeys.value = keys.filter((k): k is number => typeof k === 'number')
}

async function openAssignMenu(row: Role) {
  assignRole.value = row
  assignModalShow.value = true
  menuTreeData.value = []
  menuCheckedKeys.value = []
  const [tree, roleMenuIds] = await Promise.all([getMenuList(), getRoleMenuList(row.id)])
  menuTreeData.value = tree
  menuCheckedKeys.value = roleMenuIds
}

async function handleAssignMenu() {
  if (!assignRole.value) return
  const checked = menuTreeRef.value?.getCheckedData().keys ?? []
  const indeterminate = menuTreeRef.value?.getIndeterminateData().keys ?? []
  // 选中 + 半选（父级目录）一并提交，保证后端菜单树结构完整
  const menuIds = [...new Set([...checked, ...indeterminate])] as number[]
  assignSaving.value = true
  try {
    await assignRoleMenu(assignRole.value.id, menuIds)
    message.success('分配成功')
    assignModalShow.value = false
  } finally {
    assignSaving.value = false
  }
}

onMounted(fetchData)
</script>

<style scoped>
.page {
  max-width: 1240px;
  margin: 0 auto;
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
.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
@media (max-width: 768px) {
  .pagination-wrap { justify-content: center; }
}
</style>
