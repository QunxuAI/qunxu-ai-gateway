-- =====================================================================
-- Qunxu AI - 初始化数据（DML）
-- 数据库：MySQL 8.0+（需先在 qunxu_ai 库中执行同目录 schema.sql 建表）
-- 说明：包含系统模块种子数据（租户/用户/角色/菜单/字典/公告）与
--       模型网关模块种子数据（安全围栏/内容审计默认策略、敏感词、限流配额规则）。
-- 请在 qunxu_ai 库中执行本文件。
-- =====================================================================

-- 强制导入客户端按 UTF-8 解释（避免 docker-entrypoint 默认 latin1 造成中文乱码）
SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 一、系统模块初始化数据
-- ---------------------------------------------------------------------

-- 租户套餐
INSERT INTO `system_tenant_package` (`id`, `name`, `status`, `menu_ids`, `remark`) VALUES
(1, '标准套餐', 1, '[1000,1100,1101,1102,1103,1104,1105,1106,1107,1200,1201,1202,1203,1204,1205,1300,1301,1302,1303,1304,1400,1401,1402,1403,1404,1500,1501,1502,1503,1504,1600,1601,1602,1603,1604,1700,1701,1702,1703,1704,1800,1801,1802,1803,1804,1900,1901,1902,1903,1904,2000,2100,2101,2200,2201]', '系统默认套餐');

-- 租户
INSERT INTO `system_tenant` (`id`, `name`, `contact_name`, `contact_mobile`, `contact_email`, `status`, `package_id`, `expire_time`, `account_count`, `website`, `remark`) VALUES
(1, '示例租户', '张三', '13800000000', 'zhangsan@qunxu.tech', 1, 1, '2099-12-31 23:59:59', 100, 'https://www.qunxu.tech', '系统默认租户');

-- OAuth2 客户端
INSERT INTO `system_oauth2_client` (`id`, `client_id`, `secret`, `name`, `description`, `status`, `access_token_validity_seconds`, `refresh_token_validity_seconds`) VALUES
(1, 'default', NULL, '默认管理端', 'Qunxu AI 默认管理后台客户端', 1, 2592000, 7776000);

-- 部门
INSERT INTO `system_dept` (`id`, `name`, `parent_id`, `sort`, `status`, `tenant_id`) VALUES
(100, '总公司', 0, 0, 1, 1),
(101, '研发部', 100, 1, 1, 1),
(102, '市场部', 100, 2, 1, 1);

-- 岗位
INSERT INTO `system_post` (`id`, `name`, `code`, `sort`, `status`, `tenant_id`) VALUES
(1, '董事长', 'ceo', 1, 1, 1),
(2, '研发工程师', 'dev', 2, 1, 1),
(3, '测试工程师', 'tester', 3, 1, 1);

-- 角色
INSERT INTO `system_role` (`id`, `name`, `code`, `sort`, `status`, `type`, `data_scope`, `data_scope_dept_ids`, `tenant_id`) VALUES
(1, '超级管理员', 'super_admin', 1, 1, 1, 1, NULL, 1),
(2, '普通角色', 'common', 2, 1, 2, 4, NULL, 1);

-- 用户（密码均为 admin123 / 123456，盐值为 ABCabc1234567890）
INSERT INTO `system_users` (`id`, `username`, `password`, `salt`, `nickname`, `mobile`, `email`, `status`, `dept_id`, `post_ids`, `tenant_id`) VALUES
(1, 'admin', 'PdHYcl0sFCSBU/3UElyOuGqdF2AL8jEuJXc751++5lA=', 'ABCabc1234567890', '管理员', '13800000001', 'admin@qunxu.tech', 1, 100, '[1]', 1),
(2, 'test',  '9bGEeF7WP38yDicHhFbHD15esGSpeWXG3aEhDW7hgIE=', 'ABCabc1234567890', '测试',   '13800000002', 'test@qunxu.tech',  1, 101, '[2,3]', 1);

