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

    <!-- 列表 -->
    <n-card :bordered="false" class="card-block">
      <template #header>
        <div class="table-header">
          <span class="table-title">{{ isAdmin ? '全部用户令牌' : '我的令牌' }}</span>
          <n-tag v-if="isAdmin" size="small" round type="success">管理员可管理所有用户的令牌</n-tag>
        </div>
      </template>
      <template #header-extra>
        <n-space :size="8" align="center">
          <n-input v-model:value="query.keyword" clearable placeholder="名称 / 所有者" size="small" style="width: 160px" @keyup.enter="fetchData" />
          <n-input-number
            v-if="isAdmin"
            v-model:value="query.userId"
            :show-button="false"
            placeholder="用户ID"
            size="small"
            style="width: 100px"
            clearable
          />
          <n-button size="small" type="primary" @click="openCreate">
            <template #icon><n-icon :component="AddOutline" /></template>新建令牌
          </n-button>
          <n-button size="small" :loading="loading" @click="fetchData">
            <template #icon><n-icon :component="RefreshOutline" /></template>刷新
          </n-button>
        </n-space>
      </template>

      <n-data-table
        :columns="columns"
        :data="rows"
        :loading="loading"
        :scroll-x="isAdmin ? 1360 : 1160"
        :bordered="false"
        :pagination="pagination"
        @update:page="onPageChange"
      />
    </n-card>

    <!-- 创建 / 编辑弹窗 -->
    <n-modal v-model:show="modalShow" preset="card" :title="modalMode === 'create' ? '新建令牌' : '编辑令牌'" style="width: 520px">
      <n-form ref="formRef" :model="formModel" :rules="formRules" label-placement="left" label-width="92px">
        <n-form-item label="名称" path="name">
          <n-input v-model:value="formModel.name" placeholder="如：生产环境 / 测试环境" clearable />
        </n-form-item>
        <n-form-item v-if="isAdmin && modalMode === 'create'" label="归属用户">
          <n-input
            v-model:value="formModel.userName"
            placeholder="留空 = 当前用户（管理员可填他人用户名）"
            clearable
          />
        </n-form-item>
        <n-form-item label="过期时间" path="expiresAt">
          <n-date-picker
            v-model:value="formModel.expiresAtTs"
            type="datetime"
            clearable
            style="width: 100%"
            :is-date-disabled="disablePastDate"
          />
          <span class="field-hint">留空表示永不过期</span>
        </n-form-item>
        <n-form-item label="备注">
          <n-input v-model:value="formModel.remark" type="textarea" :rows="2" maxlength="512" show-count placeholder="用途说明（可选）" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="modalShow = false">取消</n-button>
          <n-button type="primary" :loading="saving" @click="save">确定</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 明文令牌一次性展示 -->
    <n-modal v-model:show="plainShow" preset="card" title="令牌已生成" style="width: 560px">
      <n-alert type="warning" :bordered="false" class="plain-alert">
        令牌明文<b>仅此一次展示</b>，关闭后无法再查看。请立即复制并妥善保存；如遗失请重新创建或轮换。
      </n-alert>
      <div class="plain-key">
        <code>{{ plainKey }}</code>
        <n-button size="small" type="primary" ghost @click="copyPlainKey">
          <template #icon><n-icon :component="CopyOutline" /></template>复制
        </n-button>
      </div>
      <template #footer>
        <n-space justify="end">
          <n-button @click="plainShow = false">我已保存</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import {
  NCard, NButton, NIcon, NTag, NSpace, NDataTable, NModal, NForm, NFormItem, NInput,
  NInputNumber, NPopconfirm, NAlert, NDatePicker,
  useMessage, useThemeVars, type DataTableColumns, type FormInst, type FormRules,
} from 'naive-ui'
import {
  AddOutline, RefreshOutline, KeyOutline, TimeOutline, SyncOutline, CopyOutline, CreateOutline, TrashOutline,
} from '@vicons/ionicons5'
import { apiKeyApi, gwErrorMessage } from '@/api/gateway'
import type { ApiKeyEntity } from '@/api/gateway/types'
import { formatDateTime } from '@/api/gateway/labels'
import { useUserStore } from '@/stores/user'

