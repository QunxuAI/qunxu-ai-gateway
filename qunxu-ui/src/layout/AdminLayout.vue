<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NLayoutSider, NDrawer, NDrawerContent, NMenu, useThemeVars } from 'naive-ui'
import HeaderBar from './components/HeaderBar.vue'
import SidebarUser from './components/SidebarUser.vue'
import { useAppStore } from '@/stores/app'
import { usePermissionStore } from '@/stores/permission'
import { useResponsive } from '@/utils/useResponsive'
import logoUrl from '@/assets/logo.svg'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const permissionStore = usePermissionStore()
const themeVars = useThemeVars()
const { isMobile, isTablet } = useResponsive()

// 是否深色模式：侧边栏随之切换深浅背景
const isDark = computed(() => appStore.themeMode === 'dark')

// 侧边栏菜单：登录后由权限信息生成的动态菜单（首页为「控制台 / 数据看板」）
const menuOptions = computed(() => permissionStore.menuOptions)

// 移动端抽屉开关
const mobileOpen = ref(false)

// 桌面端折叠状态：小屏（<1024px）自动折叠为图标模式，大屏跟随手动折叠开关
const collapsed = computed(() => (isTablet.value ? true : appStore.collapsed))

// 当前激活菜单 = 路由路径
const activeKey = computed(() => route.path)

// 点击菜单跳转；移动端跳转后关闭抽屉
function onMenuSelect(key: string) {
  router.push(key)
  mobileOpen.value = false
}

// 菜单展开分组（半受控：初始/路由变化自动展开当前分组，也允许手动开合）
const expandedKeys = ref<string[]>([])

function syncExpandedKeys(path: string) {
  const parts = path.split('/').filter(Boolean)
  if (parts.length <= 1) return
  const parent = '/' + parts[0]
  if (!expandedKeys.value.includes(parent)) {
    expandedKeys.value = [...expandedKeys.value, parent]
  }
}
watch(() => route.path, syncExpandedKeys, { immediate: true })

function onExpandChange(keys: string[]) {
  expandedKeys.value = keys
}

// 切换到移动端时关闭抽屉
watch(isMobile, (mobile) => {
  if (mobile) mobileOpen.value = false
})
</script>

<template>
  <div class="admin-layout">
    <!-- 桌面端：侧边栏（顶部 logo、底部用户信息），背景跟随主题深浅 -->
    <n-layout-sider
      v-if="!isMobile"
      bordered
      :inverted="isDark"
      collapse-mode="width"
      :collapsed-width="64"
      :width="240"
      :collapsed="collapsed"
      :native-scrollbar="false"
      class="sider"
    >
      <div class="sider-body" style="height: 100vh">
        <!-- 左侧顶部 Logo 区域（高度与顶栏对齐） -->
        <div class="logo" :class="{ 'is-dark': isDark }">
          <div class="logo-mark">
            <img :src="logoUrl" alt="Qunxu AI" class="logo-img" />
          </div>
          <div v-show="!collapsed" class="logo-text">
            <strong>Qunxu AI</strong>
            <small>群绪AI</small>
          </div>
        </div>
        <div class="sider-menu-wrap">
          <n-menu
            :inverted="isDark"
            :collapsed="collapsed"
            :collapsed-width="64"
            :collapsed-icon-size="20"
            :options="menuOptions"
            :value="activeKey"
            :expanded-keys="expandedKeys"
            @update:value="onMenuSelect"
            @update:expanded-keys="onExpandChange"
          />
        </div>
        <!-- 左侧底部：用户信息 -->
        <SidebarUser :collapsed="collapsed" :dark="isDark" />
      </div>
    </n-layout-sider>

    <!-- 移动端：抽屉式侧边栏（背景跟随主题深浅） -->
    <n-drawer v-else :show="mobileOpen" :width="248" placement="left" @update:show="(v) => (mobileOpen = v)">
      <n-drawer-content body-content-style="padding:0" :native-scrollbar="false">
        <template #header>
          <div class="logo" :class="{ 'is-dark': isDark }">
            <div class="logo-mark">
              <img :src="logoUrl" alt="Qunxu AI" class="logo-img" />
            </div>
            <div class="logo-text">
              <strong>Qunxu AI</strong>
              <small>群绪AI</small>
            </div>
          </div>
        </template>
        <div class="sider-body">
          <div class="sider-menu-wrap">
            <n-menu
              :inverted="isDark"
              :options="menuOptions"
              :value="activeKey"
              :expanded-keys="expandedKeys"
              @update:value="onMenuSelect"
              @update:expanded-keys="onExpandChange"
            />
          </div>
          <SidebarUser :collapsed="false" :dark="isDark" />
        </div>
      </n-drawer-content>
    </n-drawer>

    <!-- 右侧主区域：顶栏固定，内容区 flex 占满剩余高度并独立滚动 -->
    <div class="main">
      <HeaderBar
        @toggle-drawer="mobileOpen = !mobileOpen"
        @toggle-sider="appStore.toggleCollapsed()"
      />
      <div class="content">
        <router-view v-slot="{ Component }">
          <transition name="fade-slide" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 整体锁定视口高度，由内容区自行滚动；背景跟随主题 */
.admin-layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: v-bind('themeVars.bodyColor');
}
.sider {
  height: 100vh;
  flex-shrink: 0;
}
.sider-body {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.sider-menu-wrap {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
/* 右侧主区域：顶栏固定，内容区 flex 占满剩余高度并独立滚动 */
.main {
  min-width: 0;
  flex: 1;
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 20px;
}
/* Logo 区域：box-sizing border-box，总高严格 60px 与顶栏对齐，避免视觉错位 */
.logo {
  display: flex;
  align-items: center;
  gap: 11px;
  height: 60px;
  padding: 0 14px;
  overflow: hidden;
  flex-shrink: 0;
  box-sizing: border-box;
  border-bottom: 1px solid rgba(148, 163, 184, 0.18);
}
.logo-mark {
  width: 34px;
  height: 34px;
  flex-shrink: 0;
  border-radius: 9px;
  overflow: hidden;
  box-shadow: 0 4px 12px rgba(99, 102, 241, 0.45);
}
.logo-img {
  display: block;
  width: 100%;
  height: 100%;
}
.logo-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
  white-space: nowrap;
}
/* 浅色侧边栏：深色文字 */
.logo strong {
  color: #1f2937;
  font-size: 15.5px;
  font-weight: 700;
}
.logo small {
  color: #98a2b3;
  font-size: 9.5px;
  letter-spacing: 1.5px;
  font-weight: 500;
}
/* 深色侧边栏：浅色文字 */
.logo.is-dark strong {
  color: #f8fafc;
}
.logo.is-dark small {
  color: #94a3b8;
}

/* 小屏（<768px）时缩小内容区边距 */
@media (max-width: 768px) {
  .content {
    padding: 12px;
  }
}
</style>