-- 用户-角色
INSERT INTO `system_user_role` (`user_id`, `role_id`, `tenant_id`) VALUES
(1, 1, 1),
(2, 2, 1);

-- 用户-岗位
INSERT INTO `system_user_post` (`user_id`, `post_id`, `tenant_id`) VALUES
(1, 1, 1),
(2, 2, 1),
(2, 3, 1);

-- 菜单
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `status`, `tenant_id`) VALUES
-- 系统管理（目录）
(1000, '系统管理', NULL, 1, 1, 0, '/system', 'settings', NULL, 1, 1),
-- 用户管理
(1100, '用户管理', NULL, 2, 1, 1000, 'user', NULL, 'system/user/index', 1, 1),
(1101, '用户查询', 'system:user:query', 3, 1, 1100, NULL, NULL, NULL, 1, 1),
(1102, '用户创建', 'system:user:create', 3, 2, 1100, NULL, NULL, NULL, 1, 1),
(1103, '用户更新', 'system:user:update', 3, 3, 1100, NULL, NULL, NULL, 1, 1),
(1104, '用户删除', 'system:user:delete', 3, 4, 1100, NULL, NULL, NULL, 1, 1),
(1105, '用户修改密码', 'system:user:update-password', 3, 5, 1100, NULL, NULL, NULL, 1, 1),
(1106, '用户重置密码', 'system:user:reset-password', 3, 6, 1100, NULL, NULL, NULL, 1, 1),
(1107, '用户分配角色', 'system:user:assign-role', 3, 7, 1100, NULL, NULL, NULL, 1, 1),
-- 角色管理
(1200, '角色管理', NULL, 2, 2, 1000, 'role', NULL, 'system/role/index', 1, 1),
(1201, '角色查询', 'system:role:query', 3, 1, 1200, NULL, NULL, NULL, 1, 1),
(1202, '角色创建', 'system:role:create', 3, 2, 1200, NULL, NULL, NULL, 1, 1),
(1203, '角色更新', 'system:role:update', 3, 3, 1200, NULL, NULL, NULL, 1, 1),
(1204, '角色删除', 'system:role:delete', 3, 4, 1200, NULL, NULL, NULL, 1, 1),
(1205, '角色分配菜单', 'system:role:assign-menu', 3, 5, 1200, NULL, NULL, NULL, 1, 1),
-- 菜单管理
(1300, '菜单管理', NULL, 2, 3, 1000, 'menu', NULL, 'system/menu/index', 1, 1),
(1301, '菜单查询', 'system:menu:query', 3, 1, 1300, NULL, NULL, NULL, 1, 1),
(1302, '菜单创建', 'system:menu:create', 3, 2, 1300, NULL, NULL, NULL, 1, 1),
(1303, '菜单更新', 'system:menu:update', 3, 3, 1300, NULL, NULL, NULL, 1, 1),
(1304, '菜单删除', 'system:menu:delete', 3, 4, 1300, NULL, NULL, NULL, 1, 1),
-- 部门管理
(1400, '部门管理', NULL, 2, 4, 1000, 'dept', NULL, 'system/dept/index', 1, 1),
(1401, '部门查询', 'system:dept:query', 3, 1, 1400, NULL, NULL, NULL, 1, 1),
(1402, '部门创建', 'system:dept:create', 3, 2, 1400, NULL, NULL, NULL, 1, 1),
(1403, '部门更新', 'system:dept:update', 3, 3, 1400, NULL, NULL, NULL, 1, 1),
(1404, '部门删除', 'system:dept:delete', 3, 4, 1400, NULL, NULL, NULL, 1, 1),
-- 岗位管理
(1500, '岗位管理', NULL, 2, 5, 1000, 'post', NULL, 'system/post/index', 1, 1),
(1501, '岗位查询', 'system:post:query', 3, 1, 1500, NULL, NULL, NULL, 1, 1),
(1502, '岗位创建', 'system:post:create', 3, 2, 1500, NULL, NULL, NULL, 1, 1),
(1503, '岗位更新', 'system:post:update', 3, 3, 1500, NULL, NULL, NULL, 1, 1),
(1504, '岗位删除', 'system:post:delete', 3, 4, 1500, NULL, NULL, NULL, 1, 1),
-- 字典管理
(1600, '字典管理', NULL, 2, 6, 1000, 'dict', NULL, 'system/dict/index', 1, 1),
(1601, '字典查询', 'system:dict:query', 3, 1, 1600, NULL, NULL, NULL, 1, 1),
(1602, '字典创建', 'system:dict:create', 3, 2, 1600, NULL, NULL, NULL, 1, 1),
(1603, '字典更新', 'system:dict:update', 3, 3, 1600, NULL, NULL, NULL, 1, 1),
(1604, '字典删除', 'system:dict:delete', 3, 4, 1600, NULL, NULL, NULL, 1, 1),
-- 租户管理
(1700, '租户管理', NULL, 2, 7, 1000, 'tenant', NULL, 'system/tenant/index', 1, 1),
(1701, '租户查询', 'system:tenant:query', 3, 1, 1700, NULL, NULL, NULL, 1, 1),
(1702, '租户创建', 'system:tenant:create', 3, 2, 1700, NULL, NULL, NULL, 1, 1),
(1703, '租户更新', 'system:tenant:update', 3, 3, 1700, NULL, NULL, NULL, 1, 1),
(1704, '租户删除', 'system:tenant:delete', 3, 4, 1700, NULL, NULL, NULL, 1, 1),
-- 租户套餐
(1800, '租户套餐', NULL, 2, 8, 1000, 'tenant-package', NULL, 'system/tenantPackage/index', 1, 1),
(1801, '租户套餐查询', 'system:tenant-package:query', 3, 1, 1800, NULL, NULL, NULL, 1, 1),
(1802, '租户套餐创建', 'system:tenant-package:create', 3, 2, 1800, NULL, NULL, NULL, 1, 1),
(1803, '租户套餐更新', 'system:tenant-package:update', 3, 3, 1800, NULL, NULL, NULL, 1, 1),
(1804, '租户套餐删除', 'system:tenant-package:delete', 3, 4, 1800, NULL, NULL, NULL, 1, 1),
-- 通知公告
(1900, '通知公告', NULL, 2, 9, 1000, 'notice', NULL, 'system/notice/index', 1, 1),
(1901, '通知公告查询', 'system:notice:query', 3, 1, 1900, NULL, NULL, NULL, 1, 1),
(1902, '通知公告创建', 'system:notice:create', 3, 2, 1900, NULL, NULL, NULL, 1, 1),
(1903, '通知公告更新', 'system:notice:update', 3, 3, 1900, NULL, NULL, NULL, 1, 1),
(1904, '通知公告删除', 'system:notice:delete', 3, 4, 1900, NULL, NULL, NULL, 1, 1),
-- 认证权限
(1950, '认证登出', 'system:auth:logout', 3, 99, 1000, NULL, NULL, NULL, 1, 1),
(1951, '认证查询', 'system:auth:query', 3, 100, 1000, NULL, NULL, NULL, 1, 1),
-- 日志管理（目录）
(2000, '日志管理', NULL, 1, 2, 0, '/log', 'log', NULL, 1, 1),
(2100, '操作日志', NULL, 2, 1, 2000, 'operate-log', NULL, 'system/operateLog/index', 1, 1),
(2101, '操作日志查询', 'system:operate-log:query', 3, 1, 2100, NULL, NULL, NULL, 1, 1),
(2200, '登录日志', NULL, 2, 2, 2000, 'login-log', NULL, 'system/loginLog/index', 1, 1),
(2201, '登录日志查询', 'system:login-log:query', 3, 1, 2200, NULL, NULL, NULL, 1, 1);