const message = useMessage()
const userStore = useUserStore()
const themeVars = useThemeVars()

/** 管理员（super_admin 角色）可管理所有用户，普通用户仅自己的（后端强制，前端据此隐藏控件）。 */
const isAdmin = computed(() => userStore.roles.includes('super_admin'))

/* ================= 数据 ================= */
const loading = ref(false)
const rows = ref<ApiKeyEntity[]>([])
const total = ref(0)
const page = ref(1)
const size = 20

const query = reactive<{ keyword: string | null; userId: number | null }>({
  keyword: null,
  userId: null,
})

async function fetchData() {
  loading.value = true
  try {
    const params: Record<string, unknown> = { page: page.value, size }
    if (query.keyword) params.keyword = query.keyword
    if (isAdmin.value && query.userId) params.userId = query.userId
    const res = await apiKeyApi.query(params)
    rows.value = res.items
    total.value = res.total
  } catch (err) {
    message.error(gwErrorMessage(err))
  } finally {
    loading.value = false
  }
}

const pagination = computed(() => ({
  page: page.value,
  pageSize: size,
  itemCount: total.value,
  showSizePicker: false,
  prefix: ({ itemCount }: { itemCount?: number }) => `共 ${itemCount ?? 0} 条`,
}))

function onPageChange(next: number) {
  page.value = next
  fetchData()
}

/* ================= KPI ================= */
const kpiAll = ref<ApiKeyEntity[]>([])
async function fetchKpis() {
  try {
    const params: Record<string, unknown> = {}
    if (isAdmin.value && query.userId) params.userId = query.userId
    kpiAll.value = await apiKeyApi.all(params)
  } catch {
    /* KPI 失败不影响列表 */
  }
}
const kpis = computed(() => {
  const list = kpiAll.value
  const expired = list.filter((k) => k.expiresAt && new Date(k.expiresAt).getTime() < Date.now()).length
  return [
    { label: isAdmin.value ? '全部令牌' : '我的令牌', value: list.length, unit: '个', icon: KeyOutline, bg: '#eef2ff', color: '#6366f1' },
    { label: '启用中', value: list.filter((k) => k.status === 1).length, unit: '个', icon: AddOutline, bg: '#ecfdf5', color: '#10b981' },
    { label: '已过期', value: expired, unit: '个', icon: TimeOutline, bg: '#fffbeb', color: '#f59e0b' },
  ]
})

/* ================= 状态标签 ================= */
function statusTone(v: number | undefined | null): 'success' | 'error' | 'default' {
  if (v === 1) return 'success'
  if (v === 0) return 'error'
  return 'default'
}
function statusText(v: number | undefined | null): string {
  if (v === 1) return '启用'
  if (v === 0) return '停用'
  return '-'
}

/* ================= 表格 ================= */
function renderActions(row: ApiKeyEntity) {
  return h(
    NSpace,
    { size: 4, wrap: false },
    {
      default: () => [
        h(
          NButton,
          { size: 'tiny', quaternary: true, type: 'primary', onClick: () => openEdit(row) },
          { default: () => '编辑', icon: () => h(NIcon, { size: 14 }, { default: () => h(CreateOutline) }) },
        ),
        h(
          NButton,
          { size: 'tiny', quaternary: true, type: 'warning', onClick: () => onRotate(row) },
          { default: () => '轮换', icon: () => h(NIcon, { size: 14 }, { default: () => h(SyncOutline) }) },
        ),
        h(
          NPopconfirm,
          { onPositiveClick: () => onDelete(row) },
          {
            trigger: () =>
              h(
                NButton,
                { size: 'tiny', quaternary: true, type: 'error' },
                { default: () => '删除', icon: () => h(NIcon, { size: 14 }, { default: () => h(TrashOutline) }) },
              ),
            default: () => `确定删除令牌「${row.name}」？删除后立即失效且不可恢复。`,
          },
        ),
      ],
    },
  )
}

