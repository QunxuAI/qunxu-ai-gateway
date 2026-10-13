import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import AdminLayout from '@/layout/AdminLayout.vue'
import { useUserStore } from '@/stores/user'
import { usePermissionStore } from '@/stores/permission'

/** 静态路由：登录页 + 主布局（含数据看板首页） */
export const constantRoutes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录' },
  },
  {
    path: '/',
    name: 'Root',
    component: AdminLayout,
    redirect: '/gateway/dashboard',
    children: [
      {
        path: 'gateway/dashboard',
        name: 'GatewayDashboard',
        component: () => import('@/views/gateway/dashboard/index.vue'),
        meta: { title: '数据看板' },
      },
      {
        path: 'profile',
        name: 'Profile',
        component: () => import('@/views/system/profile/index.vue'),
        meta: { title: '个人中心' },
      },
    ],
  },
  // 兜底：未匹配路由回到数据看板
  { path: '/:pathMatch(.*)*', redirect: '/gateway/dashboard' },
]

const router = createRouter({
  history: createWebHistory(),
  routes: constantRoutes,
})

/** 全局路由守卫：鉴权 + 首次进入加载动态路由 */
router.beforeEach(async (to) => {
  document.title = (to.meta.title as string)
    ? `${to.meta.title} · Qunxu AI`
    : 'Qunxu AI 管理后台'

  const user = useUserStore()

  // 未登录：放行登录页，其余跳转登录页并记录来源
  if (!user.isLogin) {
    return to.path === '/login' ? true : { path: '/login', query: { redirect: to.fullPath } }
  }

  // 已登录访问登录页：回到首页
  if (to.path === '/login') {
    return { path: '/' }
  }

  // 已登录：首次进入时加载权限信息与动态路由
  const permission = usePermissionStore()
  if (!permission.routesReady) {
    try {
      await permission.init(router)
    } catch {
      user.logoutAction()
      return { path: '/login' }
    }
    // 动态路由注册后重新导航到原始目标。
    // 注意：目标若被兜底路由重定向过，to 已变为重定向后的地址，
    // 需从 redirectedFrom 取回用户最初访问的路径。
    const original = to.redirectedFrom
    if (original) {
      return { path: original.path, query: original.query, hash: original.hash, replace: true }
    }
    return { ...to, replace: true }
  }
  return true
})

export default router
