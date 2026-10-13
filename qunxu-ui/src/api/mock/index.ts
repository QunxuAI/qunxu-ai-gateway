/**
 * 本地模拟数据公共设施。
 * 所有 mock 分支共用：模拟网络延迟、构造树、收集权限串。
 */

/** 模拟网络延迟 */
export function mockDelay<T>(data: T, ms = 240): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(data), ms))
}

/** 分页切片 */
export function paged<T>(list: T[], pageNo: number, pageSize: number): { list: T[]; total: number } {
  const start = (pageNo - 1) * pageSize
  return { list: list.slice(start, start + pageSize), total: list.length }
}

/** 菜单类型：1 目录 / 2 菜单 / 3 按钮 */
export type MenuType = 1 | 2 | 3

/** 菜单 VO（与后端 MenuRespVO 对齐） */
export interface MenuVO {
  id: number
  name: string
  permission: string | null
  type: MenuType
  sort: number
  parentId: number
  path: string | null
  icon: string | null
  component: string | null
  componentName: string | null
  status: number
  visible: boolean
  keepAlive: boolean
  alwaysShow: boolean
  createTime?: string
  children?: MenuVO[]
}

/** 深度复制，避免多个 mock 调用之间互相污染 */
export function clone<T>(data: T): T {
  return JSON.parse(JSON.stringify(data))
}

/** 从菜单树中收集所有权限字符串 */
export function collectPermissions(menus: MenuVO[]): string[] {
  const result: string[] = []
  const walk = (list: MenuVO[]) => {
    list.forEach((m) => {
      if (m.permission) result.push(m.permission)
      if (m.children?.length) walk(m.children)
    })
  }
  walk(menus)
  return result
}

/**
 * 菜单种子数据（与后端 sql/mysql.sql 的 system_menu 一致）。
 * 仅作为 mock 数据源；真实环境由后端 /admin-api/system/auth/get-menus 返回。
 */
