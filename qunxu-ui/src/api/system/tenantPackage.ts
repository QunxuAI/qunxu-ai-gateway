import request from '../request'
import { mockDelay, paged } from '../mock'

const USE_MOCK = true

export interface TenantPackage {
  id: number
  name: string
  status: 0 | 1
  remark: string
  menuIds: number[]
  createTime: string
}
export interface TenantPackagePageQuery {
  pageNo: number
  pageSize: number
  name?: string
  status?: 0 | 1 | null
}

let seq = 1
const MOCK_PACKAGES: TenantPackage[] = [
  { id: seq++, name: '标准版', status: 1, remark: '系统默认套餐', menuIds: [1000, 2000], createTime: '2026-01-01 09:00:00' },
  { id: seq++, name: '专业版', status: 1, remark: '含全部功能', menuIds: [1000, 2000], createTime: '2026-01-02 09:00:00' },
  { id: seq++, name: '试用版', status: 0, remark: '已停用', menuIds: [1000], createTime: '2026-01-03 09:00:00' },
]

export function getTenantPackagePage(query: TenantPackagePageQuery): Promise<{ list: TenantPackage[]; total: number }> {
  if (USE_MOCK) {
    const list = MOCK_PACKAGES.filter(
      (p) =>
        (!query.name || p.name.includes(query.name)) &&
        (query.status === null || query.status === undefined || p.status === query.status),
    )
    return mockDelay(paged(list, query.pageNo, query.pageSize))
  }
  return request.get('/system/tenant-package/page', { params: query })
}

export function createTenantPackage(data: Partial<TenantPackage>): Promise<number> {
  if (USE_MOCK) {
    MOCK_PACKAGES.push({ ...data, id: seq++, createTime: now() } as TenantPackage)
    return mockDelay(seq - 1)
  }
  return request.post('/system/tenant-package/create', data)
}

export function updateTenantPackage(id: number, data: Partial<TenantPackage>): Promise<boolean> {
  if (USE_MOCK) {
    const p = MOCK_PACKAGES.find((x) => x.id === id)
    if (p) Object.assign(p, data)
    return mockDelay(true)
  }
  return request.put('/system/tenant-package/update', { id, ...data })
}

export function deleteTenantPackage(id: number): Promise<boolean> {
  if (USE_MOCK) {
    const i = MOCK_PACKAGES.findIndex((x) => x.id === id)
    if (i >= 0) MOCK_PACKAGES.splice(i, 1)
    return mockDelay(true)
  }
  return request.delete('/system/tenant-package/delete', { params: { id } })
}

function now(): string {
  return new Date().toISOString().slice(0, 16).replace('T', ' ')
}
