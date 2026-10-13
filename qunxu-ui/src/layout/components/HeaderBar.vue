<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NLayoutHeader, NButton, NInput, NIcon, NBadge, NPopover, NTag, NSpin, useThemeVars } from 'naive-ui'
import { MenuOutline, NotificationsOutline, MoonOutline, SunnyOutline } from '@vicons/ionicons5'
import { useAppStore } from '@/stores/app'
import { useUserStore } from '@/stores/user'
import { useResponsive } from '@/utils/useResponsive'
import { getNoticePage, type Notice } from '@/api/system/notice'

const emit = defineEmits<{ 'toggle-drawer': []; 'toggle-sider': [] }>()

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const userStore = useUserStore()
const themeVars = useThemeVars()
const { isMobile } = useResponsive()

// 面包屑：首页 / 分组 / 当前页
const breadcrumb = computed(() => {
  // 数据看板为一级菜单（独立于控制台分组）
  if (route.path === '/gateway/dashboard') {
    return { group: '', page: '数据看板' }
  }
  const parts = route.path.split('/').filter(Boolean)
  const last = parts[parts.length - 1] || ''
  const map: Record<string, string> = {
    gateway: '控制台',
    dashboard: '数据看板',
    plaza: '模型广场',
    provider: '供应商管理',
    model: '逻辑模型',
    channel: '模型通道',
    health: '健康监控',
    audit: '调用审计',
    security: '安全策略',
    ratelimit: '限流配额',
    apikey: '令牌管理',
    user: '用户管理',
    role: '角色管理',
    menu: '菜单管理',
    dept: '部门管理',
    post: '岗位管理',
    dict: '字典管理',
    tenant: '租户管理',
    'tenant-package': '租户套餐',
    notice: '通知公告',
    operate: '操作日志',
    login: '登录日志',
    profile: '个人中心',
  }
  return { group: map[parts[0]] || '', page: map[last] || last }
})

/* ================= 通知 ================= */

// 只有具备 system:notice:query 权限的用户才展示通知铃铛并拉取数据；
// 普通角色（已收敛菜单）无此权限，避免每次登录都触发后端的 Access Denied。
const canViewNotice = computed(() => userStore.hasPerm('system:notice:query'))

// 已读记录持久化在 localStorage，key 为通知 id 数组
const READ_KEY = 'qunxu.notice.read'

const notices = ref<Notice[]>([])
const noticeTotal = ref(0)
const noticeLoading = ref(false)
const readIds = ref<number[]>(loadReadIds())

const unreadCount = computed(() => notices.value.filter((n) => !readIds.value.includes(n.id)).length)

function loadReadIds(): number[] {
  try {
    return JSON.parse(localStorage.getItem(READ_KEY) || '[]') as number[]
  } catch {
    return []
  }
}

function saveReadIds() {
  localStorage.setItem(READ_KEY, JSON.stringify(readIds.value))
}

/** 拉取已发布（status=1）的最新通知 */
async function loadNotices() {
  noticeLoading.value = true
  try {
    const res = await getNoticePage({ pageNo: 1, pageSize: 5, status: 1 })
    notices.value = res.list
    noticeTotal.value = res.total
  } catch {
    notices.value = []
  } finally {
    noticeLoading.value = false
  }
}

/** 展开面板时刷新，并把当前可见的通知标记为已读 */
async function onNoticeOpen(show: boolean) {
  if (!show || !canViewNotice.value) return
  await loadNotices()
  const visibleIds = notices.value.map((n) => n.id)
  readIds.value = [...new Set([...readIds.value, ...visibleIds])]
  saveReadIds()
}

function goNoticePage(noticeId?: number) {
  router.push(noticeId ? { path: '/system/notice', query: { noticeId: String(noticeId) } } : '/system/notice')
}

function formatTime(t: string): string {
  return (t || '').slice(0, 16).replace('T', ' ')
}

onMounted(() => {
  // 进页面先拉一次，让徽标带上未读数（无权限用户跳过，避免 403）
  if (canViewNotice.value) loadNotices()
})

// 折叠 / 抽屉开关：移动端开抽屉，桌面端折叠侧边栏
function onToggleMenu() {
  if (isMobile.value) {
    emit('toggle-drawer')
  } else {
    emit('toggle-sider')
  }
}
</script>