const columns = computed<DataTableColumns<ApiKeyEntity>>(() => {
  const cols: DataTableColumns<ApiKeyEntity> = [
    { title: '名称', key: 'name', width: 150, ellipsis: { tooltip: true } },
    {
      title: '令牌',
      key: 'keyPrefix',
      width: 190,
      render: (row: ApiKeyEntity) =>
        h(
          NSpace,
          { size: 6, align: 'center' },
          {
            default: () => [
              h('code', { class: 'key-code' }, row.keyPrefix + '••••••••••••'),
              h(
                NButton,
                { size: 'tiny', quaternary: true, type: 'primary', onClick: () => copyPrefix(row) },
                { default: () => '复制前缀' },
              ),
            ],
          },
        ),
    },
    {
      title: '过期时间',
      key: 'expiresAt',
      width: 160,
      render: (row: ApiKeyEntity) =>
        h('span', { class: row.expiresAt && new Date(row.expiresAt).getTime() < Date.now() ? 'expired' : '' }, formatDateTime(row.expiresAt)),
    },
    { title: '最近使用', key: 'lastUsedAt', width: 160, render: (row: ApiKeyEntity) => formatDateTime(row.lastUsedAt) },
    { title: '备注', key: 'remark', ellipsis: { tooltip: true }, render: (row: ApiKeyEntity) => row.remark || '-' },
    { title: '创建时间', key: 'createdAt', width: 160, render: (row: ApiKeyEntity) => formatDateTime(row.createdAt) },
    { title: '操作', key: 'actions', width: 220, fixed: 'right', render: renderActions },
  ]
  if (isAdmin.value) {
    cols.splice(1, 0, {
      title: '所有者',
      key: 'userName',
      width: 150,
      ellipsis: { tooltip: true },
      render: (row: ApiKeyEntity) =>
        h(NSpace, { size: 4, align: 'center' }, {
          default: () => [
            h('span', {}, row.userName || '-'),
            h('span', { class: 'owner-id' }, `#${row.userId}`),
          ],
        }),
    })
  }
  return cols
})

function copyPrefix(row: ApiKeyEntity) {
  navigator.clipboard?.writeText(row.keyPrefix).then(
    () => message.success('已复制令牌前缀'),
    () => message.error('复制失败，请手动复制'),
  )
}

/* ================= 创建 / 编辑 ================= */
const modalShow = ref(false)
const modalMode = ref<'create' | 'edit'>('create')
const saving = ref(false)
const formRef = ref<FormInst | null>(null)
const formModel = reactive<{
  id?: number
  name: string
  userName: string
  expiresAtTs: number | null
  status: boolean
  remark: string
}>({
  id: undefined,
  name: '',
  userName: '',
  expiresAtTs: null,
  status: true,
  remark: '',
})

const formRules: FormRules = {
  name: { required: true, message: '请输入名称', trigger: ['blur', 'input'] },
}

function disablePastDate(ts: number): boolean {
  return ts < Date.now() - 24 * 60 * 60 * 1000
}

function openCreate() {
  formModel.id = undefined
  formModel.name = ''
  formModel.userName = ''
  formModel.expiresAtTs = null
  formModel.status = true
  formModel.remark = ''
  modalMode.value = 'create'
  modalShow.value = true
}

function openEdit(row: ApiKeyEntity) {
  formModel.id = row.id
  formModel.name = row.name
  formModel.userName = ''
  formModel.expiresAtTs = row.expiresAt ? new Date(row.expiresAt).getTime() : null
  formModel.status = row.status === 1
  formModel.remark = row.remark ?? ''
  modalMode.value = 'edit'
  modalShow.value = true
}

