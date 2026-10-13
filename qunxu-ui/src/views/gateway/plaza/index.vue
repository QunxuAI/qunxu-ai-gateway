<template>
  <div class="model-plaza">
    <!-- 顶部标题区 -->
    <div class="plaza-head">
      <div class="plaza-title-wrap">
        <h2 class="plaza-title">模型广场</h2>
      </div>
      <div class="plaza-tools">
        <n-input
          v-model:value="keyword"
          placeholder="搜索模型名称 / 描述"
          clearable
          class="plaza-search"
          @update:value="onSearch"
        >
          <template #prefix>
            <n-icon :component="SearchOutline" />
          </template>
        </n-input>
        <n-button quaternary circle title="刷新" @click="fetchData">
          <template #icon>
            <n-icon :size="18"><RefreshOutline /></n-icon>
          </template>
        </n-button>
      </div>
    </div>

    <div class="plaza-body">
      <!-- 左侧筛选区 -->
      <aside class="plaza-side">
        <n-card :bordered="false" class="side-card" :segmented="false">
          <template #header>
            <span class="side-title">模型能力</span>
          </template>
          <div class="filter-list">
            <button
              v-for="(opt, i) in capabilityFilters"
              :key="i"
              class="filter-item"
              :class="{ active: capability === opt.value }"
              @click="selectCapability(opt.value)"
            >
              <span class="filter-label">{{ opt.label }}</span>
              <span class="filter-count">{{ opt.count }}</span>
            </button>
          </div>
        </n-card>

        <n-card :bordered="false" class="side-card" :segmented="false">
          <template #header>
            <span class="side-title">状态</span>
          </template>
          <div class="filter-list">
            <button
              v-for="(opt, i) in statusFilters"
              :key="i"
              class="filter-item"
              :class="{ active: status === opt.value }"
              @click="selectStatus(opt.value)"
            >
              <span class="filter-label">{{ opt.label }}</span>
              <span class="filter-count">{{ opt.count }}</span>
            </button>
          </div>
        </n-card>
      </aside>

      <!-- 右侧卡片区 -->
      <section class="plaza-main">
        <div class="plaza-stat">
          <n-tag size="small" round :bordered="false">共 {{ filtered.length }} 个模型</n-tag>
          <span v-if="capability || status !== null" class="stat-hint">已按筛选条件过滤</span>
        </div>

        <n-spin :show="loading">
          <div class="card-grid">
            <div v-for="m in pagedModels" :key="m.id" class="model-card">
              <div class="card-top">
                <div class="model-icon" :style="cardIconStyle(m)">
                  <img
                    v-if="vendorOf(m)"
                    :src="vendorOf(m)!.icon"
                    class="vendor-icon"
                    :alt="vendorOf(m)!.name"
                    :title="vendorOf(m)!.name"
                  />
                  <n-icon v-else :size="20"><component :is="capabilityIcon(m.capability)" /></n-icon>
                </div>
                <div class="model-name-wrap">
                  <div class="model-name" :title="m.name">{{ m.name }}</div>
                  <div class="model-id">model: {{ m.name }}</div>
                </div>
                <n-tag
                  size="small"
                  round
                  :type="m.status === 1 ? 'success' : 'default'"
                  :bordered="false"
                  class="status-tag"
                >
                  {{ m.status === 1 ? '启用' : '停用' }}
                </n-tag>
              </div>

              <p class="model-desc">{{ m.description || '暂无描述' }}</p>

              <div class="card-foot">
                <n-tag size="small" round type="info" :bordered="false">{{ capabilityLabel(m.capability) }}</n-tag>
                <span class="model-time" :title="formatDateTime(m.createdAt)">创建于 {{ formatDateTime(m.createdAt) }}</span>
              </div>

              <div class="card-actions">
                <n-button size="tiny" text type="primary" @click="copyModelName(m)">
                  <template #icon><n-icon :component="CopyOutline" /></template>复制名称
                </n-button>
              </div>
            </div>
          </div>

          <n-empty
            v-if="!loading && filtered.length === 0"
            description="没有符合条件的模型"
            class="plaza-empty"
          />
        </n-spin>

        <div v-if="filtered.length > pageSize" class="pagination-wrap">
          <n-pagination
            v-model:page="page"
            :item-count="filtered.length"
            :page-size="pageSize"
            :page-sizes="[12, 24, 48]"
            show-size-picker
            @update:page-size="onPageSizeChange"
          />
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { NCard, NButton, NIcon, NInput, NTag, NSpin, NEmpty, NPagination, useMessage, useThemeVars } from 'naive-ui'
import {
  SearchOutline, RefreshOutline, CopyOutline,
  ChatbubbleEllipsesOutline, CubeOutline, SwapVerticalOutline,
  VolumeHighOutline, MicOutline, ImageOutline,
} from '@vicons/ionicons5'
import type { Component } from 'vue'
import { logicalModelApi, gwErrorMessage } from '@/api/gateway'
import type { Capability, LogicalModelEntity } from '@/api/gateway/types'
import { CAPABILITY_LABELS, capabilityLabel, formatDateTime } from '@/api/gateway/labels'
import { detectVendor, type VendorMeta } from '@/api/gateway/vendors'

