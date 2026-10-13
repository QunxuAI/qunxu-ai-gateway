import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Router } from 'vue-router'
import type { MenuOption } from 'naive-ui'
import { getMenus } from '@/api/system/auth'
import type { MenuVO } from '@/api/mock'
import { resolveView } from '@/router/views'
import { mapMenuIcon } from '@/layout/menuIcons'
import { renderIcon } from '@/utils/renderIcon'
import { useUserStore } from './user'

/**
 * 权限与动态路由：登录后拉取菜单树，生成侧边栏菜单并注册动态路由。
 */
export const usePermissionStore = defineStore('permission', () => {
  const menuTree = ref<MenuVO[]>([])
  const menuOptions = ref<MenuOption[]>([])
  const routesReady = ref(false)

  /** 初始化：确保用户信息已加载，再拉取菜单、注册路由（幂等） */
  async function init(router: Router) {
    const user = useUserStore()
    await user.fetchUser()
    if (routesReady.value) return
    const menus = await getMenus()
    menuTree.value = menus
    menuOptions.value = buildMenuOptions(menus)
    registerDynamicRoutes(router, menus)
    routesReady.value = true
  }

  /** 退出登录后重置 */
  function reset() {
    menuTree.value = []
    menuOptions.value = []
    routesReady.value = false
  }

  return { menuTree, menuOptions, routesReady, init, reset }
})

/** 计算菜单完整路径：目录自带 /，菜单拼接父目录路径 */
function computePath(parentPath: string, node: MenuVO): string {
  if (node.type === 1) return node.path || ''
  const p = node.path || ''
  if (!parentPath || parentPath === '/') return `/${p}`.replace(/\/+/g, '/')
  return `${parentPath}/${p}`.replace(/\/+/g, '/')
}

/** 菜单树 → Naive 侧边栏菜单（过滤按钮与停用项） */
function buildMenuOptions(menus: MenuVO[]): MenuOption[] {
  const walk = (list: MenuVO[], parentPath: string): MenuOption[] =>
    list
      .filter((m) => m.type !== 3 && m.status === 1)
      .map((m) => {
        const path = computePath(parentPath, m)
        const children = m.children?.length ? walk(m.children, path) : undefined
        return {
          label: m.name,
          key: path,
          icon: renderIcon(mapMenuIcon(m.name)),
          children: children?.length ? children : undefined,
        }
      })
  return walk(menus, '')
}

/** 注册动态路由：type=2 且带 component 的菜单挂到 Root（AdminLayout）下 */
function registerDynamicRoutes(router: Router, menus: MenuVO[]) {
  const walk = (list: MenuVO[], parentPath: string) => {
    list
      .filter((m) => m.type !== 3 && m.status === 1)
      .forEach((m) => {
        const path = computePath(parentPath, m)
        if (m.type === 2 && m.component) {
          const loader = resolveView(m.component)
          if (loader) {
            router.addRoute('Root', {
              path,
              name: m.componentName || path.replace(/\//g, '-'),
              component: loader as never,
              meta: { title: m.name, keepAlive: m.keepAlive },
            })
          }
        }
        if (m.children?.length) walk(m.children, path)
      })
  }
  walk(menus, '')
}
