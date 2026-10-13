import request from '../request'
import { mockDelay, clone, buildMenuTree, type MenuVO } from '../mock'

// 后端已就绪：菜单列表/角色分配菜单树均对接真实接口（含令牌管理等增量菜单）
const USE_MOCK = false

/** 菜单列表（树） */
export function getMenuList(): Promise<MenuVO[]> {
  if (USE_MOCK) return mockDelay(clone(buildMenuTree()))
  return request.get('/system/menu/list')
}

export function createMenu(data: Partial<MenuVO>): Promise<number> {
  if (USE_MOCK) return mockDelay(Date.now() % 100000)
  return request.post('/system/menu/create', data)
}

export function updateMenu(id: number, data: Partial<MenuVO>): Promise<boolean> {
  if (USE_MOCK) return mockDelay(true)
  return request.put('/system/menu/update', { id, ...data })
}

export function deleteMenu(id: number): Promise<boolean> {
  if (USE_MOCK) return mockDelay(true)
  return request.delete('/system/menu/delete', { params: { id } })
}