export function buildMenuTree(): MenuVO[] {
  const mk = (partial: Partial<MenuVO> & { id: number; name: string; type: MenuType; parentId: number }): MenuVO => ({
    permission: null,
    sort: 1,
    path: null,
    icon: null,
    component: null,
    componentName: null,
    status: 1,
    visible: true,
    keepAlive: false,
    alwaysShow: false,
    createTime: '2026-01-01 00:00:00',
    children: [],
    ...partial,
  })
  const btn = (id: number, parentId: number, name: string, permission: string, sort: number): MenuVO =>
    mk({ id, parentId, name, permission, type: 3, sort })

  return [
    // 数据看板（一级菜单，置于系统管理之上）
    mk({
      id: 3100, name: '数据看板', type: 2, parentId: 0, path: '/gateway/dashboard', component: 'gateway/dashboard/index', sort: 0,
      children: [btn(3101, 3100, '数据看板查询', 'gateway:dashboard:query', 1)],
    }),
    mk({
      id: 1000, name: '系统管理', type: 1, parentId: 0, path: '/system', icon: 'settings', sort: 1,
      children: [
        mk({
          id: 1100, name: '用户管理', type: 2, parentId: 1000, path: 'user', component: 'system/user/index', sort: 1,
          children: [
            btn(1101, 1100, '用户查询', 'system:user:query', 1),
            btn(1102, 1100, '用户创建', 'system:user:create', 2),
            btn(1103, 1100, '用户更新', 'system:user:update', 3),
            btn(1104, 1100, '用户删除', 'system:user:delete', 4),
          ],
        }),
        mk({
          id: 1200, name: '角色管理', type: 2, parentId: 1000, path: 'role', component: 'system/role/index', sort: 2,
          children: [
            btn(1201, 1200, '角色查询', 'system:role:query', 1),
            btn(1202, 1200, '角色创建', 'system:role:create', 2),
            btn(1203, 1200, '角色更新', 'system:role:update', 3),
            btn(1204, 1200, '角色删除', 'system:role:delete', 4),
            btn(1205, 1200, '角色分配菜单', 'system:role:assign-menu', 5),
          ],
        }),
        mk({
          id: 1300, name: '菜单管理', type: 2, parentId: 1000, path: 'menu', component: 'system/menu/index', sort: 3,
          children: [
            btn(1301, 1300, '菜单查询', 'system:menu:query', 1),
            btn(1302, 1300, '菜单创建', 'system:menu:create', 2),
            btn(1303, 1300, '菜单更新', 'system:menu:update', 3),
            btn(1304, 1300, '菜单删除', 'system:menu:delete', 4),
          ],
        }),
        mk({
          id: 1400, name: '部门管理', type: 2, parentId: 1000, path: 'dept', component: 'system/dept/index', sort: 4,
          children: [
            btn(1401, 1400, '部门查询', 'system:dept:query', 1),
            btn(1402, 1400, '部门创建', 'system:dept:create', 2),
            btn(1403, 1400, '部门更新', 'system:dept:update', 3),
            btn(1404, 1400, '部门删除', 'system:dept:delete', 4),
          ],
        }),
        mk({
          id: 1500, name: '岗位管理', type: 2, parentId: 1000, path: 'post', component: 'system/post/index', sort: 5,
          children: [
            btn(1501, 1500, '岗位查询', 'system:post:query', 1),
            btn(1502, 1500, '岗位创建', 'system:post:create', 2),
            btn(1503, 1500, '岗位更新', 'system:post:update', 3),
            btn(1504, 1500, '岗位删除', 'system:post:delete', 4),
          ],
        }),
        mk({
          id: 1600, name: '字典管理', type: 2, parentId: 1000, path: 'dict', component: 'system/dict/index', sort: 6,
          children: [
            btn(1601, 1600, '字典查询', 'system:dict:query', 1),
            btn(1602, 1600, '字典创建', 'system:dict:create', 2),
            btn(1603, 1600, '字典更新', 'system:dict:update', 3),
            btn(1604, 1600, '字典删除', 'system:dict:delete', 4),
          ],
        }),
        mk({
          id: 1700, name: '租户管理', type: 2, parentId: 1000, path: 'tenant', component: 'system/tenant/index', sort: 7,
          children: [
            btn(1701, 1700, '租户查询', 'system:tenant:query', 1),
            btn(1702, 1700, '租户创建', 'system:tenant:create', 2),
            btn(1703, 1700, '租户更新', 'system:tenant:update', 3),
            btn(1704, 1700, '租户删除', 'system:tenant:delete', 4),
          ],
        }),
        mk({
          id: 1800, name: '租户套餐', type: 2, parentId: 1000, path: 'tenant-package', component: 'system/tenantPackage/index', sort: 8,
          children: [
            btn(1801, 1800, '租户套餐查询', 'system:tenant-package:query', 1),
            btn(1802, 1800, '租户套餐创建', 'system:tenant-package:create', 2),
            btn(1803, 1800, '租户套餐更新', 'system:tenant-package:update', 3),
            btn(1804, 1800, '租户套餐删除', 'system:tenant-package:delete', 4),
          ],
        }),
        mk({
          id: 1900, name: '通知公告', type: 2, parentId: 1000, path: 'notice', component: 'system/notice/index', sort: 9,
          children: [
            btn(1901, 1900, '通知公告查询', 'system:notice:query', 1),
            btn(1902, 1900, '通知公告创建', 'system:notice:create', 2),
            btn(1903, 1900, '通知公告更新', 'system:notice:update', 3),
            btn(1904, 1900, '通知公告删除', 'system:notice:delete', 4),
          ],
        }),
      ],
    }),
    mk({
      id: 2000, name: '日志管理', type: 1, parentId: 0, path: '/log', icon: 'log', sort: 2,
      children: [
        mk({
          id: 2100, name: '操作日志', type: 2, parentId: 2000, path: 'operate-log', component: 'system/operateLog/index', sort: 1,
          children: [btn(2101, 2100, '操作日志查询', 'system:operate-log:query', 1)],
        }),
        mk({
          id: 2200, name: '登录日志', type: 2, parentId: 2000, path: 'login-log', component: 'system/loginLog/index', sort: 2,
          children: [btn(2201, 2200, '登录日志查询', 'system:login-log:query', 1)],
        }),
      ],
    }),
    // 控制台（模型网关管理）——菜单种子与 sql/schema.sql / data.sql 的 system_menu 一致
    mk({
      id: 3000, name: '控制台', type: 1, parentId: 0, path: '/gateway', icon: 'gateway', sort: 3,
      children: [
        mk({
          id: 3950, name: '模型广场', type: 2, parentId: 3000, path: 'plaza', component: 'gateway/plaza/index', sort: 1,
          children: [btn(3951, 3950, '模型广场查询', 'gateway:plaza:query', 1)],
        }),
        mk({
          id: 3200, name: '供应商管理', type: 2, parentId: 3000, path: 'provider', component: 'gateway/provider/index', sort: 2,
          children: [
            btn(3201, 3200, '供应商查询', 'gateway:provider:query', 1),
            btn(3202, 3200, '供应商创建', 'gateway:provider:create', 2),
            btn(3203, 3200, '供应商更新', 'gateway:provider:update', 3),
            btn(3204, 3200, '供应商删除', 'gateway:provider:delete', 4),
          ],
        }),
        mk({
          id: 3300, name: '逻辑模型', type: 2, parentId: 3000, path: 'model', component: 'gateway/model/index', sort: 3,
          children: [
            btn(3301, 3300, '逻辑模型查询', 'gateway:model:query', 1),
            btn(3302, 3300, '逻辑模型创建', 'gateway:model:create', 2),
            btn(3303, 3300, '逻辑模型更新', 'gateway:model:update', 3),
            btn(3304, 3300, '逻辑模型删除', 'gateway:model:delete', 4),
          ],
        }),
        mk({
          id: 3400, name: '模型通道', type: 2, parentId: 3000, path: 'channel', component: 'gateway/channel/index', sort: 4,
          children: [
            btn(3401, 3400, '通道查询', 'gateway:channel:query', 1),
            btn(3402, 3400, '通道创建', 'gateway:channel:create', 2),
            btn(3403, 3400, '通道更新', 'gateway:channel:update', 3),
            btn(3404, 3400, '通道删除', 'gateway:channel:delete', 4),
          ],
        }),
        mk({
          id: 3500, name: '健康监控', type: 2, parentId: 3000, path: 'health', component: 'gateway/health/index', sort: 5,
          children: [btn(3501, 3500, '健康监控查询', 'gateway:health:query', 1)],
        }),
        mk({
          id: 3600, name: '调用审计', type: 2, parentId: 3000, path: 'audit', component: 'gateway/audit/index', sort: 6,
          children: [btn(3601, 3600, '审计查询', 'gateway:audit:query', 1)],
        }),
        mk({
          id: 3700, name: '安全策略', type: 2, parentId: 3000, path: 'security', component: 'gateway/security/index', sort: 7,
          children: [btn(3701, 3700, '安全策略查询', 'gateway:security:query', 1)],
        }),
        mk({
          id: 3800, name: '限流配额', type: 2, parentId: 3000, path: 'ratelimit', component: 'gateway/ratelimit/index', sort: 8,
          children: [btn(3801, 3800, '限流配额查询', 'gateway:ratelimit:query', 1)],
        }),
        mk({
          id: 3900, name: '令牌管理', type: 2, parentId: 3000, path: 'apikey', component: 'gateway/apikey/index', sort: 9,
          children: [
            btn(3901, 3900, '令牌查询', 'gateway:apikey:query', 1),
            btn(3902, 3900, '令牌创建', 'gateway:apikey:create', 2),
            btn(3903, 3900, '令牌更新', 'gateway:apikey:update', 3),
            btn(3904, 3900, '令牌删除', 'gateway:apikey:delete', 4),
          ],
        }),
      ],
    }),
  ]
}

/** 普通角色（test 用户）可访问的菜单：「数据看板」+「控制台 > 令牌管理」+「控制台 > 模型广场」（只读浏览） */
export function buildSubsetMenuTree(): MenuVO[] {
  const tree = buildMenuTree()
  const dashboard = tree.find((m) => m.id === 3100)!
  const consoleDir = tree.find((m) => m.id === 3000)!
  // 控制台目录下仅保留 令牌管理 + 模型广场（及其按钮）
  consoleDir.children = consoleDir.children?.filter((m) => m.id === 3900 || m.id === 3950) ?? []
  return [dashboard, consoleDir]
}

/** 普通角色（test 用户）的权限串（数据看板 + 令牌管理 + 模型广场） */
export const TEST_PERMISSIONS = [
  'gateway:dashboard:query',
  'gateway:apikey:query', 'gateway:apikey:create', 'gateway:apikey:update', 'gateway:apikey:delete',
  'gateway:plaza:query',
]

/** 全部权限串（admin 用户） */
export const ADMIN_PERMISSIONS = collectPermissions(buildMenuTree())