/** 过期时间（时间戳）→ 后端 ISO-8601 LocalDateTime 字符串；null 表示永不过期。 */
function normalizeExpiresAt(ts: number | null): string | null {
  if (ts == null) return null
  const d = new Date(ts)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

async function save() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  saving.value = true
  const body: Record<string, unknown> = {
    name: formModel.name.trim(),
    remark: formModel.remark.trim() || null,
    expiresAt: normalizeExpiresAt(formModel.expiresAtTs),
    status: formModel.status ? 1 : 0,
  }
  try {
    if (modalMode.value === 'create') {
      if (isAdmin.value && formModel.userName.trim()) {
        body.userName = formModel.userName.trim()
      }
      const created = await apiKeyApi.create(body)
      showPlain(created.apiKey, created.key.name)
    } else if (formModel.id != null) {
      await apiKeyApi.update(formModel.id, body)
      message.success('已保存')
    }
    modalShow.value = false
    await Promise.all([fetchData(), fetchKpis()])
  } catch (err) {
    message.error(gwErrorMessage(err))
  } finally {
    saving.value = false
  }
}

/* ================= 明文展示 ================= */
const plainShow = ref(false)
const plainKey = ref('')
function showPlain(apiKey: string, name: string) {
  plainKey.value = apiKey
  plainShow.value = true
  message.success(`令牌「${name}」已生成，请立即保存`)
}
function copyPlainKey() {
  navigator.clipboard?.writeText(plainKey.value).then(
    () => message.success('已复制到剪贴板'),
    () => message.error('复制失败，请手动复制'),
  )
}

/* ================= 轮换 / 删除 ================= */
async function onRotate(row: ApiKeyEntity) {
  try {
    const created = await apiKeyApi.rotate(row.id)
    showPlain(created.apiKey, row.name)
    await Promise.all([fetchData(), fetchKpis()])
  } catch (err) {
    message.error(gwErrorMessage(err))
  }
}

async function onDelete(row: ApiKeyEntity) {
  try {
    await apiKeyApi.remove(row.id)
    message.success('已删除')
    await Promise.all([fetchData(), fetchKpis()])
  } catch (err) {
    message.error(gwErrorMessage(err))
  }
}

/* ================= 初始化 ================= */
onMounted(() => {
  fetchData()
  fetchKpis()
})
</script>

<style scoped>
.gateway-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* KPI 卡片 */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 14px;
}
.kpi-card {
  display: flex;
  align-items: center;
  gap: 12px;
  background: v-bind('themeVars.cardColor');
  border: 1px solid rgba(128, 138, 158, 0.14);
  border-radius: 12px;
  padding: 16px;
}
.kpi-icon {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.kpi-info {
  min-width: 0;
}
.kpi-label {
  font-size: 12px;
  color: #98a2b3;
}
.kpi-num {
  font-size: 22px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.kpi-num small {
  font-size: 12px;
  font-weight: 400;
  color: #98a2b3;
  margin-left: 2px;
}

/* 卡片 */
.card-block {
  background: v-bind('themeVars.cardColor');
  border-radius: 12px;
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

/* 表格辅助 */
.key-code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  background: rgba(128, 138, 158, 0.08);
  padding: 2px 6px;
  border-radius: 4px;
  color: #475467;
}
.owner-id {
  font-size: 12px;
  color: #98a2b3;
}
.expired {
  color: #ef4444;
}

/* 明文展示 */
.plain-alert {
  margin-bottom: 12px;
}
.plain-key {
  display: flex;
  align-items: center;
  gap: 10px;
  background: rgba(128, 138, 158, 0.06);
  border: 1px dashed rgba(128, 138, 158, 0.3);
  border-radius: 8px;
  padding: 10px 12px;
}
.plain-key code {
  flex: 1;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
  word-break: break-all;
  color: #1d2939;
}

/* 表单提示 */
.field-hint {
  font-size: 12px;
  color: #98a2b3;
  margin-left: 8px;
  white-space: nowrap;
}

@media (max-width: 768px) {
  .kpi-grid {
    grid-template-columns: 1fr 1fr;
    gap: 10px;
  }
}
</style>
