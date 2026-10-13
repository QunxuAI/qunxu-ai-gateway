import request from '../request'

/** 用户状态（1 开启 / 0 关闭） */
export type UserStatus = 0 | 1

/** 用户分页查询入参，对应后端 UserPageReqVO */
export interface UserPageQuery {
  pageNo: number
  pageSize: number
  username?: string
  mobile?: string
  status?: UserStatus | null
  /** 创建时间范围（时间戳，[起, 止]） */
  createTime?: [number, number] | null
}

/** 用户信息，对应后端 UserRespVO */
export interface User {
  id: number
  username: string
  nickname: string
  mobile: string
  deptName: string
  postNames: string[]
  roleNames: string[]
  status: UserStatus
  createTime: string
}

/** 分页结果，对应后端 PageResult */
export interface PageResult<T> {
  list: T[]
  total: number
}

/** 新增 / 编辑用户入参，对应后端 UserSaveReqVO */
export interface UserSaveDTO {
  username: string
  nickname: string
  mobile?: string
  password?: string
  deptId?: number
  postIds?: number[]
  roleIds?: number[]
  status: UserStatus
}

/**
 * 是否使用本地模拟数据。
 * 后端就绪后，将 USE_MOCK 置为 false 即可无缝切换到真实接口。
 */
const USE_MOCK = true

/* ================= 本地模拟数据（USE_MOCK = true 时生效） ================= */

const NAME_POOL = ['张伟', '王芳', '李娜', '刘洋', '陈静', '杨帆', '赵敏', '黄磊', '周涛', '吴琼']
const DEPTS = ['总公司', '研发部', '市场部']
const POSTS = ['董事长', '研发工程师', '测试工程师', '市场专员']
const ROLES = ['超级管理员', '普通角色']

let seq = 1
const MOCK_USERS: User[] = Array.from({ length: 128 }, (_, i) => {
  const name = i === 0 ? '管理员' : i === 1 ? '测试' : NAME_POOL[i % NAME_POOL.length]
  const username = i === 0 ? 'admin' : i === 1 ? 'test' : `${name}${seq + i}`.toLowerCase()
  return {
    id: seq++,
    username,
    nickname: name,
    mobile: `1${3 + (i % 7)}0${String(10000000 + i * 137).slice(0, 8)}`,
    deptName: DEPTS[i % DEPTS.length],
    postNames: [POSTS[(i + 1) % POSTS.length], ...(i % 3 === 0 ? [POSTS[(i + 2) % POSTS.length]] : [])],
    roleNames: [i === 0 ? ROLES[0] : ROLES[1]],
    status: i % 7 === 3 ? 0 : 1,
    createTime: `2026-${String((i % 9) + 1).padStart(2, '0')}-${String((i % 28) + 1).padStart(2, '0')} ${String((i * 7) % 24).padStart(2, '0')}:${String((i * 13) % 60).padStart(2, '0')}:00`,
  }
})

function mockDelay<T>(data: T): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(data), 260))
}

function filterUsers(query: UserPageQuery): PageResult<User> {
  const list = MOCK_USERS.filter(
    (u) =>
      (!query.username || u.username.includes(query.username) || u.nickname.includes(query.username)) &&
      (!query.mobile || u.mobile.includes(query.mobile)) &&
      (query.status === null || query.status === undefined || u.status === query.status) &&
      inDateRange(u.createTime, query.createTime),
  )
  const start = (query.pageNo - 1) * query.pageSize
  return { list: list.slice(start, start + query.pageSize), total: list.length }
}

/** 按创建时间范围过滤（时间戳比较） */
function inDateRange(time: string, range?: [number, number] | null): boolean {
  if (!range || range.length !== 2) return true
  const t = new Date(time.replace(' ', 'T')).getTime()
  return t >= range[0] && t <= range[1] + 86399999
}

/* ================= 用户 API ================= */

/** 分页查询用户列表 */
export function getUserPage(query: UserPageQuery): Promise<PageResult<User>> {
  if (USE_MOCK) {
    return mockDelay(filterUsers(query))
  }
  return request.get('/system/user/page', { params: query })
}

/** 创建用户 */
export function createUser(data: UserSaveDTO): Promise<number> {
  if (USE_MOCK) {
    return mockDelay(MOCK_USERS.push({ ...toUser(data), id: seq++ }))
  }
  return request.post('/system/user/create', data)
}

/** 更新用户 */
export function updateUser(id: number, data: UserSaveDTO): Promise<boolean> {
  if (USE_MOCK) {
    const u = MOCK_USERS.find((x) => x.id === id)
    if (u) Object.assign(u, toUser(data))
    return mockDelay(true)
  }
  return request.put('/system/user/update', { id, ...data })
}

/** 删除用户 */
export function deleteUser(id: number): Promise<boolean> {
  if (USE_MOCK) {
    const index = MOCK_USERS.findIndex((x) => x.id === id)
    if (index >= 0) MOCK_USERS.splice(index, 1)
    return mockDelay(true)
  }
  return request.delete('/system/user/delete', { params: { id } })
}

/** 用户统计（KPI 卡片用）。真实后端就绪后请提供对应统计接口 */
export function getUserStats(): Promise<{ total: number; enabled: number; disabled: number; todayNew: number }> {
  if (USE_MOCK) {
    const enabled = MOCK_USERS.filter((u) => u.status === 1).length
    const today = new Date().toISOString().slice(0, 10)
    return mockDelay({
      total: MOCK_USERS.length,
      enabled,
      disabled: MOCK_USERS.length - enabled,
      todayNew: MOCK_USERS.filter((u) => u.createTime.startsWith(today)).length,
    })
  }
  return request.get('/system/user/stats')
}

/** 将表单数据转换为用户行（模拟场景用） */
function toUser(data: UserSaveDTO): User {
  return {
    id: 0,
    username: data.username,
    nickname: data.nickname,
    mobile: data.mobile || '',
    deptName: DEPTS[(data.deptId ?? 1) - 1] ?? '总公司',
    postNames: (data.postIds || []).map((id) => POSTS[id - 1]).filter(Boolean),
    roleNames: (data.roleIds || []).map((id) => ROLES[id - 1]).filter(Boolean),
    status: data.status,
    createTime: new Date().toISOString().slice(0, 16).replace('T', ' '),
  }
}
