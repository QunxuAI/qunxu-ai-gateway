import request from '../request'
import { mockDelay, clone } from '../mock'

const USE_MOCK = true

export interface Dept {
  id: number
  name: string
  parentId: number
  sort: number
  leader: string
  phone: string
  status: 0 | 1
  createTime: string
  children?: Dept[]
}

const MOCK_DEPTS: Dept[] = [
  {
    id: 1, name: '总公司', parentId: 0, sort: 1, leader: '管理员', phone: '13800000001', status: 1, createTime: '2026-01-01 09:00:00',
    children: [
      {
        id: 2, name: '研发部', parentId: 1, sort: 1, leader: '张三', phone: '13800000002', status: 1, createTime: '2026-01-01 09:10:00',
        children: [
          { id: 5, name: '前端组', parentId: 2, sort: 1, leader: '李四', phone: '13800000005', status: 1, createTime: '2026-01-02 10:00:00' },
          { id: 6, name: '后端组', parentId: 2, sort: 2, leader: '王五', phone: '13800000006', status: 1, createTime: '2026-01-02 10:10:00' },
        ],
      },
      { id: 3, name: '市场部', parentId: 1, sort: 2, leader: '赵六', phone: '13800000003', status: 1, createTime: '2026-01-01 09:20:00' },
      { id: 4, name: '财务部', parentId: 1, sort: 3, leader: '钱七', phone: '13800000004', status: 0, createTime: '2026-01-01 09:30:00' },
    ],
  },
]

export function getDeptList(): Promise<Dept[]> {
  if (USE_MOCK) return mockDelay(clone(MOCK_DEPTS))
  return request.get('/system/dept/list')
}

export function createDept(data: Partial<Dept>): Promise<number> {
  if (USE_MOCK) {
    const d = { ...data, id: Date.now() % 100000, createTime: new Date().toISOString().slice(0, 16).replace('T', ' ') } as Dept
    const parent = MOCK_DEPTS[0]
    ;(parent.children ||= []).push(d)
    return mockDelay(d.id)
  }
  return request.post('/system/dept/create', data)
}

export function updateDept(id: number, data: Partial<Dept>): Promise<boolean> {
  if (USE_MOCK) return mockDelay(true)
  return request.put('/system/dept/update', { id, ...data })
}

export function deleteDept(id: number): Promise<boolean> {
  if (USE_MOCK) return mockDelay(true)
  return request.delete('/system/dept/delete', { params: { id } })
}
