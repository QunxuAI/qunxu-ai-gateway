<template>
  <div class="page">
    <n-card :bordered="false" class="card-block">
      <n-grid :cols="searchCols" :x-gap="14" :y-gap="6" item-responsive>
        <n-grid-item>
          <n-form-item label="用户" :show-feedback="false">
            <n-input v-model:value="query.username" placeholder="请输入用户" clearable @keyup.enter="handleSearch" />
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

    <n-card :bordered="false" class="card-block">
      <n-data-table :columns="columns" :data="tableData" :loading="loading" :scroll-x="980" :bordered="false" />
      <div class="pagination-wrap">
        <n-pagination
          v-model:page="query.pageNo"
          v-model:page-size="query.pageSize"
          :item-count="total"
          :page-sizes="[10, 20, 50]"
          show-size-picker
          @update:page="fetchData"
          @update:page-size="onPageSizeChange"
        />
      </div>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import {
  NCard, NButton, NIcon, NInput, NSelect, NDataTable, NPagination, NTag, NGrid, NGridItem,
  type DataTableColumns, type SelectOption,
} from 'naive-ui'
import { EnterOutline, ExitOutline } from '@vicons/ionicons5'
import { getLoginLogPage, type LoginLog, type LoginLogPageQuery } from '@/api/system/loginLog'
import { useResponsive } from '@/utils/useResponsive'

const { width } = useResponsive()
const query = reactive<LoginLogPageQuery>({ pageNo: 1, pageSize: 10, username: '', status: null })
const statusOptions: SelectOption[] = [
  { label: '成功', value: 1 },
  { label: '失败', value: 0 },
]
const searchCols = computed(() => (width.value < 768 ? 1 : 2))

const loading = ref(false)
const tableData = ref<LoginLog[]>([])
const total = ref(0)

const columns: DataTableColumns<LoginLog> = [
  { title: '编号', key: 'id', width: 70, className: 'tabular-nums' },
  { title: '用户', key: 'username', width: 120 },
  {
    title: '类型', key: 'type', width: 90,
    render: (row) =>
      h('div', { style: 'display:flex;align-items:center;gap:4px' }, [
        h(NIcon, { size: 14 }, { default: () => h(row.type === 1 ? EnterOutline : ExitOutline) }),
        row.type === 1 ? '登录' : '登出',
      ]),
  },
  { title: 'IP', key: 'ip', width: 130, className: 'tabular-nums' },
  { title: '浏览器', key: 'userAgent', minWidth: 240, ellipsis: true },
  {
    title: '状态', key: 'status', width: 90,
    render: (row) => h(NTag, { size: 'small', round: true, type: row.status === 1 ? 'success' : 'error' }, { default: () => (row.status === 1 ? '成功' : '失败') }),
  },
  { title: '登录时间', key: 'createTime', width: 160, className: 'tabular-nums' },
]

async function fetchData() {
  loading.value = true
  try {
    const res = await getLoginLogPage(query)
    tableData.value = res.list
    total.value = res.total
  } finally {
    loading.value = false
  }
}
function handleSearch() { query.pageNo = 1; fetchData() }
function handleReset() { query.username = ''; query.status = null; handleSearch() }
function onPageSizeChange() { query.pageNo = 1; fetchData() }

onMounted(fetchData)
</script>

<style scoped>
.page { max-width: 1240px; margin: 0 auto; }
.card-block { margin-bottom: 16px; border-radius: 14px; }
.search-actions { display: flex; gap: 10px; margin-top: 6px; }
.pagination-wrap { display: flex; justify-content: flex-end; margin-top: 16px; }
@media (max-width: 768px) { .pagination-wrap { justify-content: center; } }
</style>
