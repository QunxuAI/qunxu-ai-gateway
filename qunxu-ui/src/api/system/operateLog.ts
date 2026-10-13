import request from '../request'
import { mockDelay, paged } from '../mock'

const USE_MOCK = true

export interface OperateLog {
  id: number
  userId: number
  username: string
  module: string
  name: string
  type: number // 1 查询 2 新增 3 修改 4 删除 5 其他
  success: boolean
  duration: number // 耗时 ms
  ip: string
  userAgent: string
  createTime: string
}
export interface OperateLogPageQuery {
  pageNo: number
  pageSize: number
  username?: string
  module?: string
  success?: boolean | null
}

const MODULES = ['系统管理-用户', '系统管理-角色', '系统管理-菜单', '系统管理-部门', '系统管理-岗位']
const NAMES = ['查询用户', '新增用户', '修改用户', '删除用户', '分配角色', '查询角色', '新增角色']
const TYPE_NAMES: Record<number, string> = { 1: '查询', 2: '新增', 3: '修改', 4: '删除', 5: '其他' }

let seq = 1
const MOCK_LOGS: OperateLog[] = Array.from({ length: 46 }, (_, i) => ({
  id: seq++,
  userId: i % 3 === 0 ? 1 : 2,
  username: i % 3 === 0 ? 'admin' : 'test',
  module: MODULES[i % MODULES.length],
  name: NAMES[(i + 2) % NAMES.length],
  type: (i % 4) + 1,
  success: i % 9 !== 7,
  duration: (i * 13) % 300 + 8,
  ip: `192.168.1.${(i % 200) + 1}`,
  userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X)',
  createTime: `2026-${String((i % 9) + 1).padStart(2, '0')}-${String((i % 28) + 1).padStart(2, '0')} ${String((i * 5) % 24).padStart(2, '0')}:${String((i * 11) % 60).padStart(2, '0')}:00`,
}))

export function getOperateLogPage(query: OperateLogPageQuery): Promise<{ list: OperateLog[]; total: number }> {
  if (USE_MOCK) {
    const list = MOCK_LOGS.filter(
      (l) =>
        (!query.username || l.username.includes(query.username)) &&
        (!query.module || l.module.includes(query.module)) &&
        (query.success === null || query.success === undefined || l.success === query.success),
    )
    return mockDelay(paged(list, query.pageNo, query.pageSize))
  }
  return request.get('/system/operate-log/page', { params: query })
}

export { TYPE_NAMES }