const message = useMessage()
const themeVars = useThemeVars()

/* ================= 数据 ================= */
const loading = ref(false)
const allModels = ref<LogicalModelEntity[]>([])

async function fetchData() {
  loading.value = true
  try {
    allModels.value = await logicalModelApi.all()
  } catch (err) {
    message.error(gwErrorMessage(err))
  } finally {
    loading.value = false
  }
}

/* ================= 筛选 ================= */
const keyword = ref('')
const capability = ref<Capability | null>(null)
const status = ref<number | null>(null)
const page = ref(1)
const pageSize = ref(12)

const CAPABILITY_ICONS: Record<Capability, Component> = {
  CHAT: ChatbubbleEllipsesOutline,
  EMBEDDING: CubeOutline,
  RERANK: SwapVerticalOutline,
  TTS: VolumeHighOutline,
  STT: MicOutline,
  IMAGE: ImageOutline,
}

const CAPABILITY_TONES: Record<Capability, { bg: string; color: string }> = {
  CHAT: { bg: '#eef2ff', color: '#6366f1' },
  EMBEDDING: { bg: '#ecfeff', color: '#0891b2' },
  RERANK: { bg: '#f5f3ff', color: '#8b5cf6' },
  TTS: { bg: '#fffbeb', color: '#d97706' },
  STT: { bg: '#ecfdf5', color: '#059669' },
  IMAGE: { bg: '#fdf2f8', color: '#db2777' },
}

function capabilityIcon(cap?: string): Component {
  return (cap && CAPABILITY_ICONS[cap as Capability]) || CubeOutline
}
function iconStyle(cap?: string): Record<string, string> {
  const t = (cap && CAPABILITY_TONES[cap as Capability]) || CAPABILITY_TONES.CHAT
  return { background: t.bg, color: t.color }
}

/* ================= 开发商图标（优先品牌图标，未知开发商回退能力图标） ================= */

function vendorOf(m: LogicalModelEntity): VendorMeta | null {
  return detectVendor(m.name)
}
function cardIconStyle(m: LogicalModelEntity): Record<string, string> {
  const v = vendorOf(m)
  if (v) {
    // 品牌色浅底（hex + 12% 透明度），图标即品牌色
    return { background: `${v.color}1f`, color: v.color }
  }
  return iconStyle(m.capability)
}

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return allModels.value.filter((m) => {
    if (capability.value && m.capability !== capability.value) return false
    if (status.value !== null && m.status !== status.value) return false
    if (kw) {
      const haystack = `${m.name} ${m.description ?? ''}`.toLowerCase()
      if (!haystack.includes(kw)) return false
    }
    return true
  })
})

const pagedModels = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return filtered.value.slice(start, start + pageSize.value)
})

/* 左侧筛选项：全部 + 各能力，附带数量 */
const capabilityFilters = computed(() => [
  { label: '全部', value: null as Capability | null, count: allModels.value.length },
  ...(Object.keys(CAPABILITY_LABELS) as Capability[]).map((cap) => ({
    label: CAPABILITY_LABELS[cap],
    value: cap,
    count: allModels.value.filter((m) => m.capability === cap).length,
  })),
])

const statusFilters = computed(() => [
  { label: '全部', value: null as number | null, count: allModels.value.length },
  { label: '启用', value: 1, count: allModels.value.filter((m) => m.status === 1).length },
  { label: '停用', value: 0, count: allModels.value.filter((m) => m.status === 0).length },
])

function selectCapability(v: Capability | null) {
  capability.value = v
  page.value = 1
}
function selectStatus(v: number | null) {
  status.value = v
  page.value = 1
}
function onSearch() {
  page.value = 1
}
function onPageSizeChange() {
  page.value = 1
}

