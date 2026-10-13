import request from '../request'
import { mockDelay, paged } from '../mock'

const USE_MOCK = true

export interface LoginLog {
  id: number
  username: string
  ip: string
  userAgent: string
  type: number // 1 登录 2 登出
  status: number // 0 失败 1 成功
  createTime: string
}
export interface LoginLogPageQuery {
  pageNo: number
  pageSize: number
  username?: string
  status?: number | null
}

let seq = 1
const MOCK_LOGS: LoginLog[] = Array.from({ length: 32 }, (_, i) => ({
  id: seq++,
  username: i % 4 === 0 ? 'admin' : 'test',
  ip: `192.168.1.${(i % 200) + 1}`,
  userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X) AppleWebKit/537.36',
  type: i % 5 === 4 ? 2 : 1,
  status: i % 7 === 6 ? 0 : 1,
  createTime: `2026-${String((i % 9) + 1).padStart(2, '0')}-${String((i % 28) + 1).padStart(2, '0')} ${String((i * 3) % 24).padStart(2, '0')}:${String((i * 17) % 60).padStart(2, '0')}:00`,
}))

export function getLoginLogPage(query: LoginLogPageQuery): Promise<{ list: LoginLog[]; total: number }> {
  if (USE_MOCK) {
    const list = MOCK_LOGS.filter(
      (l) =>
        (!query.username || l.username.includes(query.username)) &&
        (query.status === null || query.status === undefined || l.status === query.status),
    )
    return mockDelay(paged(list, query.pageNo, query.pageSize))
  }
  return request.get('/system/login-log/page', { params: query })
}
