import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  login as loginApi, logout as logoutApi, getPermissionInfo, getProfile,
  updateProfile as updateProfileApi, type ProfileVO, type ProfileUpdateReqVO,
} from '@/api/system/auth'

/** 用户与鉴权状态：token、资料、角色、权限串 */
export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('qunxu.token') || '')
  const profile = ref<ProfileVO | null>(null)
  const roles = ref<string[]>([])
  const permissions = ref<string[]>([])
  const loaded = ref(false)

  const isLogin = computed(() => !!token.value)
  const displayName = computed(() => profile.value?.nickname || '管理员')
  const displayRole = computed(() => profile.value?.roleNames?.[0] || roles.value[0] || '超级管理员')

  function setToken(value: string) {
    token.value = value
    localStorage.setItem('qunxu.token', value)
  }

  /** 登录：获取 token 后暂不拉取用户信息（由路由守卫统一加载） */
  async function loginAction(form: { username: string; password: string; captchaUuid?: string; captchaCode?: string }) {
    const res = await loginApi(form)
    setToken(res.accessToken)
  }

  /** 拉取资料 / 角色 / 权限（幂等） */
  async function fetchUser() {
    if (loaded.value) return
    const [info, p] = await Promise.all([getPermissionInfo(), getProfile()])
    roles.value = info.roles || []
    permissions.value = info.permissions || []
    profile.value = p
    loaded.value = true
  }

  /** 是否拥有指定权限（支持单个字符串或数组，数组为“任一满足”） */
  function hasPerm(value?: string | string[]): boolean {
    if (!value) return true
    const list = Array.isArray(value) ? value : [value]
    return list.some((p) => permissions.value.includes(p))
  }

  /** 更新个人资料：成功后同步本地缓存，顶栏 / 侧边栏昵称即时刷新 */
  async function updateProfileAction(data: ProfileUpdateReqVO) {
    await updateProfileApi(data)
    if (profile.value) {
      profile.value.nickname = data.nickname
      profile.value.email = data.email
      profile.value.mobile = data.mobile || ''
      profile.value.avatar = data.avatar
    }
  }

  async function logoutAction() {
    try {
      await logoutApi()
    } catch {
      /* 忽略登出接口异常 */
    }
    token.value = ''
    profile.value = null
    roles.value = []
    permissions.value = []
    loaded.value = false
    localStorage.removeItem('qunxu.token')
  }

  return { token, profile, roles, permissions, loaded, isLogin, displayName, displayRole, setToken, loginAction, fetchUser, hasPerm, updateProfileAction, logoutAction }
})