-- 控制台（模型网关管理菜单）
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `status`, `tenant_id`) VALUES
-- 数据看板（一级菜单，置于系统管理之上）
(3100, '数据看板', NULL, 2, 0, 0, '/gateway/dashboard', NULL, 'gateway/dashboard/index', 1, 1),
(3101, '数据看板查询', 'gateway:dashboard:query', 3, 1, 3100, NULL, NULL, NULL, 1, 1),
-- 控制台（目录）
(3000, '控制台', NULL, 1, 3, 0, '/gateway', 'gateway', NULL, 1, 1),
-- 模型广场
(3950, '模型广场', NULL, 2, 1, 3000, 'plaza', NULL, 'gateway/plaza/index', 1, 1),
(3951, '模型广场查询', 'gateway:plaza:query', 3, 1, 3950, NULL, NULL, NULL, 1, 1),
-- 供应商管理
(3200, '供应商管理', NULL, 2, 2, 3000, 'provider', NULL, 'gateway/provider/index', 1, 1),
(3201, '供应商查询', 'gateway:provider:query', 3, 1, 3200, NULL, NULL, NULL, 1, 1),
(3202, '供应商创建', 'gateway:provider:create', 3, 2, 3200, NULL, NULL, NULL, 1, 1),
(3203, '供应商更新', 'gateway:provider:update', 3, 3, 3200, NULL, NULL, NULL, 1, 1),
(3204, '供应商删除', 'gateway:provider:delete', 3, 4, 3200, NULL, NULL, NULL, 1, 1),
-- 逻辑模型
(3300, '逻辑模型', NULL, 2, 3, 3000, 'model', NULL, 'gateway/model/index', 1, 1),
(3301, '逻辑模型查询', 'gateway:model:query', 3, 1, 3300, NULL, NULL, NULL, 1, 1),
(3302, '逻辑模型创建', 'gateway:model:create', 3, 2, 3300, NULL, NULL, NULL, 1, 1),
(3303, '逻辑模型更新', 'gateway:model:update', 3, 3, 3300, NULL, NULL, NULL, 1, 1),
(3304, '逻辑模型删除', 'gateway:model:delete', 3, 4, 3300, NULL, NULL, NULL, 1, 1),
-- 模型通道
(3400, '模型通道', NULL, 2, 4, 3000, 'channel', NULL, 'gateway/channel/index', 1, 1),
(3401, '通道查询', 'gateway:channel:query', 3, 1, 3400, NULL, NULL, NULL, 1, 1),
(3402, '通道创建', 'gateway:channel:create', 3, 2, 3400, NULL, NULL, NULL, 1, 1),
(3403, '通道更新', 'gateway:channel:update', 3, 3, 3400, NULL, NULL, NULL, 1, 1),
(3404, '通道删除', 'gateway:channel:delete', 3, 4, 3400, NULL, NULL, NULL, 1, 1),
-- 健康监控
(3500, '健康监控', NULL, 2, 5, 3000, 'health', NULL, 'gateway/health/index', 1, 1),
(3501, '健康监控查询', 'gateway:health:query', 3, 1, 3500, NULL, NULL, NULL, 1, 1),
-- 调用审计
(3600, '调用审计', NULL, 2, 6, 3000, 'audit', NULL, 'gateway/audit/index', 1, 1),
(3601, '审计查询', 'gateway:audit:query', 3, 1, 3600, NULL, NULL, NULL, 1, 1),
-- 安全策略
(3700, '安全策略', NULL, 2, 7, 3000, 'security', NULL, 'gateway/security/index', 1, 1),
(3701, '安全策略查询', 'gateway:security:query', 3, 1, 3700, NULL, NULL, NULL, 1, 1),
-- 限流配额
(3800, '限流配额', NULL, 2, 8, 3000, 'ratelimit', NULL, 'gateway/ratelimit/index', 1, 1),
(3801, '限流配额查询', 'gateway:ratelimit:query', 3, 1, 3800, NULL, NULL, NULL, 1, 1),
-- 令牌管理（管理员管全部/普通用户管自己的；普通角色也需可见以管理自身令牌）
(3900, '令牌管理', NULL, 2, 9, 3000, 'apikey', NULL, 'gateway/apikey/index', 1, 1),
(3901, '令牌查询', 'gateway:apikey:query', 3, 1, 3900, NULL, NULL, NULL, 1, 1),
(3902, '令牌创建', 'gateway:apikey:create', 3, 2, 3900, NULL, NULL, NULL, 1, 1),
(3903, '令牌更新', 'gateway:apikey:update', 3, 3, 3900, NULL, NULL, NULL, 1, 1),
(3904, '令牌删除', 'gateway:apikey:delete', 3, 4, 3900, NULL, NULL, NULL, 1, 1);

