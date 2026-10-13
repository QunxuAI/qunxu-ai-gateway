<template>
  <div class="user-page">
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
          <n-form-item label="用户账号" :show-feedback="false">
            <n-input v-model:value="query.username" placeholder="请输入用户账号" clearable @keyup.enter="handleSearch" />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="手机号" :show-feedback="false">
            <n-input v-model:value="query.mobile" placeholder="请输入手机号" clearable @keyup.enter="handleSearch" />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="状态" :show-feedback="false">
            <n-select
              v-model:value="query.status"
              placeholder="全部状态"
              clearable
              :options="statusOptions"
            />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="创建时间" :show-feedback="false">
            <n-date-picker v-model:value="dateRange" type="daterange" clearable style="width: 100%" />
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

    <!-- 用户列表 -->
    <n-card :bordered="false" class="card-block">
      <template #header>
        <div class="table-header">
          <span class="table-title">用户列表</span>
          <n-tag size="small" round>共 {{ total }} 条</n-tag>
        </div>
      </template>
      <template #header-extra>
        <n-space :size="8" wrap>
          <n-button size="small">
            <template #icon><n-icon :component="DownloadOutline" /></template>导出
          </n-button>
          <n-button size="small" @click="fetchData">
            <template #icon><n-icon :component="RefreshOutline" /></template>刷新
          </n-button>
          <n-button size="small" type="primary" v-permission="'system:user:create'" @click="openCreate">
            <template #icon><n-icon :component="AddOutline" /></template>新增用户
          </n-button>
        </n-space>
      </template>

      <n-data-table
        :columns="columns"
        :data="tableData"
        :loading="loading"
        :scroll-x="980"
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
    <n-modal
      v-model:show="modalShow"
      preset="card"
      :title="editingId ? '编辑用户' : '新增用户'"
      :style="{ width: '600px', maxWidth: '94vw' }"
      :bordered="false"
      :mask-closable="false"
    >
      <n-form ref="formRef" :model="formModel" :rules="formRules" label-placement="left" label-width="84">
        <n-grid cols="1 640:2" :x-gap="14" :y-gap="0">
          <n-grid-item>
            <n-form-item label="用户账号" path="username">
              <n-input v-model:value="formModel.username" placeholder="登录账号" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="用户昵称" path="nickname">
              <n-input v-model:value="formModel.nickname" placeholder="显示名称" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="手机号" path="mobile">
              <n-input v-model:value="formModel.mobile" placeholder="手机号" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item :label="editingId ? '重置密码' : '登录密码'" path="password">
              <n-input
                v-model:value="formModel.password"
                type="password"
                show-password-on="click"
                :placeholder="editingId ? '留空则不修改' : '至少 6 位'"
              />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="所属部门">
              <n-select v-model:value="formModel.deptId" placeholder="请选择部门" :options="deptOptions" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="状态">
              <n-radio-group v-model:value="formModel.status">
                <n-radio-button :value="1">开启</n-radio-button>
                <n-radio-button :value="0">关闭</n-radio-button>
              </n-radio-group>
            </n-form-item>
          </n-grid-item>
        </n-grid>
        <n-form-item label="岗位">
          <n-select v-model:value="formModel.postIds" multiple placeholder="请选择岗位" :options="postOptions" />
        </n-form-item>
        <n-form-item label="角色">
          <n-select v-model:value="formModel.roleIds" multiple placeholder="请选择角色" :options="roleOptions" />
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
  NDatePicker, NDataTable, NPagination, NModal, NTag, NSpace, NGrid, NGridItem,
  useMessage, useDialog, useThemeVars, type FormInst, type FormRules, type DataTableColumns, type SelectOption,
} from 'naive-ui'
import {
  SearchOutline, RefreshOutline, AddOutline, DownloadOutline,
  PeopleOutline, CheckmarkCircleOutline, CloseCircleOutline, TimeOutline, BusinessOutline,
} from '@vicons/ionicons5'
import { getUserPage, createUser, updateUser, deleteUser, getUserStats, type User, type UserPageQuery, type UserSaveDTO } from '@/api/system/user'
import { useResponsive } from '@/utils/useResponsive'

