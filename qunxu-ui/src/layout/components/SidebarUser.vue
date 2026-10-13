<script setup lang="ts">
import { computed, ref } from 'vue'
import { NPopover, NAvatar, NIcon, useThemeVars } from 'naive-ui'
import { ChevronDownOutline, LogOutOutline, LockClosedOutline, PersonOutline } from '@vicons/ionicons5'
import { useUserStore } from '@/stores/user'
import { usePermissionStore } from '@/stores/permission'
import { useRouter } from 'vue-router'
import ChangePasswordModal from './ChangePasswordModal.vue'

/**
 * 侧边栏底部用户卡片：展示当前登录用户，点击弹出统一风格的用户菜单。
 * dark=true 适配深色侧边栏（浅色文字），false 适配浅色侧边栏（深色文字）。
 */
defineProps<{ collapsed: boolean; dark: boolean }>()

const themeVars = useThemeVars()
const userStore = useUserStore()
const permissionStore = usePermissionStore()
const router = useRouter()

const avatarChar = computed(() => userStore.displayName.charAt(0))

const menuItems = [
  { key: 'profile', label: '个人中心', icon: PersonOutline },
  { key: 'password', label: '修改密码', icon: LockClosedOutline },
]

// 修改密码弹窗开关
const showPassword = ref(false)

async function handleLogout() {
  // 退出登录：重置权限路由并跳转登录页
  await userStore.logoutAction()
  permissionStore.reset()
  router.push('/login')
}

function onAction(key: string) {
  if (key === 'profile') {
    // 个人中心：跳转静态路由页
    router.push('/profile')
  } else if (key === 'password') {
    // 修改密码：弹出统一风格弹窗
    showPassword.value = true
  } else if (key === 'logout') {
    handleLogout()
  }
}

// 修改密码成功后会话已被后端吊销，需重新登录
function onPasswordSuccess() {
  handleLogout()
}
</script>

<template>
  <n-popover
    trigger="click"
    placement="top-start"
    :show-arrow="false"
    :offset="6"
    :theme-overrides="{ borderRadius: '12px' }"
    style="padding: 0"
  >
    <template #trigger>
      <div class="sider-user" :class="{ collapsed, 'is-dark': dark }">
        <n-avatar
          round
          :size="34"
          style="background: linear-gradient(135deg, #6366f1, #ec4899); color: #fff; flex-shrink: 0"
        >
          {{ avatarChar }}
        </n-avatar>
        <div v-show="!collapsed" class="sider-user-info">
          <div class="sider-user-name">{{ userStore.displayName }}</div>
          <div class="sider-user-role">{{ userStore.displayRole }}</div>
        </div>
        <n-icon v-show="!collapsed" :size="14" class="sider-user-arrow">
          <ChevronDownOutline />
        </n-icon>
      </div>
    </template>

    <!-- 用户菜单：统一圆角卡片风格 -->
    <div class="user-menu">
      <div class="user-menu-header">
        <n-avatar
          round
          :size="40"
          style="background: linear-gradient(135deg, #6366f1, #ec4899); color: #fff; flex-shrink: 0"
        >
          {{ avatarChar }}
        </n-avatar>
        <div class="um-info">
          <div class="um-name">{{ userStore.displayName }}</div>
          <div class="um-role">{{ userStore.displayRole }}</div>
        </div>
      </div>
      <div
        v-for="item in menuItems"
        :key="item.key"
        class="user-menu-item"
        @click="onAction(item.key)"
      >
        <n-icon :size="16"><component :is="item.icon" /></n-icon>
        <span>{{ item.label }}</span>
      </div>
      <div class="user-menu-divider"></div>
      <div class="user-menu-item danger" @click="onAction('logout')">
        <n-icon :size="16"><LogOutOutline /></n-icon>
        <span>退出登录</span>
      </div>
    </div>
  </n-popover>

  <!-- 修改密码弹窗 -->
  <ChangePasswordModal v-model:show="showPassword" @success="onPasswordSuccess" />
</template>

<style scoped>
/* ===== 触发卡片 ===== */
.sider-user {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-top: 1px solid rgba(148, 163, 184, 0.18);
  cursor: pointer;
  transition: background 0.15s;
  user-select: none;
}
.sider-user:hover {
  background: rgba(0, 0, 0, 0.05);
}
.sider-user.is-dark:hover {
  background: rgba(148, 163, 184, 0.08);
}
.sider-user.collapsed {
  justify-content: center;
  padding: 12px 0;
}
.sider-user-info {
  flex: 1;
  min-width: 0;
  line-height: 1.25;
}
.sider-user-name {
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sider-user-role {
  font-size: 11.5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sider-user-arrow {
  color: v-bind('themeVars.textColor3');
}

/* 浅色侧边栏文字 */
.sider-user .sider-user-name {
  color: #1f2937;
}
.sider-user .sider-user-role {
  color: #98a2b3;
}
/* 深色侧边栏文字 */
.sider-user.is-dark .sider-user-name {
  color: #f1f5f9;
}
.sider-user.is-dark .sider-user-role {
  color: #94a3b8;
}

/* ===== 用户菜单弹窗（统一圆角风格） ===== */
.user-menu {
  width: 208px;
  padding: 6px;
}
.user-menu-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 10px 12px;
  border-bottom: 1px solid v-bind('themeVars.dividerColor');
  margin-bottom: 6px;
}
.um-info {
  min-width: 0;
  line-height: 1.3;
}
.um-name {
  font-size: 14px;
  font-weight: 700;
  color: v-bind('themeVars.textColor1');
}
.um-role {
  font-size: 12px;
  color: v-bind('themeVars.textColor3');
}
.user-menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 8px;
  font-size: 13.5px;
  color: v-bind('themeVars.textColor1');
  cursor: pointer;
  transition: background 0.15s;
}
.user-menu-item:hover {
  background: v-bind('themeVars.hoverColor');
}
.user-menu-item.danger {
  color: v-bind('themeVars.errorColor');
}
.user-menu-item.danger:hover {
  background: color-mix(in srgb, v-bind('themeVars.errorColor') 10%, transparent);
}
.user-menu-divider {
  height: 1px;
  background: v-bind('themeVars.dividerColor');
  margin: 6px 0;
}
</style>