-- 角色-菜单（超级管理员拥有全部菜单）
INSERT INTO `system_role_menu` (`role_id`, `menu_id`, `tenant_id`)
SELECT 1, `id`, 1 FROM `system_menu` WHERE `deleted` = b'0';

-- 角色-菜单（普通角色 = 普通用户视角）
-- 「数据看板」+「控制台 > 令牌管理」+「控制台 > 模型广场」；数据隔离由接口层强制：
-- 数据看板用量统计按本人 callerId 过滤、令牌仅本人创建的密钥；模型广场为只读浏览，逻辑模型为全局配置。
INSERT INTO `system_role_menu` (`role_id`, `menu_id`, `tenant_id`) VALUES
(2, 3100, 1), (2, 3101, 1),
(2, 3000, 1), (2, 3900, 1), (2, 3901, 1), (2, 3902, 1), (2, 3903, 1), (2, 3904, 1),
(2, 3950, 1), (2, 3951, 1);

-- 字典类型
INSERT INTO `system_dict_type` (`id`, `name`, `type`, `status`, `tenant_id`) VALUES
(1, '通用状态', 'common_status', 1, 1),
(2, '用户性别', 'system_user_sex', 1, 1),
(3, '通知类型', 'system_notice_type', 1, 1);

-- 字典数据
INSERT INTO `system_dict_data` (`sort`, `label`, `value`, `dict_type`, `status`, `color_type`, `tenant_id`) VALUES
(1, '开启', '1', 'common_status', 1, 'success', 1),
(2, '关闭', '0', 'common_status', 1, 'danger', 1),
(1, '男', '1', 'system_user_sex', 1, 'primary', 1),
(2, '女', '2', 'system_user_sex', 1, 'primary', 1),
(3, '未知', '0', 'system_user_sex', 1, 'info', 1),
(1, '通知', '1', 'system_notice_type', 1, 'primary', 1),
(2, '公告', '2', 'system_notice_type', 1, 'warning', 1);

