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
          <n-form-item label="模块" :show-feedback="false">
            <n-input v-model:value="query.module" placeholder="请输入模块" clearable @keyup.enter="handleSearch" />
          </n-form-item>
        </n-grid-item>
        <n-grid-item>
          <n-form-item label="结果" :show-feedback="false">
            <n-select v-model:value="successValue" placeholder="全部结果" clearable :options="successOptions" />
          </n-form-item>
        </n-grid-item>
      </n-grid>
      <div class="search-actions">
        <n-button type="primary" @click="handleSearch">查询</n-button>
        <n-button @click="handleReset">重置</n-button>
      </div>
    </n-card>

    <n-card :bordered="false" class="card-block">
      <n-data-table :columns="columns" :data="tableData" :loading="loading" :scroll-x="1080" :bordered="false" />
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
import { CheckmarkOutline, CloseOutline } from '@vicons/ionicons5'
import { getOperateLogPage, TYPE_NAMES, type OperateLog, type OperateLogPageQuery } from '@/api/system/operateLog'
import { useResponsive } from '@/utils/useResponsive'

const { width } = useResponsive()
const query = reactive<OperateLogPageQuery>({ pageNo: 1, pageSize: 10, username: '', module: '', success: null })
const successOptions: SelectOption[] = [
  { label: '成功', value: '1' },
  { label: '失败', value: '0' },
]
/** naive-ui Select 的 Value 仅支持 string|number，这里做 boolean ↔ '1'/'0' 映射。 */
const successValue = computed({
  get: () => (query.success === null || query.success === undefined ? null : query.success ? '1' : '0'),
  set: (v: string | number | null) => {
    query.success = v === null ? null : String(v) === '1'
  },
})
const searchCols = computed(() => (width.value < 768 ? 1 : width.value < 1200 ? 2 : 3))

const loading = ref(false)
const tableData = ref<OperateLog[]>([])
const total = ref(0)

const columns: DataTableColumns<OperateLog> = [
  { title: '编号', key: 'id', width: 70, className: 'tabular-nums' },
  { title: '用户', key: 'username', width: 100 },
  { title: '模块', key: 'module', minWidth: 150 },
  { title: '操作名称', key: 'name', minWidth: 130 },
  {
    title: '操作类型', key: 'type', width: 90,
    render: (row) => h(NTag, { size: 'small', round: true, type: row.type === 4 ? 'error' : 'info' }, { default: () => TYPE_NAMES[row.type] || '其他' }),
  },
  {
    title: '结果', key: 'success', width: 90,
    render: (row) =>
      row.success
        ? h('div', { style: 'display:flex;align-items:center;gap:4px;color:#18a058' }, [
            h(NIcon, { size: 14 }, { default: () => h(CheckmarkOutline) }), '成功',
          ])
        : h('div', { style: 'display:flex;align-items:center;gap:4px;color:#d03050' }, [
            h(NIcon, { size: 14 }, { default: () => h(CloseOutline) }), '失败',
          ]),
  },
  { title: '耗时(ms)', key: 'duration', width: 90, className: 'tabular-nums' },
  { title: 'IP', key: 'ip', width: 120, className: 'tabular-nums' },
  { title: '操作时间', key: 'createTime', width: 160, className: 'tabular-nums' },
]

async function fetchData() {
  loading.value = true
  try {
    const res = await getOperateLogPage(query)
    tableData.value = res.list
    total.value = res.total
  } finally {
    loading.value = false
  }
}
function handleSearch() { query.pageNo = 1; fetchData() }
function handleReset() { query.username = ''; query.module = ''; query.success = null; handleSearch() }
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
