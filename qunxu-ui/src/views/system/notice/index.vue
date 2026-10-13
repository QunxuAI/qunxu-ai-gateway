<template>
  <div class="page">
    <n-card :bordered="false" class="card-block">
      <n-grid :cols="searchCols" :x-gap="14" :y-gap="6" item-responsive>
        <n-grid-item>
          <n-form-item label="标题" :show-feedback="false">
            <n-input v-model:value="query.title" placeholder="请输入标题" clearable @keyup.enter="handleSearch" />
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

    <n-card :bordered="false" class="card-block" title="公告列表">
      <template #header-extra>
        <n-button size="small" type="primary" v-permission="'system:notice:create'" @click="openCreate">
          <template #icon><n-icon :component="AddOutline" /></template>新增公告
        </n-button>
      </template>
      <n-data-table :columns="columns" :data="tableData" :loading="loading" :scroll-x="860" :bordered="false" />
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

    <n-modal v-model:show="detailShow" preset="card" title="通知详情" :style="{ width: '560px', maxWidth: '94vw' }" :bordered="false">
      <n-spin :show="detailLoading">
        <template v-if="detail">
          <n-descriptions :column="1" label-placement="left" bordered size="small">
            <n-descriptions-item label="标题">{{ detail.title }}</n-descriptions-item>
            <n-descriptions-item label="类型">
              <n-tag size="small" round :type="detail.type === 2 ? 'info' : 'default'">{{ detail.type === 2 ? '公告' : '通知' }}</n-tag>
            </n-descriptions-item>
            <n-descriptions-item label="状态">
              <n-tag size="small" round :type="detail.status === 1 ? 'success' : 'default'">{{ detail.status === 1 ? '开启' : '关闭' }}</n-tag>
            </n-descriptions-item>
            <n-descriptions-item label="创建时间">{{ formatTime(detail.createTime) }}</n-descriptions-item>
            <n-descriptions-item label="内容">
              <div class="notice-detail-content">{{ detail.content }}</div>
            </n-descriptions-item>
          </n-descriptions>
        </template>
      </n-spin>
      <template #footer>
        <n-space justify="end">
          <n-button @click="detailShow = false">关闭</n-button>
        </n-space>
      </template>
    </n-modal>

    <n-modal v-model:show="modalShow" preset="card" :title="editingId ? '编辑公告' : '新增公告'" :style="{ width: '560px', maxWidth: '94vw' }" :bordered="false" :mask-closable="false">
      <n-form ref="formRef" :model="formModel" :rules="formRules" label-placement="left" label-width="80">
        <n-form-item label="标题" path="title">
          <n-input v-model:value="formModel.title" placeholder="请输入公告标题" />
        </n-form-item>
        <n-form-item label="类型">
          <n-radio-group v-model:value="formModel.type">
            <n-radio-button :value="1">通知</n-radio-button>
            <n-radio-button :value="2">公告</n-radio-button>
          </n-radio-group>
        </n-form-item>
        <n-form-item label="内容" path="content">
          <n-input v-model:value="formModel.content" type="textarea" :rows="5" placeholder="请输入公告内容" />
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
import { computed, h, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  NCard, NButton, NIcon, NForm, NFormItem, NInput, NSelect, NRadioGroup, NRadioButton,
  NDataTable, NPagination, NModal, NTag, NSpace, NGrid, NGridItem, NDescriptions, NDescriptionsItem, NSpin,
  useMessage, useDialog,
  type FormInst, type FormRules, type DataTableColumns, type SelectOption,
} from 'naive-ui'
import { AddOutline } from '@vicons/ionicons5'
import { getNoticePage, getNotice, createNotice, updateNotice, deleteNotice, type Notice, type NoticePageQuery } from '@/api/system/notice'
import { useResponsive } from '@/utils/useResponsive'

const message = useMessage()
const dialog = useDialog()
const route = useRoute()
const { width } = useResponsive()

const query = reactive<NoticePageQuery>({ pageNo: 1, pageSize: 6, title: '', status: null })
const statusOptions: SelectOption[] = [
  { label: '开启', value: 1 },
  { label: '关闭', value: 0 },
]
const searchCols = computed(() => (width.value < 768 ? 1 : 2))

const loading = ref(false)
const tableData = ref<Notice[]>([])
const total = ref(0)