const message = useMessage()
const dialog = useDialog()
const themeVars = useThemeVars()
const { width } = useResponsive()

/* ================= KPI ================= */
const kpis = ref([
  { label: '用户总数', value: 0, unit: '人', icon: PeopleOutline, bg: '#eef2ff', color: '#6366f1' },
  { label: '已启用', value: 0, unit: '人', icon: CheckmarkCircleOutline, bg: '#ecfdf5', color: '#10b981' },
  { label: '已禁用', value: 0, unit: '人', icon: CloseCircleOutline, bg: '#fef2f2', color: '#ef4444' },
  { label: '今日新增', value: 0, unit: '人', icon: TimeOutline, bg: '#eff6ff', color: '#3b82f6' },
])

async function loadStats() {
  const stats = await getUserStats()
  Object.assign(kpis.value[0], { value: stats.total })
  Object.assign(kpis.value[1], { value: stats.enabled })
  Object.assign(kpis.value[2], { value: stats.disabled })
  Object.assign(kpis.value[3], { value: stats.todayNew })
}

/* ================= 搜索 ================= */
const query = reactive<UserPageQuery>({
  pageNo: 1,
  pageSize: 6,
  username: '',
  mobile: '',
  status: null,
})
const dateRange = ref<[number, number] | null>(null)
const statusOptions: SelectOption[] = [
  { label: '开启', value: 1 },
  { label: '关闭', value: 0 },
]

// 搜索表单响应式列数：<768 一列，<1200 两列，其余四列
const searchCols = computed(() => (width.value < 768 ? 1 : width.value < 1200 ? 2 : 4))

function handleSearch() {
  query.pageNo = 1
  fetchData()
}
function handleReset() {
  query.username = ''
  query.mobile = ''
  query.status = null
  dateRange.value = null
  handleSearch()
}

/* ================= 表格 ================= */
const loading = ref(false)
const tableData = ref<User[]>([])
const total = ref(0)

function renderUserCell(row: User) {
  return h('div', { style: 'display:flex;align-items:center;gap:10px' }, [
    h('div', {
      style: `width:34px;height:34px;border-radius:10px;flex-shrink:0;display:flex;align-items:center;justify-content:center;color:#fff;font-weight:700;background:linear-gradient(135deg,${avatarColors[row.id % avatarColors.length]},#6366f1)`,
    }, row.nickname.charAt(0)),
    h('div', null, [
      h('div', { style: 'font-weight:600;line-height:1.3' }, row.nickname),
      h('div', { style: 'font-size:12px;color:#98a2b3' }, `@${row.username}`),
    ]),
  ])
}
const avatarColors = ['#6366f1', '#0ea5e9', '#10b981', '#f59e0b', '#ec4899', '#8b5cf6']

function renderTags(list: string[], type: 'info' | 'warning' | 'default') {
  return list.map((item) =>
    h(NTag, { size: 'small', round: true, type, style: 'margin-right:4px' }, { default: () => item }),
  )
}

