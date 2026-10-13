import request from '../request'
import { mockDelay, paged, buildMenuTree, type MenuVO } from '../mock'

// 后端已就绪：角色列表/CRUD 与角色-菜单分配均对接真实接口（分配结果持久化到库）
const USE_MOCK = false

export interface Role {
  id: number
  name: string
  code: string
  sort: number
  status: 0 | 1
  remark: string
  createTime: string
}
export interface RolePageQuery {
  pageNo: number
  pageSize: number
  name?: string
  code?: string
  status?: 0 | 1 | null
}

let seq = 1
const MOCK_ROLES: Role[] = [
  { id: seq++, name: '超级管理员', code: 'super_admin', sort: 1, status: 1, remark: '拥有全部权限', createTime: '2026-01-01 10:00:00' },
  { id: seq++, name: '普通角色', code: 'common', sort: 2, status: 1, remark: '普通员工默认角色', createTime: '2026-01-02 11:30:00' },
  { id: seq++, name: '审计角色', code: 'auditor', sort: 3, status: 1, remark: '只读审计', createTime: '2026-01-05 09:20:00' },
  { id: seq++, name: '临时角色', code: 'temp', sort: 4, status: 0, remark: '已停用示例', createTime: '2026-02-01 15:45:00' },
]

function filter(query: RolePageQuery) {
  return MOCK_ROLES.filter(
    (r) =>
      (!query.name || r.name.includes(query.name)) &&
      (!query.code || r.code.includes(query.code)) &&
      (query.status === null || query.status === undefined || r.status === query.status),
  )
}

export function getRolePage(query: RolePageQuery): Promise<{ list: Role[]; total: number }> {
  if (USE_MOCK) return mockDelay(paged(filter(query), query.pageNo, query.pageSize))
  return request.get('/system/role/page', { params: query })
}

/** 角色简单列表（下拉用） */
export function getRoleSimpleList(): Promise<Role[]> {
  if (USE_MOCK) return mockDelay([...MOCK_ROLES])
  return request.get('/system/role/simple-list')
}

export function createRole(data: Partial<Role>): Promise<number> {
  if (USE_MOCK) {
    MOCK_ROLES.push({ ...data, id: seq++, createTime: now() } as Role)
    return mockDelay(seq - 1)
  }
  return request.post('/system/role/create', data)
}

export function updateRole(id: number, data: Partial<Role>): Promise<boolean> {
  if (USE_MOCK) {
    const r = MOCK_ROLES.find((x) => x.id === id)
    if (r) Object.assign(r, data)
    return mockDelay(true)
  }
  return request.put('/system/role/update', { id, ...data })
}

export function deleteRole(id: number): Promise<boolean> {
  if (USE_MOCK) {
    const i = MOCK_ROLES.findIndex((x) => x.id === id)
    if (i >= 0) MOCK_ROLES.splice(i, 1)
    return mockDelay(true)
  }
  return request.delete('/system/role/delete', { params: { id } })
}

/* ================= 角色-菜单分配 ================= */

/** 收集菜单树中全部节点 id */
function collectMenuIds(list: MenuVO[]): number[] {
  const ids: number[] = []
  const walk = (nodes: MenuVO[]) => {
    nodes.forEach((n) => {
      ids.push(n.id)
      if (n.children?.length) walk(n.children)
    })
  }
  walk(list)
  return ids
}

/** 角色-菜单分配 mock 数据源：超级管理员拥有全部菜单，其余角色为演示子集 */
const MOCK_ROLE_MENUS: Record<number, number[]> = (() => {
  const all = collectMenuIds(buildMenuTree())
  return {
    1: all, // 超级管理员
    2: [1000, 1100, 1101, 1102, 1200, 1201, 1202, 1300, 1301, 1302],
    3: [1000, 1100, 1101, 1200, 1201, 1300, 1301, 1400, 1401, 1500, 1501, 1600, 1601],
    4: [],
  }
})()

/** 获取角色拥有的菜单编号集合 */
export function getRoleMenuList(roleId: number): Promise<number[]> {
  if (USE_MOCK) return mockDelay([...(MOCK_ROLE_MENUS[roleId] ?? [])])
  return request.get('/system/role/get-menu-list', { params: { roleId } })
}

/** 分配角色菜单（覆盖式） */
export function assignRoleMenu(roleId: number, menuIds: number[]): Promise<boolean> {
  if (USE_MOCK) {
    MOCK_ROLE_MENUS[roleId] = [...menuIds]
    return mockDelay(true)
  }
  return request.put('/system/role/assign-menu', { roleId, menuIds })
}

function now(): string {
  return new Date().toISOString().slice(0, 16).replace('T', ' ')
}