-- 通知公告
INSERT INTO `system_notice` (`title`, `content`, `type`, `status`, `tenant_id`) VALUES
('系统上线公告', '欢迎使用 QunxuAI-Gateway 统一模型网关，本公告由系统初始化数据生成。', 2, 1, 1);

-- ---------------------------------------------------------------------
-- 二、模型网关模块初始化数据
-- ---------------------------------------------------------------------

-- 安全围栏默认策略（GLOBAL，覆盖全量请求）
INSERT INTO guardrail_policy (name, scope, scope_value, rules_json, action, enabled, priority, deleted)
SELECT '默认围栏策略', 'GLOBAL', NULL,
       JSON_OBJECT(
           'sensitiveWord', JSON_OBJECT('enabled', TRUE),
           'pii', JSON_OBJECT('enabled', TRUE, 'mask', TRUE),
           'length', JSON_OBJECT('maxInput', 32768, 'maxOutput', 65536),
           'promptInjection', JSON_OBJECT('enabled', TRUE, 'action', 'WARN')
       ),
       'BLOCK', 1, 100, 0
WHERE NOT EXISTS (SELECT 1 FROM guardrail_policy WHERE scope = 'GLOBAL' AND deleted = 0 AND name = '默认围栏策略');

-- 内容审计默认策略（GLOBAL，内置关键词审计 + 默认拦截）
INSERT INTO moderation_policy (name, scope, scope_value, provider, config_json, action, enabled, priority, deleted)
SELECT '默认内容审计', 'GLOBAL', NULL, 'builtin', JSON_OBJECT(), 'BLOCK', 1, 100, 0
WHERE NOT EXISTS (SELECT 1 FROM moderation_policy WHERE scope = 'GLOBAL' AND deleted = 0 AND name = '默认内容审计');