/* 筛选条件变化时回到第一页（含全部模型重排） */
watch(filtered, () => {
  const maxPage = Math.max(1, Math.ceil(filtered.value.length / pageSize.value))
  if (page.value > maxPage) page.value = maxPage
})

/* ================= 操作 ================= */
async function copyModelName(m: LogicalModelEntity) {
  try {
    await navigator.clipboard.writeText(m.name)
    message.success(`已复制模型名：${m.name}`)
  } catch {
    message.error('复制失败，请手动复制')
  }
}

/* ================= 初始化 ================= */
onMounted(fetchData)
</script>

<style scoped>
.model-plaza {
  max-width: 1240px;
  margin: 0 auto;
}

/* ===== 顶部标题 ===== */
.plaza-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}
.plaza-title {
  font-size: 20px;
  font-weight: 700;
  margin: 0;
  line-height: 1.3;
}
.plaza-tools {
  display: flex;
  align-items: center;
  gap: 6px;
}
.plaza-search {
  width: 240px;
}

/* ===== 主体：左筛右卡 ===== */
.plaza-body {
  display: grid;
  grid-template-columns: 208px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}
.plaza-side {
  display: flex;
  flex-direction: column;
  gap: 14px;
  position: sticky;
  top: 80px;
}
.side-card {
  border-radius: 14px;
}
.side-title {
  font-size: 13.5px;
  font-weight: 700;
}
.filter-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.filter-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 8px 10px;
  border: none;
  background: transparent;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  color: #475467;
  transition: background-color 0.15s, color 0.15s;
  text-align: left;
}
.filter-item:hover {
  background: rgba(99, 102, 241, 0.06);
  color: #4338ca;
}
.filter-item.active {
  background: rgba(99, 102, 241, 0.1);
  color: #4338ca;
  font-weight: 600;
}
.filter-count {
  font-size: 11.5px;
  color: #98a2b3;
  font-variant-numeric: tabular-nums;
}
.filter-item.active .filter-count {
  color: #6366f1;
}

/* ===== 右侧卡片区 ===== */
.plaza-main {
  min-width: 0;
}
.plaza-stat {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.stat-hint {
  font-size: 12px;
  color: #98a2b3;
}
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(252px, 1fr));
  gap: 14px;
}
.model-card {
  background: v-bind('themeVars.cardColor');
  border: 1px solid rgba(128, 138, 158, 0.18);
  border-radius: 14px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  transition: box-shadow 0.2s, transform 0.2s, border-color 0.2s;
}
.model-card:hover {
  box-shadow: 0 8px 24px rgba(16, 24, 40, 0.1);
  transform: translateY(-2px);
  border-color: rgba(99, 102, 241, 0.35);
}
.card-top {
  display: flex;
  align-items: center;
  gap: 10px;
}
.model-icon {
  width: 40px;
  height: 40px;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.vendor-icon {
  width: 24px;
  height: 24px;
  object-fit: contain;
  display: block;
}
.model-name-wrap {
  min-width: 0;
  flex: 1;
}
.model-name {
  font-size: 14.5px;
  font-weight: 700;
  line-height: 1.3;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.model-id {
  font-size: 11.5px;
  color: #98a2b3;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.status-tag {
  flex-shrink: 0;
}
.model-desc {
  margin: 12px 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: #667085;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 40px;
}
.card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: auto;
}
.model-time {
  font-size: 11.5px;
  color: #98a2b3;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.card-actions {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed rgba(128, 138, 158, 0.2);
  display: flex;
  justify-content: flex-end;
}
.plaza-empty {
  padding: 60px 0;
}
.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 18px;
}

/* ===== 小屏适配 ===== */
@media (max-width: 900px) {
  .plaza-body {
    grid-template-columns: 1fr;
  }
  .plaza-side {
    position: static;
    flex-direction: row;
    flex-wrap: wrap;
  }
  .side-card {
    flex: 1;
    min-width: 180px;
  }
  .filter-list {
    flex-direction: row;
    flex-wrap: wrap;
    gap: 4px;
  }
  .filter-item {
    width: auto;
  }
  .plaza-search {
    width: 100%;
  }
  .plaza-head {
    flex-direction: column;
    align-items: stretch;
  }
  .plaza-tools {
    justify-content: space-between;
  }
}
</style>
