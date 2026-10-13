import request from '../request'
import { mockDelay, paged } from '../mock'

const USE_MOCK = false

export interface Notice {
  id: number
  title: string
  type: 1 | 2 // 1 通知 / 2 公告
  content: string
  status: 0 | 1
  createTime: string
}
export interface NoticePageQuery {
  pageNo: number
  pageSize: number
  title?: string
  status?: 0 | 1 | null
}

let seq = 1
const MOCK_NOTICES: Notice[] = [
  { id: seq++, title: '关于系统上线通知', type: 1, content: '系统将于本周五晚 22:00 上线，请各部门提前做好准备。', status: 1, createTime: '2026-03-01 10:00:00' },
  { id: seq++, title: '2026 年度放假安排公告', type: 2, content: '根据国家规定，结合公司实际情况，2026 年节假日安排如下：……', status: 1, createTime: '2026-03-05 14:00:00' },
  { id: seq++, title: '关于开展安全培训的通知', type: 1, content: '请全体研发人员于下周三参加信息安全培训。', status: 1, createTime: '2026-03-10 09:30:00' },
  { id: seq++, title: '旧版系统下架公告', type: 2, content: '旧版系统将于月底停止服务。', status: 0, createTime: '2026-02-01 08:00:00' },
]

export function getNoticePage(query: NoticePageQuery): Promise<{ list: Notice[]; total: number }> {
  if (USE_MOCK) {
    const list = MOCK_NOTICES.filter(
      (n) =>
        (!query.title || n.title.includes(query.title)) &&
        (query.status === null || query.status === undefined || n.status === query.status),
    )
    return mockDelay(paged(list, query.pageNo, query.pageSize))
  }
  return request.get('/system/notice/page', { params: query })
}

export function getNotice(id: number): Promise<Notice> {
  if (USE_MOCK) {
    const n = MOCK_NOTICES.find((x) => x.id === id)
    return mockDelay(n ?? ({} as Notice))
  }
  return request.get('/system/notice/get', { params: { id } })
}

export function createNotice(data: Partial<Notice>): Promise<number> {
  if (USE_MOCK) {
    MOCK_NOTICES.push({ ...data, id: seq++, createTime: now() } as Notice)
    return mockDelay(seq - 1)
  }
  return request.post('/system/notice/create', data)
}

export function updateNotice(id: number, data: Partial<Notice>): Promise<boolean> {
  if (USE_MOCK) {
    const n = MOCK_NOTICES.find((x) => x.id === id)
    if (n) Object.assign(n, data)
    return mockDelay(true)
  }
  return request.put('/system/notice/update', { id, ...data })
}

export function deleteNotice(id: number): Promise<boolean> {
  if (USE_MOCK) {
    const i = MOCK_NOTICES.findIndex((x) => x.id === id)
    if (i >= 0) MOCK_NOTICES.splice(i, 1)
    return mockDelay(true)
  }
  return request.delete('/system/notice/delete', { params: { id } })
}

function now(): string {
  return new Date().toISOString().slice(0, 16).replace('T', ' ')
}