<template>
  <n-layout-header bordered class="topbar">
    <!-- 左侧：菜单折叠（桌面）/ 抽屉开关（移动） + 面包屑 -->
    <n-button quaternary circle class="toggle-btn" @click="onToggleMenu">
      <template #icon>
        <n-icon :size="20"><MenuOutline /></n-icon>
      </template>
    </n-button>
    <div class="breadcrumb">
      <span class="crumb-home">首页</span>
      <span v-if="breadcrumb.group" class="crumb-sep">/</span>
      <span v-if="breadcrumb.group" class="crumb-group">{{ breadcrumb.group }}</span>
      <span class="crumb-sep">/</span>
      <span class="crumb-page">{{ breadcrumb.page }}</span>
    </div>

    <!-- 右侧操作区 -->
    <div class="topbar-right">
      <n-popover v-if="canViewNotice" trigger="click" placement="bottom-end" :show-arrow="false" @update:show="onNoticeOpen">
        <template #trigger>
          <n-badge :value="unreadCount" :max="99" :offset="[-2, 4]">
            <n-button quaternary circle>
              <template #icon>
                <n-icon :size="19"><NotificationsOutline /></n-icon>
              </template>
            </n-button>
          </n-badge>
        </template>
        <div class="notice-panel">
          <div class="notice-panel-head">
            <span class="notice-panel-title">通知公告</span>
            <n-button text size="tiny" @click="goNoticePage">查看全部</n-button>
          </div>
          <n-spin :show="noticeLoading">
            <div class="notice-panel-body">
              <template v-if="notices.length">
                <div v-for="n in notices" :key="n.id" class="notice-item" @click="goNoticePage(n.id)">
                  <div class="notice-item-line">
                    <span class="notice-item-title">{{ n.title }}</span>
                    <n-tag size="tiny" :type="n.type === 2 ? 'info' : 'default'" round>{{ n.type === 2 ? '公告' : '通知' }}</n-tag>
                  </div>
                  <div class="notice-item-content">{{ n.content }}</div>
                  <div class="notice-item-time">{{ formatTime(n.createTime) }}</div>
                </div>
              </template>
              <div v-else class="notice-empty">暂无通知</div>
            </div>
          </n-spin>
          <div class="notice-panel-foot">共 {{ noticeTotal }} 条已发布通知</div>
        </div>
      </n-popover>

      <n-button quaternary circle @click="appStore.toggleTheme()">
        <template #icon>
          <n-icon :size="19">
            <SunnyOutline v-if="appStore.themeMode === 'light'" />
            <MoonOutline v-else />
          </n-icon>
        </template>
      </n-button>
    </div>
  </n-layout-header>
</template>

<style scoped>
.topbar {
  height: 60px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 16px;
  position: sticky;
  top: 0;
  z-index: 30;
}
.toggle-btn {
  flex-shrink: 0;
}
.breadcrumb {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13.5px;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
}
.crumb-home,
.crumb-group {
  color: v-bind('themeVars.textColor3');
}
.crumb-page {
  color: v-bind('themeVars.textColor1');
  font-weight: 600;
}
.crumb-sep {
  color: v-bind('themeVars.textColor3');
  opacity: 0.7;
}
.topbar-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 通知面板 */
.notice-panel {
  width: 300px;
  max-height: 380px;
  display: flex;
  flex-direction: column;
}
.notice-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px 6px;
  border-bottom: 1px solid v-bind('themeVars.dividerColor');
}
.notice-panel-title {
  font-weight: 600;
  font-size: 13.5px;
  color: v-bind('themeVars.textColor1');
}
.notice-panel-body {
  overflow-y: auto;
  min-height: 60px;
  padding: 4px 0;
}
.notice-item {
  padding: 8px 12px;
  cursor: pointer;
  transition: background-color 0.2s;
}
.notice-item:hover {
  background-color: v-bind('themeVars.hoverColor');
}
.notice-item-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.notice-item-title {
  font-size: 13px;
  font-weight: 500;
  color: v-bind('themeVars.textColor1');
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.notice-item-content {
  margin-top: 3px;
  font-size: 12px;
  color: v-bind('themeVars.textColor3');
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.notice-item-time {
  margin-top: 4px;
  font-size: 11.5px;
  color: v-bind('themeVars.textColor3');
  opacity: 0.8;
}
.notice-empty {
  padding: 24px 0;
  text-align: center;
  font-size: 12.5px;
  color: v-bind('themeVars.textColor3');
}
.notice-panel-foot {
  padding: 6px 12px;
  border-top: 1px solid v-bind('themeVars.dividerColor');
  font-size: 12px;
  color: v-bind('themeVars.textColor3');
  text-align: center;
}

/* 小屏适配 */
@media (max-width: 768px) {
  .topbar {
    padding: 0 10px;
  }
}
</style>
