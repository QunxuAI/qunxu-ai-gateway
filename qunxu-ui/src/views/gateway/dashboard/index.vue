<template>
  <div class="gateway-dashboard">
    <!-- KPI 概览 -->
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

    <!-- 运行状态（管理员可见的系统级信息；普通用户仅看本人用量，不展示） -->
    <n-card v-if="isAdmin" :bordered="false" class="card-block">
      <template #header>
        <div class="table-header">
          <span class="table-title">运行状态</span>
        </div>
      </template>
      <template #header-extra>
        <n-button size="small" @click="loadAll">
          <template #icon><n-icon :component="RefreshOutline" /></template>刷新
        </n-button>
      </template>
      <n-descriptions bordered :column="2" size="small" label-placement="left" class="status-descriptions">
        <n-descriptions-item label="缓存实例 (Pub/Sub)">
          <n-space :size="6" align="center">
            <n-tag size="small" round :type="cache?.pubsubActive ? 'success' : 'error'" :bordered="false">
              {{ cache?.pubsubActive ? '已启用' : '未启用' }}
            </n-tag>
            <span v-if="cache?.pubsubChannel" class="desc-sub">{{ cache.pubsubChannel }}</span>
            <span v-else>-</span>
          </n-space>
        </n-descriptions-item>
        <n-descriptions-item label="敏感词缓存条数">
          <span class="tabular-nums">{{ cache?.sensitiveWords ?? 0 }}</span>
        </n-descriptions-item>
        <n-descriptions-item label="限流开关">
          <n-tag size="small" round :type="cache?.rateLimitEnabled ? 'success' : 'default'" :bordered="false">
            {{ cache?.rateLimitEnabled ? '已开启' : '已关闭' }}
          </n-tag>
        </n-descriptions-item>
        <n-descriptions-item label="限流存储">{{ cache?.rateLimitStore || '-' }}</n-descriptions-item>
        <n-descriptions-item label="配额开关">
          <n-tag size="small" round :type="cache?.quotaEnabled ? 'success' : 'default'" :bordered="false">
            {{ cache?.quotaEnabled ? '已开启' : '已关闭' }}
          </n-tag>
        </n-descriptions-item>
        <n-descriptions-item label="配额范围">{{ rateLimit?.quotaScope || '-' }}</n-descriptions-item>
        <n-descriptions-item label="健康探活">
          <n-tag size="small" round :type="cache?.healthEnabled ? 'success' : 'default'" :bordered="false">
            {{ cache?.healthEnabled ? '已开启' : '已关闭' }}
          </n-tag>
        </n-descriptions-item>
        <n-descriptions-item label="健康存储">{{ cache?.healthStore || '-' }}</n-descriptions-item>
        <n-descriptions-item label="限流失效开放">{{ rateLimit?.failOpen ? '是' : '否' }}</n-descriptions-item>
      </n-descriptions>
    </n-card>

    <!-- 近 7 日用量明细 -->
    <n-card :bordered="false" class="card-block">
      <template #header>
        <div class="table-header">
          <span class="table-title">近 7 日用量明细</span>
          <n-tag size="small" round>{{ usageRangeLabel }}</n-tag>
        </div>
      </template>
      <n-data-table
        :columns="usageColumns"
        :data="usageStats"
        :loading="loading"
        :scroll-x="720"
        :bordered="false"
      />
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NCard, NButton, NIcon, NDataTable, NTag, NSpace, NDescriptions, NDescriptionsItem,
  useMessage, useThemeVars, type DataTableColumns,
} from 'naive-ui'
import {
  RefreshOutline, GitNetworkOutline, CloseCircleOutline, BanOutline,
  AnalyticsOutline, DocumentTextOutline,
} from '@vicons/ionicons5'
import { healthApi, cacheApi, rateLimitApi, auditApi, gwErrorMessage } from '@/api/gateway'
import { formatTokens, toLocalIso } from '@/api/gateway/labels'
import type { CacheStatus, HealthSummary, RateLimitConfigView, TokenUsageStat } from '@/api/gateway/types'
import { useUserStore } from '@/stores/user'

const message = useMessage()
const userStore = useUserStore()
const themeVars = useThemeVars()

/** 管理员（super_admin 角色）可查看全网关运行状态与全部用量；普通用户仅可查看本人用量（后端强制）。 */
const isAdmin = computed(() => userStore.roles.includes('super_admin'))

const loading = ref(false)

