import request from '../request'
import {
  mockDelay, clone, buildMenuTree, buildSubsetMenuTree,
  ADMIN_PERMISSIONS, TEST_PERMISSIONS, type MenuVO,
} from '../mock'

/** 是否使用本地模拟数据；后端已就绪，对接真实接口 */
const USE_MOCK = false

/** 登录请求，对应后端 AuthLoginReqVO */
export interface LoginReqVO {
  username: string
  password: string
  captchaUuid?: string
  captchaCode?: string
  clientId?: string
  clientSecret?: string
}

/** 登录响应，对应后端 AuthLoginRespVO */
export interface LoginRespVO {
  userId: number
  accessToken: string
  refreshToken: string
  expiresTime: string
}

/** 验证码，对应后端 CaptchaRespDTO */
export interface CaptchaRespVO {
  uuid: string
  expression: string
}

/** 权限信息，对应后端 AuthPermissionInfoRespVO */
export interface PermissionInfoVO {
  userType: number
  roles: string[]
  permissions: string[]
}

/** 用户资料（用于顶栏/侧边栏展示），对应后端 UserRespVO */
export interface ProfileVO {
  id: number
  username: string
  nickname: string
  mobile: string
  email?: string
  avatar?: string
  deptName?: string
  postNames: string[]
  roleNames: string[]
  createTime?: string
}

/** 更新个人资料，对应后端 UserProfileUpdateReqVO */
export interface ProfileUpdateReqVO {
  nickname: string
  email?: string
  mobile?: string
  avatar?: string
}

/** 修改个人密码，对应后端 UserProfileUpdatePasswordReqVO */
export interface ProfileUpdatePasswordReqVO {
  oldPassword: string
  newPassword: string
}

/* ================= 本地模拟 ================= */

// 从 token 推断 mock 用户：admin（全权限）/ test（受限权限）
function resolveMockUser(token: string): { roles: string[]; permissions: string[]; profile: ProfileVO } {
  if (token.includes('test')) {
    return {
      roles: ['common'],
      permissions: TEST_PERMISSIONS,
      profile: { id: 2, username: 'test', nickname: '测试', mobile: '13800138002', deptName: '研发部', postNames: ['测试工程师'], roleNames: ['普通角色'] },
    }
  }
  return {
    roles: ['admin'],
    permissions: ADMIN_PERMISSIONS,
    profile: { id: 1, username: 'admin', nickname: '管理员', mobile: '13800138001', deptName: '总公司', postNames: ['董事长'], roleNames: ['超级管理员'] },
  }
}

export function getMockProfile(token: string): ProfileVO {
  return resolveMockUser(token).profile
}

/* ================= 认证 API ================= */

/** 账号密码登录 */
export function login(data: LoginReqVO): Promise<LoginRespVO> {
  if (USE_MOCK) {
    const ok = (data.username === 'admin' && data.password === 'admin123') ||
      (data.username === 'test' && data.password === '123456')
    if (!ok) {
      return Promise.reject(new Error('账号或密码错误'))
    }
    const isTest = data.username === 'test'
    return mockDelay({
      userId: isTest ? 2 : 1,
      accessToken: isTest ? 'mock-token-test' : 'mock-token-admin',
      refreshToken: isTest ? 'mock-refresh-test' : 'mock-refresh-admin',
      expiresTime: new Date(Date.now() + 2592000000).toISOString(),
    })
  }
  return request.post('/system/auth/login', { clientId: 'default', ...data })
}

/** 登出 */
export function logout(): Promise<boolean> {
  if (USE_MOCK) {
    return mockDelay(true)
  }
  return request.post('/system/auth/logout')
}

/** 获取验证码 */
export function getCaptcha(): Promise<CaptchaRespVO> {
  if (USE_MOCK) {
    return mockDelay({ uuid: 'mock-captcha-uuid', expression: '3 + 5 = ?' })
  }
  return request.get('/system/auth/get-captcha')
}

/** 获取登录用户的角色与权限 */
export function getPermissionInfo(): Promise<PermissionInfoVO> {
  if (USE_MOCK) {
    const token = localStorage.getItem('qunxu.token') || ''
    const user = resolveMockUser(token)
    return mockDelay({ userType: 1, roles: user.roles, permissions: user.permissions })
  }
  return request.get('/system/auth/get-permission-info')
}

/** 获取登录用户的菜单（树形结构） */
export function getMenus(): Promise<MenuVO[]> {
  if (USE_MOCK) {
    const token = localStorage.getItem('qunxu.token') || ''
    return mockDelay(token.includes('test') ? clone(buildSubsetMenuTree()) : clone(buildMenuTree()))
  }
  return request.get('/system/auth/get-menus')
}

/** 获取当前用户资料 */
export function getProfile(): Promise<ProfileVO> {
  if (USE_MOCK) {
    const token = localStorage.getItem('qunxu.token') || ''
    return mockDelay(clone(resolveMockUser(token).profile))
  }
  return request.get('/system/user/profile')
}

/** 更新个人资料 */
export function updateProfile(data: ProfileUpdateReqVO): Promise<boolean> {
  if (USE_MOCK) {
    return mockDelay(true)
  }
  return request.put('/system/user/profile/update', data)
}

/** 修改个人密码（成功后后端会吊销当前会话，需重新登录） */
export function updatePassword(data: ProfileUpdatePasswordReqVO): Promise<boolean> {
  if (USE_MOCK) {
    return mockDelay(true)
  }
  return request.put('/system/user/profile/update-password', data)
}
