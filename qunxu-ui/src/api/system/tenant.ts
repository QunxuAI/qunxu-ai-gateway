import request from '../request'
import { mockDelay, paged } from '../mock'

const USE_MOCK = true

export interface Tenant {
  id: number
  name: string
  packageId: number
  contactName: string
  contactMobile: string
  status: 0 | 1
  expireTime: string
  remark: string
  createTime: string
}
export interface TenantPageQuery {
  pageNo: number
  pageSize: number
  name?: string
  contactName?: string
  status?: 0 | 1 | null
}

let seq = 1
const MOCK_TENANTS: Tenant[] = [
  { id: seq++, name: '芋道科技有限公司', packageId: 1, contactName: '管理员', contactMobile: '13800000001', status: 1, expireTime: '2099-12-31 23:59:59', remark: '系统内置租户', createTime: '2026-01-01 09:00:00' },
  { id: seq++, name: '群星网络科技', packageId: 1, contactName: '张三', contactMobile: '13800000002', status: 1, expireTime: '2026-12-31 23:59:59', remark: '', createTime: '2026-01-05 10:00:00' },
  { id: seq++, name: '云帆信息技术', packageId: 2, contactName: '李四', contactMobile: '13800000003', status: 1, expireTime: '2026-06-30 23:59:59', remark: '', createTime: '2026-02-01 11:00:00' },
  { id: seq++, name: '示例租户', packageId: 2, contactName: '王五', contactMobile: '13800000004', status: 0, expireTime: '2026-03-01 00:00:00', remark: '已过期停用', createTime: '2026-02-10 14:00:00' },
]

export function getTenantPage(query: TenantPageQuery): Promise<{ list: Tenant[]; total: number }> {
  if (USE_MOCK) {
    const list = MOCK_TENANTS.filter(
      (t) =>
        (!query.name || t.name.includes(query.name)) &&
        (!query.contactName || t.contactName.includes(query.contactName)) &&
        (query.status === null || query.status === undefined || t.status === query.status),
    )
    return mockDelay(paged(list, query.pageNo, query.pageSize))
  }
  return request.get('/system/tenant/page', { params: query })
}

export function createTenant(data: Partial<Tenant>): Promise<number> {
  if (USE_MOCK) {
    MOCK_TENANTS.push({ ...data, id: seq++, createTime: now() } as Tenant)
    return mockDelay(seq - 1)
  }
  return request.post('/system/tenant/create', data)
}

export function updateTenant(id: number, data: Partial<Tenant>): Promise<boolean> {
  if (USE_MOCK) {
    const t = MOCK_TENANTS.find((x) => x.id === id)
    if (t) Object.assign(t, data)
    return mockDelay(true)
  }
  return request.put('/system/tenant/update', { id, ...data })
}

export function deleteTenant(id: number): Promise<boolean> {
  if (USE_MOCK) {
    const i = MOCK_TENANTS.findIndex((x) => x.id === id)
    if (i >= 0) MOCK_TENANTS.splice(i, 1)
    return mockDelay(true)
  }
  return request.delete('/system/tenant/delete', { params: { id } })
}

function now(): string {
  return new Date().toISOString().slice(0, 16).replace('T', ' ')
}