const columns: DataTableColumns<User> = [
  { title: '用户', key: 'nickname', minWidth: 180, render: renderUserCell },
  { title: '手机号', key: 'mobile', width: 140, className: 'tabular-nums' },
  { title: '部门', key: 'deptName', width: 100, render: (row) => h(NTag, { size: 'small', round: true, bordered: false }, { default: () => row.deptName }) },
  { title: '岗位', key: 'postNames', minWidth: 150, render: (row) => renderTags(row.postNames, 'info') },
  { title: '角色', key: 'roleNames', minWidth: 120, render: (row) => renderTags(row.roleNames, 'warning') },
  {
    title: '状态', key: 'status', width: 90,
    render: (row) =>
      h(NTag, { size: 'small', round: true, type: row.status === 1 ? 'success' : 'default' }, { default: () => (row.status === 1 ? '开启' : '关闭') }),
  },
  { title: '创建时间', key: 'createTime', width: 160, className: 'tabular-nums' },
  {
    title: '操作', key: 'actions', width: 130, align: 'right',
    render: (row) =>
      h('div', { style: 'display:flex;justify-content:flex-end;gap:4px' }, [
        h(NButton, { size: 'small', text: true, type: 'primary', directives: [{ name: 'permission', value: 'system:user:update' }], onClick: () => openEdit(row) }, { default: () => '编辑' }),
        h(NButton, { size: 'small', text: true, type: 'error', directives: [{ name: 'permission', value: 'system:user:delete' }], onClick: () => confirmDelete(row) }, { default: () => '删除' }),
      ]),
  },
]

async function fetchData() {
  loading.value = true
  try {
    const res = await getUserPage({ ...query, createTime: dateRange.value })
    tableData.value = res.list
    total.value = res.total
  } finally {
    loading.value = false
  }
}

function onPageSizeChange() {
  query.pageNo = 1
  fetchData()
}

/* ================= 新增 / 编辑 ================= */
const modalShow = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const formRef = ref<FormInst | null>(null)

const deptOptions: SelectOption[] = [
  { label: '总公司', value: 1 },
  { label: '研发部', value: 2 },
  { label: '市场部', value: 3 },
]
const postOptions: SelectOption[] = [
  { label: '董事长', value: 1 },
  { label: '研发工程师', value: 2 },
  { label: '测试工程师', value: 3 },
  { label: '市场专员', value: 4 },
]
const roleOptions: SelectOption[] = [
  { label: '超级管理员', value: 1 },
  { label: '普通角色', value: 2 },
]

const formModel = reactive<UserSaveDTO>({
  username: '',
  nickname: '',
  mobile: '',
  password: '',
  deptId: 2,
  postIds: [],
  roleIds: [],
  status: 1,
})

const formRules: FormRules = {
  username: { required: true, message: '请输入用户账号', trigger: ['input', 'blur'] },
  nickname: { required: true, message: '请输入用户昵称', trigger: ['input', 'blur'] },
  password: {
    validator: (_rule, value: string) => {
      if (!editingId.value && !value) return new Error('请输入登录密码')
      if (value && value.length < 6) return new Error('密码至少 6 位')
      return true
    },
    trigger: ['input', 'blur'],
  },
}

function openCreate() {
  editingId.value = null
  Object.assign(formModel, { username: '', nickname: '', mobile: '', password: '', deptId: 2, postIds: [], roleIds: [], status: 1 })
  modalShow.value = true
}

function openEdit(row: User) {
  editingId.value = row.id
  Object.assign(formModel, {
    username: row.username,
    nickname: row.nickname,
    mobile: row.mobile,
    password: '',
    deptId: deptOptions.find((d) => d.label === row.deptName)?.value ?? 1,
    postIds: row.postNames.map((n) => postOptions.find((p) => p.label === n)?.value).filter(Boolean) as number[],
    roleIds: row.roleNames.map((n) => roleOptions.find((r) => r.label === n)?.value).filter(Boolean) as number[],
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
    if (editingId.value) {
      await updateUser(editingId.value, { ...formModel })
    } else {
      await createUser({ ...formModel })
    }
    message.success(editingId.value ? '用户信息已更新' : '用户创建成功')
    modalShow.value = false
    fetchData()
    loadStats()
  } finally {
    saving.value = false
  }
}

/* ================= 删除 ================= */
function confirmDelete(row: User) {
  dialog.warning({
    title: '删除确认',
    content: `确定要删除用户「${row.nickname}」吗？删除后该账号将无法登录系统。`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await deleteUser(row.id)
      message.success('用户已删除')
      fetchData()
      loadStats()
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
.user-page {
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