-- 敏感词基础库（与 classpath sensitive-words.seed 一致）
INSERT INTO sensitive_word (word, category, level, action, enabled, deleted)
SELECT '赌博', '广告', 'HIGH', 'BLOCK', 1, 0
WHERE NOT EXISTS (SELECT 1 FROM sensitive_word WHERE word = '赌博' AND deleted = 0);

INSERT INTO sensitive_word (word, category, level, action, enabled, deleted)
SELECT '诈骗', '广告', 'HIGH', 'BLOCK', 1, 0
WHERE NOT EXISTS (SELECT 1 FROM sensitive_word WHERE word = '诈骗' AND deleted = 0);

INSERT INTO sensitive_word (word, category, level, action, enabled, deleted)
SELECT '色情', '色情', 'HIGH', 'BLOCK', 1, 0
WHERE NOT EXISTS (SELECT 1 FROM sensitive_word WHERE word = '色情' AND deleted = 0);

INSERT INTO sensitive_word (word, category, level, action, enabled, deleted)
SELECT '毒品', '暴恐', 'HIGH', 'BLOCK', 1, 0
WHERE NOT EXISTS (SELECT 1 FROM sensitive_word WHERE word = '毒品' AND deleted = 0);

INSERT INTO sensitive_word (word, category, level, action, enabled, deleted)
SELECT '枪支', '暴恐', 'HIGH', 'BLOCK', 1, 0
WHERE NOT EXISTS (SELECT 1 FROM sensitive_word WHERE word = '枪支' AND deleted = 0);

INSERT INTO sensitive_word (word, category, level, action, enabled, deleted)
SELECT '暴力', '暴恐', 'MEDIUM', 'BLOCK', 1, 0
WHERE NOT EXISTS (SELECT 1 FROM sensitive_word WHERE word = '暴力' AND deleted = 0);

INSERT INTO sensitive_word (word, category, level, action, enabled, deleted)
SELECT '代办发票', '广告', 'MEDIUM', 'MASK', 1, 0
WHERE NOT EXISTS (SELECT 1 FROM sensitive_word WHERE word = '代办发票' AND deleted = 0);

-- 限流配额规则（全局开关/存储/突发系数/配额维度由 application.yml 的 gateway.rate-limit.* / gateway.quota.* 控制）
INSERT INTO rate_limit_rule (name, rule_type, target, permits_per_minute, daily_tokens, enabled)
SELECT '调用人默认限流', 'CALLER_DEFAULT', NULL, 600, NULL, 1
WHERE NOT EXISTS (SELECT 1 FROM rate_limit_rule WHERE rule_type = 'CALLER_DEFAULT' AND target IS NULL AND deleted = 0);

INSERT INTO rate_limit_rule (name, rule_type, target, permits_per_minute, daily_tokens, enabled)
SELECT '模型默认限流', 'MODEL_DEFAULT', NULL, 300, NULL, 1
WHERE NOT EXISTS (SELECT 1 FROM rate_limit_rule WHERE rule_type = 'MODEL_DEFAULT' AND target IS NULL AND deleted = 0);

INSERT INTO rate_limit_rule (name, rule_type, target, permits_per_minute, daily_tokens, enabled)
SELECT '每日 Token 配额（默认）', 'QUOTA_DEFAULT', NULL, 0, 0, 1
WHERE NOT EXISTS (SELECT 1 FROM rate_limit_rule WHERE rule_type = 'QUOTA_DEFAULT' AND target IS NULL AND deleted = 0);
