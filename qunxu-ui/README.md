# Qunxu AI 前端（qunxu-ui）

基于 **Vue 3 + Vite + TypeScript + Naive UI** 的响应式管理后台，与后端 Spring Boot（`/admin-api`）对接。

## 技术栈

| 分类 | 选型 |
| --- | --- |
| 框架 | Vue 3.5（组合式 API + `<script setup>`） |
| 构建 | Vite 6 |
| 语言 | TypeScript |
| UI | Naive UI 2.x（内置深浅主题）+ @vicons/ionicons5 图标 |
| 路由 / 状态 | Vue Router 4（动态路由）+ Pinia |
| 请求 | Axios（统一 token / 租户头 / 错误处理） |
| 图表 | ECharts |

## 快速开始

```bash
npm install
npm run dev        # 开发：http://localhost:5180
npm run build      # 构建：产物输出到 dist/
npm run preview    # 预览构建产物
```

> 端口固定为 **5180**，避免与本机其他前端项目（如 5173）冲突；如需修改见 `vite.config.ts`。

## 登录与鉴权

- 登录页 `/login`：账号 / 密码 / 验证码（后端 `system/auth/get-captcha`），成功后写入 `localStorage.qunxu.token` 并跳转来源页。
- 路由守卫：未登录访问任意页面跳转登录页；已登录首次进入时拉取权限信息与菜单，注册动态路由后再放行。
- 默认演示账号：
  - `admin / admin123`：超级管理员，拥有全部菜单与按钮权限（系统管理 / 日志管理 / 控制台全部网关页面）；
  - `test / 123456`：普通角色，「数据看板」+「控制台 > 令牌管理」+「控制台 > 模型广场」（只读浏览）三个入口，本人数据隔离（用量统计与令牌均按本人隔离）。

## 动态路由与权限

- 登录后调用 `system/auth/get-permission-info`（角色 + 权限串）与 `system/auth/get-menus`（菜单树）。
- 菜单树按 `type`（1 目录 / 2 菜单 / 3 按钮）生成侧边栏；`type=2` 且带 `component` 的菜单懒加载对应视图并注册到路由。
- 按钮级权限：`v-permission="'system:role:create'"` 指令，无权限时移除元素；渲染函数中用 `directives: [{ name: 'permission', value }]`。
- 侧边栏底部用户卡片展示真实昵称 / 角色，退出登录重置权限路由。

## 目录结构

```
qunxu-ui
├── vite.config.ts            # 构建配置：@ 别名、/admin-api 代理到后端 48080
├── src
│   ├── api/                  # 接口层：request 封装 + system/* 各模块（均含本地 mock）
│   │   └── mock/             # 菜单种子树、权限串、分页/延迟工具
│   ├── composables/          # useChart（ECharts 封装：resize / 销毁）
│   ├── directives/           # v-permission 按钮权限指令
│   ├── layout/               # 响应式布局：侧边栏 / 顶栏 / 用户菜单
│   ├── router/               # 静态路由 + 守卫 + 动态路由注册（views.ts 组件加载器）
│   ├── stores/               # Pinia：app（主题/折叠）、user（鉴权）、permission（菜单/路由）
│   ├── utils/                # useResponsive 响应式断点、图标渲染等
│   └── views/                # login、dashboard、system/* 管理页面
```

## 响应式说明

- **< 768px（手机）**：侧边栏收起，改为左侧抽屉，点击菜单后自动关闭；KPI 卡片两列；表格横向滚动。
- **768px ~ 1024px（平板）**：侧边栏自动折叠为图标模式。
- **≥ 1024px（桌面）**：完整侧边栏，可手动折叠/展开（状态持久化）。
- 搜索表单列数、KPI 列数、弹窗宽度、图表布局等均按视口自适应。

断点统一由 `src/utils/useResponsive.ts` 管理。

## 数据对接

当前默认使用**本地模拟数据**（各 `src/api/system/*.ts` 内 `USE_MOCK = true`），无需后端即可运行演示。后端就绪后：

1. 将各模块 `USE_MOCK` 置为 `false`；
2. 启动后端（默认 `127.0.0.1:48080`），`vite.config.ts` 已配置 `/admin-api` 代理；
3. 请求自动携带 `Authorization: Bearer {token}` 与 `tenant-id` 头。

## 已实现

- 登录页 + 鉴权（登录 / 登出 / 验证码 / 路由守卫 / 退出登录）
- 动态路由 + 侧边栏菜单（由后端菜单树生成）+ `v-permission` 按钮级权限
- 响应式管理后台布局：侧边栏（Logo、动态菜单、底部用户卡片）、移动端抽屉、顶栏（折叠 / 面包屑 / 搜索 / 通知 / 主题切换）
- 管理页面（均含查询 / 分页 / 新增 / 编辑 / 删除）：
  - 用户管理、角色管理、菜单管理（树表）、部门管理（树表）、岗位管理
  - 字典管理（类型 + 数据项下钻）、租户管理、租户套餐、通知公告
  - 操作日志、登录日志（只读列表）
- 数据概览页：KPI 卡片 + 访问趋势折线图 / 角色分布环形图 / 部门人数柱状图（ECharts，响应式）

## 下一步规划

- [ ] 个人中心 / 修改密码（侧边栏用户菜单已预留入口）
- [ ] 角色分配菜单、用户分配角色（后端 `assign-role` / `assign-menu` 已就绪）
- [ ] 对接真实后端联调（登录、权限、各 CRUD）