/* ================= 数据（只读，任一接口失败不致命） ================= */
const summary = ref<HealthSummary | null>(null)
const cache = ref<CacheStatus | null>(null)
const rateLimit = ref<RateLimitConfigView | null>(null)
const usageStats = ref<TokenUsageStat[]>([])

const totalCalls = computed(() => usageStats.value.reduce((s, r) => s + r.calls, 0))
const totalTokens = computed(() => usageStats.value.reduce((s, r) => s + r.totalTokens, 0))

/** KPI：管理员展示全网关维度（可用通道/故障/已剔除 + 近7日用量）；普通用户仅展示本人近7日用量。 */
const kpis = computed(() => {
  const list = [
    { label: '近7日调用', value: totalCalls.value.toLocaleString(), unit: '次', icon: AnalyticsOutline, bg: '#eff6ff', color: '#3b82f6' },
    { label: '近7日Token', value: totalTokens.value.toLocaleString(), unit: '', icon: DocumentTextOutline, bg: '#f5f3ff', color: '#8b5cf6' },
  ]
  if (isAdmin.value) {
    list.unshift(
      { label: '可用通道', value: `${summary.value?.routable ?? 0} / ${summary.value?.total ?? 0}`, unit: '条', icon: GitNetworkOutline, bg: '#eef2ff', color: '#6366f1' },
      { label: '故障', value: `${summary.value?.down ?? 0}`, unit: '条', icon: CloseCircleOutline, bg: '#fef2f2', color: '#ef4444' },
      { label: '已剔除', value: `${summary.value?.ejected ?? 0}`, unit: '条', icon: BanOutline, bg: '#fffbeb', color: '#f59e0b' },
    )
  }
  return list
})

const usageRangeLabel = computed(() => {
  if (usageStats.value.length === 0) return '暂无数据'
  const keys = usageStats.value.map((s) => s.key).sort()
  return `${keys[0]} ~ ${keys[keys.length - 1]}`
})

const usageColumns: DataTableColumns<TokenUsageStat> = [
  { title: '日期', key: 'key', minWidth: 140 },
  { title: '调用次数', key: 'calls', width: 120, className: 'tabular-nums', render: (row) => row.calls.toLocaleString() },
  { title: '输入 Tokens', key: 'promptTokens', width: 140, className: 'tabular-nums', render: (row) => formatTokens(row.promptTokens) },
  { title: '输出 Tokens', key: 'completionTokens', width: 140, className: 'tabular-nums', render: (row) => formatTokens(row.completionTokens) },
  { title: '合计 Tokens', key: 'totalTokens', width: 140, className: 'tabular-nums', render: (row) => h('b', null, formatTokens(row.totalTokens)) },
]

async function loadAll() {
  loading.value = true
  const now = new Date()
  // 注意：后端把 from/to 按本地 LocalDateTime 比较，必须传本地时间（toLocalIso），
  // 传 toISOString()（UTC+Z）会导致「今天」被截断在本地 00:00、看不到当天数据。
  const from = toLocalIso(new Date(now.getTime() - 7 * 24 * 3600 * 1000))
  const to = toLocalIso(now)

  // 普通用户：仅加载本人用量（callerId = 本人 id，后端亦强制隔离）；系统级状态仅管理员可见
  const usageParams: Record<string, unknown> = { from, to }
  if (!isAdmin.value && userStore.profile?.id != null) {
    usageParams.callerId = String(userStore.profile.id)
  }

  if (isAdmin.value) {
    try {
      summary.value = await healthApi.summary()
    } catch (err) {
      message.warning(`健康概览加载失败：${gwErrorMessage(err)}`)
    }
    try {
      cache.value = await cacheApi.status()
    } catch (err) {
      message.warning(`缓存状态加载失败：${gwErrorMessage(err)}`)
    }
    try {
      rateLimit.value = await rateLimitApi.config()
    } catch (err) {
      message.warning(`限流配置加载失败：${gwErrorMessage(err)}`)
    }
  }
  try {
    usageStats.value = await auditApi.usage('day', usageParams)
  } catch (err) {
    message.warning(`用量统计加载失败：${gwErrorMessage(err)}`)
  }
  loading.value = false
}

onMounted(() => {
  loadAll()
})
</script>

<style scoped>
.gateway-dashboard {
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
.table-header {
  display: flex;
  align-items: center;
  gap: 10px;
}
.table-title {
  font-size: 15px;
  font-weight: 700;
}
.desc-sub {
  font-size: 12px;
  color: #98a2b3;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.status-descriptions {
  margin-top: 4px;
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
}
</style>