const columns: DataTableColumns<Notice> = [
  { title: '标题', key: 'title', minWidth: 220, render: (row) => h('div', { style: 'font-weight:600' }, row.title) },
  {
    title: '类型', key: 'type', width: 90,
    render: (row) => h(NTag, { size: 'small', round: true, type: row.type === 1 ? 'info' : 'warning' }, { default: () => (row.type === 1 ? '通知' : '公告') }),
  },
  { title: '内容', key: 'content', minWidth: 260, ellipsis: true },
  {
    title: '状态', key: 'status', width: 90,
    render: (row) => h(NTag, { size: 'small', round: true, type: row.status === 1 ? 'success' : 'default' }, { default: () => (row.status === 1 ? '开启' : '关闭') }),
  },
  { title: '创建时间', key: 'createTime', width: 150, className: 'tabular-nums' },
  {
    title: '操作', key: 'actions', width: 170, align: 'right',
    render: (row) =>
      h('div', { style: 'display:flex;justify-content:flex-end;gap:4px' }, [
        h(NButton, { size: 'small', text: true, type: 'info', onClick: () => openDetail(row.id) }, { default: () => '详情' }),
        h(NButton, { size: 'small', text: true, type: 'primary', onClick: () => openEdit(row) }, { default: () => '编辑' }),
        h(NButton, { size: 'small', text: true, type: 'error', onClick: () => confirmDelete(row) }, { default: () => '删除' }),
      ]),
  },
]

async function fetchData() {
  loading.value = true
  try {
    const res = await getNoticePage(query)
    tableData.value = res.list
    total.value = res.total
  } finally {
    loading.value = false
  }
}
function handleSearch() { query.pageNo = 1; fetchData() }
function handleReset() { query.title = ''; query.status = null; handleSearch() }
function onPageSizeChange() { query.pageNo = 1; fetchData() }

/* ================= 通知详情 ================= */

const detailShow = ref(false)
const detailLoading = ref(false)
const detail = ref<Notice | null>(null)

async function openDetail(id: number) {
  detailShow.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await getNotice(id)
  } catch {
    message.error('获取通知详情失败')
  } finally {
    detailLoading.value = false
  }
}

function formatTime(t?: string): string {
  return (t || '').slice(0, 16).replace('T', ' ')
}

const modalShow = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const formRef = ref<FormInst | null>(null)
const formModel = reactive({ title: '', type: 1 as 1 | 2, content: '', status: 1 as 0 | 1 })
const formRules: FormRules = {
  title: { required: true, message: '请输入公告标题', trigger: ['input', 'blur'] },
  content: { required: true, message: '请输入公告内容', trigger: ['input', 'blur'] },
}

function openCreate() {
  editingId.value = null
  Object.assign(formModel, { title: '', type: 1, content: '', status: 1 })
  modalShow.value = true
}
function openEdit(row: Notice) {
  editingId.value = row.id
  Object.assign(formModel, { title: row.title, type: row.type, content: row.content, status: row.status })
  modalShow.value = true
}
async function handleSave() {
  try {
    await formRef.value?.validate()
  } catch { return }
  saving.value = true
  try {
    if (editingId.value) {
      await updateNotice(editingId.value, { ...formModel })
    } else {
      await createNotice({ ...formModel })
    }
    message.success('保存成功')
    modalShow.value = false
    fetchData()
  } finally {
    saving.value = false
  }
}
function confirmDelete(row: Notice) {
  dialog.warning({
    title: '删除确认',
    content: `确定要删除公告「${row.title}」吗？`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await deleteNotice(row.id)
      message.success('删除成功')
      fetchData()
    },
  })
}

onMounted(async () => {
  fetchData()
  // 支持从顶栏通知面板点击某条通知跳转而来（携带 noticeId），自动打开该通知详情
  const noticeId = Number(route.query.noticeId)
  if (noticeId) {
    await openDetail(noticeId)
    // 清理 query，避免刷新页面重复弹窗
    window.history.replaceState({}, '', route.path)
  }
})
</script>

<style scoped>
.page { max-width: 1240px; margin: 0 auto; }
.card-block { margin-bottom: 16px; border-radius: 14px; }
.search-actions { display: flex; gap: 10px; margin-top: 6px; }
.pagination-wrap { display: flex; justify-content: flex-end; margin-top: 16px; }
.notice-detail-content {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.7;
  max-height: 260px;
  overflow-y: auto;
  padding: 4px 0;
}
@media (max-width: 768px) { .pagination-wrap { justify-content: center; } }
</style>
