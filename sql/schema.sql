-- =====================================================================
-- Qunxu AI - 数据结构（DDL）
-- 数据库：MySQL 8.0+，字符集 utf8mb4
-- 说明：本文件仅包含建表语句；初始化数据见同目录 data.sql。
--       所有业务表均含 creator/create_time/updater/update_time/deleted
--       多租户表额外包含 tenant_id 字段
--
-- 导入前请先建库（示例）：
--   CREATE DATABASE IF NOT EXISTS `qunxu_ai` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- 然后在 qunxu_ai 库中依次执行：
--   mysql -uroot -p qunxu_ai < schema.sql
--   mysql -uroot -p qunxu_ai < data.sql
-- =====================================================================

-- 强制导入客户端按 UTF-8 解释（避免 docker-entrypoint 默认 latin1 造成中文乱码）
SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 一、系统模块表（多租户 / 用户 / 部门 / 岗位 / 角色 / 菜单 / 字典 / 日志）
-- ---------------------------------------------------------------------

-- 1. 租户
DROP TABLE IF EXISTS `system_tenant`;
CREATE TABLE `system_tenant` (
    `id`             bigint NOT NULL AUTO_INCREMENT COMMENT '租户编号',
    `name`           varchar(64)  NOT NULL COMMENT '租户名',
    `contact_name`   varchar(32)  DEFAULT NULL COMMENT '联系人',
    `contact_mobile` varchar(32)  DEFAULT NULL COMMENT '联系手机',
    `contact_email`  varchar(64)  DEFAULT NULL COMMENT '联系邮箱',
    `status`         tinyint NOT NULL DEFAULT 1 COMMENT '状态（0 关闭 1 开启）',
    `package_id`     bigint NOT NULL COMMENT '套餐编号',
    `expire_time`    datetime DEFAULT NULL COMMENT '过期时间',
    `account_count`  int NOT NULL DEFAULT 10 COMMENT '账号数量',
    `website`        varchar(128) DEFAULT NULL COMMENT '官网地址',
    `remark`         varchar(255) DEFAULT NULL COMMENT '备注',
    `creator`        varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`    datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`        varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`    datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        tinyint NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB COMMENT = '租户表';

-- 2. 租户套餐
DROP TABLE IF EXISTS `system_tenant_package`;
CREATE TABLE `system_tenant_package` (
    `id`          bigint NOT NULL AUTO_INCREMENT COMMENT '套餐编号',
    `name`        varchar(64)  NOT NULL COMMENT '套餐名',
    `status`      tinyint NOT NULL DEFAULT 1 COMMENT '状态（0 关闭 1 开启）',
    `menu_ids`    varchar(2048) DEFAULT NULL COMMENT '菜单编号集合（JSON）',
    `remark`      varchar(255) DEFAULT NULL COMMENT '备注',
    `creator`     varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`     varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     tinyint NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB COMMENT = '租户套餐表';

-- 3. OAuth2 客户端
DROP TABLE IF EXISTS `system_oauth2_client`;
CREATE TABLE `system_oauth2_client` (
    `id`                          bigint NOT NULL AUTO_INCREMENT COMMENT '客户端编号',
    `client_id`                   varchar(64)  NOT NULL COMMENT '客户端 ID',
    `secret`                      varchar(128) DEFAULT NULL COMMENT '客户端密钥',
    `name`                        varchar(64)  DEFAULT NULL COMMENT '客户端名称',
    `logo`                        varchar(255) DEFAULT NULL COMMENT '客户端 Logo',
    `description`                 varchar(255) DEFAULT NULL COMMENT '客户端描述',
    `status`                      int NOT NULL DEFAULT 1 COMMENT '状态：0 禁用 / 1 启用',
    `access_token_validity_seconds`  int NOT NULL DEFAULT 2592000 COMMENT '访问令牌有效期（秒）',
    `refresh_token_validity_seconds` int NOT NULL DEFAULT 7776000 COMMENT '刷新令牌有效期（秒）',
    `redirect_uris`               varchar(1024) DEFAULT NULL COMMENT '回调地址（JSON）',
    `scopes`                      varchar(1024) DEFAULT NULL COMMENT '授权范围（JSON）',
    `creator`                     varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`                 datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`                     varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`                 datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`                     tinyint NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_client_id` (`client_id`)
) ENGINE = InnoDB COMMENT = 'OAuth2 客户端表';

-- 4. 用户
DROP TABLE IF EXISTS `system_users`;
CREATE TABLE `system_users` (
    `id`          bigint NOT NULL AUTO_INCREMENT COMMENT '用户编号',
    `username`    varchar(32)  NOT NULL COMMENT '账号',
    `password`    varchar(64)  NOT NULL COMMENT '密码（加盐加密）',
    `salt`        varchar(16)  NOT NULL COMMENT '盐值',
    `nickname`    varchar(32)  NOT NULL COMMENT '昵称',
    `mobile`      varchar(32)  DEFAULT NULL COMMENT '手机号',
    `email`       varchar(64)  DEFAULT NULL COMMENT '邮箱',
    `avatar`      varchar(255) DEFAULT NULL COMMENT '头像',
    `status`      tinyint NOT NULL DEFAULT 1 COMMENT '状态（0 关闭 1 开启）',
    `dept_id`     bigint DEFAULT NULL COMMENT '部门编号',
    `post_ids`    varchar(255) DEFAULT NULL COMMENT '岗位编号集合（JSON）',
    `remark`      varchar(255) DEFAULT NULL COMMENT '备注',
    `tenant_id`   bigint NOT NULL DEFAULT 1 COMMENT '租户编号',
    `creator`     varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`     varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     tinyint NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username_tenant` (`username`, `tenant_id`),
    KEY `idx_dept_id` (`dept_id`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE = InnoDB COMMENT = '用户表';

-- 5. 部门
DROP TABLE IF EXISTS `system_dept`;
CREATE TABLE `system_dept` (
    `id`             bigint NOT NULL AUTO_INCREMENT COMMENT '部门编号',
    `name`           varchar(32)  NOT NULL COMMENT '部门名称',
    `parent_id`      bigint NOT NULL DEFAULT 0 COMMENT '父部门编号',
    `sort`           int NOT NULL DEFAULT 0 COMMENT '显示顺序',
    `leader_user_id` bigint DEFAULT NULL COMMENT '负责人用户编号',
    `phone`          varchar(32)  DEFAULT NULL COMMENT '联系电话',
    `email`          varchar(64)  DEFAULT NULL COMMENT '邮箱',
    `status`         tinyint NOT NULL DEFAULT 1 COMMENT '状态（0 关闭 1 开启）',
    `tenant_id`      bigint NOT NULL DEFAULT 1 COMMENT '租户编号',
    `creator`        varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`    datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`        varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`    datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        tinyint NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    KEY `idx_parent_id` (`parent_id`)
) ENGINE = InnoDB COMMENT = '部门表';

-- 6. 岗位
DROP TABLE IF EXISTS `system_post`;
CREATE TABLE `system_post` (
    `id`          bigint NOT NULL AUTO_INCREMENT COMMENT '岗位编号',
    `name`        varchar(32)  NOT NULL COMMENT '岗位名称',
    `code`        varchar(32)  NOT NULL COMMENT '岗位编码',
    `sort`        int NOT NULL DEFAULT 0 COMMENT '显示顺序',
    `status`      tinyint NOT NULL DEFAULT 1 COMMENT '状态（0 关闭 1 开启）',
    `remark`      varchar(255) DEFAULT NULL COMMENT '备注',
    `tenant_id`   bigint NOT NULL DEFAULT 1 COMMENT '租户编号',
    `creator`     varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`     varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     tinyint NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_tenant` (`code`, `tenant_id`)
) ENGINE = InnoDB COMMENT = '岗位表';

-- 7. 角色
DROP TABLE IF EXISTS `system_role`;
CREATE TABLE `system_role` (
    `id`                 bigint NOT NULL AUTO_INCREMENT COMMENT '角色编号',
    `name`               varchar(32)  NOT NULL COMMENT '角色名称',
    `code`               varchar(32)  NOT NULL COMMENT '角色编码',
    `sort`               int NOT NULL DEFAULT 0 COMMENT '显示顺序',
    `status`             tinyint NOT NULL DEFAULT 1 COMMENT '状态（0 关闭 1 开启）',
    `type`               tinyint NOT NULL DEFAULT 2 COMMENT '角色类型（1 系统内置 2 自定义）',
    `data_scope`         tinyint NOT NULL DEFAULT 1 COMMENT '数据范围（1 全部 2 指定部门 3 本部门 4 本部门及以下 5 仅本人）',
    `data_scope_dept_ids` varchar(255) DEFAULT NULL COMMENT '数据范围-指定部门编号集合（JSON）',
    `remark`             varchar(255) DEFAULT NULL COMMENT '备注',
    `tenant_id`          bigint NOT NULL DEFAULT 1 COMMENT '租户编号',
    `creator`            varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`        datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`            varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`        datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`            tinyint NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_tenant` (`code`, `tenant_id`)
) ENGINE = InnoDB COMMENT = '角色表';

-- 8. 菜单
DROP TABLE IF EXISTS `system_menu`;
CREATE TABLE `system_menu` (
    `id`             bigint NOT NULL AUTO_INCREMENT COMMENT '菜单编号',
    `name`           varchar(32)  NOT NULL COMMENT '菜单名称',
    `permission`     varchar(128) DEFAULT NULL COMMENT '权限标识',
    `type`           tinyint NOT NULL COMMENT '菜单类型（1 目录 2 菜单 3 按钮）',
    `sort`           int NOT NULL DEFAULT 0 COMMENT '显示顺序',
    `parent_id`      bigint NOT NULL DEFAULT 0 COMMENT '父菜单编号',
    `path`           varchar(128) DEFAULT NULL COMMENT '路由地址',
    `icon`           varchar(64)  DEFAULT NULL COMMENT '图标',
    `component`      varchar(128) DEFAULT NULL COMMENT '组件路径',
    `component_name` varchar(64)  DEFAULT NULL COMMENT '组件名',
    `status`         tinyint NOT NULL DEFAULT 1 COMMENT '状态（0 关闭 1 开启）',
    `visible`        bit NOT NULL DEFAULT b'1' COMMENT '是否可见（0 隐藏 1 显示）',
    `keep_alive`     bit NOT NULL DEFAULT b'1' COMMENT '是否缓存（0 不缓存 1 缓存）',
    `always_show`    bit NOT NULL DEFAULT b'1' COMMENT '是否总是显示（0 否 1 是）',
    `tenant_id`      bigint NOT NULL DEFAULT 1 COMMENT '租户编号',
    `creator`        varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`    datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`        varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`    datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        tinyint NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    KEY `idx_parent_id` (`parent_id`)
) ENGINE = InnoDB COMMENT = '菜单表';

-- 9. 用户-角色关联
DROP TABLE IF EXISTS `system_user_role`;
CREATE TABLE `system_user_role` (
    `id`          bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
    `user_id`     bigint NOT NULL COMMENT '用户编号',
    `role_id`     bigint NOT NULL COMMENT '角色编号',
    `tenant_id`   bigint NOT NULL DEFAULT 1 COMMENT '租户编号',
    `creator`     varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`     varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     tinyint NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_role_id` (`role_id`)
) ENGINE = InnoDB COMMENT = '用户-角色关联表';

-- 10. 角色-菜单关联
DROP TABLE IF EXISTS `system_role_menu`;
CREATE TABLE `system_role_menu` (
    `id`          bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
    `role_id`     bigint NOT NULL COMMENT '角色编号',
    `menu_id`     bigint NOT NULL COMMENT '菜单编号',
    `tenant_id`   bigint NOT NULL DEFAULT 1 COMMENT '租户编号',
    `creator`     varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`     varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     tinyint NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    KEY `idx_role_id` (`role_id`),
    KEY `idx_menu_id` (`menu_id`)
) ENGINE = InnoDB COMMENT = '角色-菜单关联表';

-- 11. 用户-岗位关联
DROP TABLE IF EXISTS `system_user_post`;
CREATE TABLE `system_user_post` (
    `id`          bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
    `user_id`     bigint NOT NULL COMMENT '用户编号',
    `post_id`     bigint NOT NULL COMMENT '岗位编号',
    `tenant_id`   bigint NOT NULL DEFAULT 1 COMMENT '租户编号',
    `creator`     varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`     varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     tinyint NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB COMMENT = '用户-岗位关联表';

-- 12. 字典类型
DROP TABLE IF EXISTS `system_dict_type`;
CREATE TABLE `system_dict_type` (
    `id`          bigint NOT NULL AUTO_INCREMENT COMMENT '字典类型编号',
    `name`        varchar(64)  NOT NULL COMMENT '字典名称',
    `type`        varchar(64)  NOT NULL COMMENT '字典类型',
    `status`      tinyint NOT NULL DEFAULT 1 COMMENT '状态（0 关闭 1 开启）',
    `remark`      varchar(255) DEFAULT NULL COMMENT '备注',
    `tenant_id`   bigint NOT NULL DEFAULT 1 COMMENT '租户编号',
    `creator`     varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`     varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     tinyint NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_type_tenant` (`type`, `tenant_id`)
) ENGINE = InnoDB COMMENT = '字典类型表';

-- 13. 字典数据
DROP TABLE IF EXISTS `system_dict_data`;
CREATE TABLE `system_dict_data` (
    `id`          bigint NOT NULL AUTO_INCREMENT COMMENT '字典数据编号',
    `sort`        int NOT NULL DEFAULT 0 COMMENT '显示顺序',
    `label`       varchar(64)  NOT NULL COMMENT '字典标签',
    `value`       varchar(64)  NOT NULL COMMENT '字典键值',
    `dict_type`   varchar(64)  NOT NULL COMMENT '字典类型',
    `status`      tinyint NOT NULL DEFAULT 1 COMMENT '状态（0 关闭 1 开启）',
    `color_type`  varchar(16)  DEFAULT NULL COMMENT '颜色类型',
    `css_class`   varchar(64)  DEFAULT NULL COMMENT '样式',
    `remark`      varchar(255) DEFAULT NULL COMMENT '备注',
    `tenant_id`   bigint NOT NULL DEFAULT 1 COMMENT '租户编号',
    `creator`     varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`     varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     tinyint NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    KEY `idx_dict_type` (`dict_type`)
) ENGINE = InnoDB COMMENT = '字典数据表';

-- 14. 通知公告
DROP TABLE IF EXISTS `system_notice`;
CREATE TABLE `system_notice` (
    `id`          bigint NOT NULL AUTO_INCREMENT COMMENT '通知公告编号',
    `title`       varchar(128) NOT NULL COMMENT '标题',
    `content`     text NOT NULL COMMENT '内容',
    `type`        tinyint NOT NULL COMMENT '类型（1 通知 2 公告）',
    `status`      tinyint NOT NULL DEFAULT 1 COMMENT '状态（0 关闭 1 开启）',
    `tenant_id`   bigint NOT NULL DEFAULT 1 COMMENT '租户编号',
    `creator`     varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`     varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     tinyint NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB COMMENT = '通知公告表';

-- 15. 登录日志
DROP TABLE IF EXISTS `system_login_log`;
CREATE TABLE `system_login_log` (
    `id`          bigint NOT NULL AUTO_INCREMENT COMMENT '日志编号',
    `log_type`    tinyint NOT NULL COMMENT '日志类型（1 登录 2 登出）',
    `user_id`     bigint DEFAULT NULL COMMENT '用户编号',
    `user_type`   tinyint NOT NULL DEFAULT 1 COMMENT '用户类型',
    `username`    varchar(32)  NOT NULL COMMENT '用户账号',
    `status`      tinyint NOT NULL DEFAULT 1 COMMENT '状态（0 失败 1 成功）',
    `user_ip`     varchar(64)  DEFAULT NULL COMMENT '用户 IP',
    `user_agent`  varchar(255) DEFAULT NULL COMMENT '浏览器 UserAgent',
    `tenant_id`   bigint NOT NULL DEFAULT 1 COMMENT '租户编号',
    `creator`     varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`     varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     tinyint NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    KEY `idx_username` (`username`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB COMMENT = '登录日志表';

-- 16. 操作日志
DROP TABLE IF EXISTS `system_operate_log`;
CREATE TABLE `system_operate_log` (
    `id`              bigint NOT NULL AUTO_INCREMENT COMMENT '日志编号',
    `user_id`         bigint DEFAULT NULL COMMENT '用户编号',
    `user_type`       tinyint NOT NULL DEFAULT 1 COMMENT '用户类型',
    `module`          varchar(64)  NOT NULL COMMENT '模块名',
    `name`            varchar(64)  NOT NULL COMMENT '操作名',
    `type`            varchar(16)  NOT NULL DEFAULT 'other' COMMENT '操作类型',
    `content`         varchar(255) DEFAULT NULL COMMENT '操作内容',
    `request_method`  varchar(16)  DEFAULT NULL COMMENT '请求方法',
    `request_url`     varchar(255) DEFAULT NULL COMMENT '请求地址',
    `user_ip`         varchar(64)  DEFAULT NULL COMMENT '用户 IP',
    `user_agent`      varchar(255) DEFAULT NULL COMMENT '浏览器 UserAgent',
    `java_method`     varchar(255) DEFAULT NULL COMMENT 'Java 方法',
    `java_method_args` text DEFAULT NULL COMMENT 'Java 方法入参',
    `start_time`      datetime NOT NULL COMMENT '开始时间',
    `duration`        int NOT NULL DEFAULT 0 COMMENT '执行时长（毫秒）',
    `result_code`     int NOT NULL DEFAULT 0 COMMENT '结果码（0 成功 -1 失败）',
    `result_msg`      varchar(255) DEFAULT NULL COMMENT '结果提示',
    `result_data`     text DEFAULT NULL COMMENT '结果数据',
    `tenant_id`       bigint NOT NULL DEFAULT 1 COMMENT '租户编号',
    `creator`         varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`     datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`         varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`     datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         tinyint NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    KEY `idx_module` (`module`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB COMMENT = '操作日志表';

-- ---------------------------------------------------------------------
-- 二、模型网关模块表
--    健康状态为运行时数据，存于 Redis；本库仅持久化配置与审计。
-- ---------------------------------------------------------------------

-- 17. 模型供应商
DROP TABLE IF EXISTS `provider`;
CREATE TABLE `provider`
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(128) NOT NULL COMMENT '供应商名称',
    code        VARCHAR(64)  NOT NULL COMMENT '供应商编码(ProviderType)',
    base_url    VARCHAR(512) NOT NULL COMMENT '下游基础URL',
    auth_type   VARCHAR(32)  NOT NULL DEFAULT 'BEARER' COMMENT '鉴权类型: BEARER/ZHIPU_JWT/QIANFAN_IAM/SPARK_WS',
    credentials TEXT COMMENT '加密后的凭证JSON(apiKey/apiSecret/appId等)',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
    priority    INT          NOT NULL DEFAULT 100 COMMENT '优先级',
    remark      VARCHAR(512) DEFAULT NULL COMMENT '备注',
    version     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_provider_code_name (code, name),
    KEY idx_provider_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='模型供应商';

-- 18. 对外统一逻辑模型
DROP TABLE IF EXISTS `logical_model`;
CREATE TABLE `logical_model`
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(128) NOT NULL COMMENT '对外统一模型名',
    capability  VARCHAR(16)  NOT NULL COMMENT '能力: CHAT/EMBEDDING/RERANK/TTS/STT/IMAGE',
    description VARCHAR(512) DEFAULT NULL,
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
    deleted     TINYINT      NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_logical_model_name (name),
    KEY idx_logical_capability (capability, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='对外统一逻辑模型';

-- 19. 逻辑模型到供应商部署的映射（通道）
DROP TABLE IF EXISTS `model_channel`;
CREATE TABLE `model_channel`
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    logical_model_id BIGINT       NOT NULL COMMENT '逻辑模型ID',
    provider_id      BIGINT       NOT NULL COMMENT '供应商ID',
    upstream_model   VARCHAR(128) NOT NULL COMMENT '下游真实模型名',
    protocol         VARCHAR(32)  NOT NULL DEFAULT 'OPENAI_COMPAT' COMMENT '通道协议: OPENAI_COMPAT/DASHSCOPE_NATIVE/SPARK_WS',
    weight           INT          NOT NULL DEFAULT 100 COMMENT '负载均衡权重',
    priority         INT          NOT NULL DEFAULT 100 COMMENT '优先级(越小越优先)',
    enabled          TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    config_json      JSON         DEFAULT NULL COMMENT '通道级额外配置',
    version          INT          NOT NULL DEFAULT 0,
    deleted          TINYINT      NOT NULL DEFAULT 0,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_channel_logical (logical_model_id, enabled),
    KEY idx_channel_provider (provider_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='逻辑模型到供应商部署的映射(通道)';

-- 20. 模型调用审计
DROP TABLE IF EXISTS `audit_log`;
CREATE TABLE `audit_log`
(
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    trace_id          VARCHAR(64)  DEFAULT NULL COMMENT '链路追踪ID',
    caller_id         VARCHAR(128) DEFAULT NULL COMMENT '调用人标识(JWT sub)',
    caller_name       VARCHAR(128) DEFAULT NULL COMMENT '调用人名称',
    tenant            VARCHAR(128) DEFAULT NULL COMMENT '租户',
    client_ip         VARCHAR(64)  DEFAULT NULL,
    protocol          VARCHAR(16)  DEFAULT NULL COMMENT '上游协议: OPENAI/ANTHROPIC',
    endpoint          VARCHAR(128) DEFAULT NULL COMMENT '请求端点',
    logical_model     VARCHAR(128) DEFAULT NULL,
    channel_id        BIGINT       DEFAULT NULL,
    provider_id       BIGINT       DEFAULT NULL,
    provider_code     VARCHAR(64)  DEFAULT NULL,
    upstream_model    VARCHAR(128) DEFAULT NULL,
    request_body      MEDIUMTEXT   DEFAULT NULL COMMENT '请求体(截断/脱敏)',
    response_body     MEDIUMTEXT   DEFAULT NULL COMMENT '响应体(截断/脱敏)',
    prompt_tokens     INT         NOT NULL DEFAULT 0,
    completion_tokens INT         NOT NULL DEFAULT 0,
    total_tokens      INT         NOT NULL DEFAULT 0,
    latency_ms        BIGINT      NOT NULL DEFAULT 0 COMMENT '总耗时',
    first_token_ms    BIGINT      NOT NULL DEFAULT 0 COMMENT '首字延迟',
    status            VARCHAR(16)  DEFAULT NULL COMMENT 'SUCCESS/FAILURE/BLOCKED',
    error_code        VARCHAR(64)  DEFAULT NULL,
    error_msg         VARCHAR(1024) DEFAULT NULL,
    guardrail_hit     VARCHAR(512) DEFAULT NULL COMMENT '命中的安全围栏',
    moderation_result VARCHAR(512) DEFAULT NULL COMMENT '内容审计结果',
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_audit_created (created_at),
    KEY idx_audit_caller (caller_id, created_at),
    KEY idx_audit_model (logical_model, created_at),
    KEY idx_audit_trace (trace_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='模型调用审计';

-- 21. 安全围栏策略
DROP TABLE IF EXISTS `guardrail_policy`;
CREATE TABLE `guardrail_policy`
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(128) NOT NULL,
    scope       VARCHAR(16)  NOT NULL DEFAULT 'GLOBAL' COMMENT 'GLOBAL/TENANT/MODEL',
    scope_value VARCHAR(128) DEFAULT NULL COMMENT 'scope为TENANT/MODEL时的取值',
    rules_json  JSON         DEFAULT NULL COMMENT '规则集合',
    action      VARCHAR(16)  NOT NULL DEFAULT 'BLOCK' COMMENT 'BLOCK/MASK/WARN',
    enabled     TINYINT      NOT NULL DEFAULT 1,
    priority    INT          NOT NULL DEFAULT 100,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_guardrail_scope (scope, enabled)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='安全围栏策略';

-- 22. 内容审计策略
DROP TABLE IF EXISTS `moderation_policy`;
CREATE TABLE `moderation_policy`
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(128) NOT NULL,
    scope       VARCHAR(16)  NOT NULL DEFAULT 'GLOBAL' COMMENT 'GLOBAL/TENANT/MODEL',
    scope_value VARCHAR(128) DEFAULT NULL,
    provider    VARCHAR(32)  NOT NULL DEFAULT 'builtin' COMMENT 'builtin/aliyun/llm',
    config_json JSON         DEFAULT NULL,
    action      VARCHAR(16)  NOT NULL DEFAULT 'BLOCK' COMMENT 'BLOCK/REVIEW/PASS',
    enabled     TINYINT      NOT NULL DEFAULT 1,
    priority    INT          NOT NULL DEFAULT 100,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_moderation_scope (scope, enabled)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='内容审计策略';

-- 23. 敏感词库
DROP TABLE IF EXISTS `sensitive_word`;
CREATE TABLE `sensitive_word`
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    word       VARCHAR(255) NOT NULL COMMENT '敏感词',
    category   VARCHAR(64)  DEFAULT NULL COMMENT '分类: 涉政/色情/暴恐/广告/辱骂等',
    level      VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM' COMMENT 'LOW/MEDIUM/HIGH',
    action     VARCHAR(16)  NOT NULL DEFAULT 'BLOCK' COMMENT 'BLOCK/MASK/WARN',
    enabled    TINYINT      NOT NULL DEFAULT 1,
    deleted    TINYINT      NOT NULL DEFAULT 0,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_word (word),
    KEY idx_word_enabled (enabled)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='敏感词库';

-- 24. 通道健康事件历史
DROP TABLE IF EXISTS `health_event`;
CREATE TABLE `health_event`
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    channel_id  BIGINT      NOT NULL,
    provider_id BIGINT       DEFAULT NULL,
    event_type  VARCHAR(32) NOT NULL COMMENT 'EJECT/RECOVER/PROBE_FAIL/AUTH_EXPIRED',
    from_status VARCHAR(16)  DEFAULT NULL,
    to_status   VARCHAR(16)  DEFAULT NULL,
    reason      VARCHAR(512) DEFAULT NULL,
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_health_channel (channel_id, created_at),
    KEY idx_health_created (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='通道健康事件历史';

-- 25. 限流配额规则
DROP TABLE IF EXISTS `rate_limit_rule`;
CREATE TABLE `rate_limit_rule`
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    name               VARCHAR(128) NOT NULL COMMENT '规则展示名',
    rule_type          VARCHAR(32)  NOT NULL COMMENT 'CALLER_DEFAULT/CALLER_OVERRIDE/TENANT_OVERRIDE/MODEL_DEFAULT/MODEL_OVERRIDE/QUOTA_DEFAULT/QUOTA_CALLER/QUOTA_TENANT',
    target             VARCHAR(128) DEFAULT NULL COMMENT '限流对象（caller/tenant/model 标识）',
    permits_per_minute INT          NOT NULL DEFAULT 0 COMMENT '每分钟许可；<=0 不限制',
    daily_tokens       BIGINT       DEFAULT NULL COMMENT '每日 Token 上限；仅配额规则有值，<=0 不限',
    enabled            TINYINT      NOT NULL DEFAULT 1,
    deleted            TINYINT      NOT NULL DEFAULT 0,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_rl_type (rule_type),
    KEY idx_rl_target (target)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='限流配额规则';

-- 26. 网关 APIKey（数据面调用凭证）
DROP TABLE IF EXISTS `gateway_api_key`;
CREATE TABLE `gateway_api_key`
(
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id      BIGINT       NOT NULL COMMENT '所属用户ID(system_users.id)',
    user_name    VARCHAR(64)  DEFAULT NULL COMMENT '所属用户名快照(展示用)',
    name         VARCHAR(128) NOT NULL COMMENT 'APIKey 名称',
    api_key_hash CHAR(64)     NOT NULL COMMENT 'APIKey 的 SHA-256 十六进制摘要',
    key_prefix   VARCHAR(32)  NOT NULL COMMENT 'APIKey 前缀(展示用，如 sk-qx-abc12345)',
    tenant       VARCHAR(32)  NOT NULL DEFAULT 'default' COMMENT '归属租户标识',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    expires_at   DATETIME     DEFAULT NULL COMMENT '过期时间，NULL 表示永不过期',
    last_used_at DATETIME     DEFAULT NULL COMMENT '最近一次成功调用时间',
    remark       VARCHAR(512) DEFAULT NULL COMMENT '备注',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_api_key_hash (api_key_hash),
    KEY idx_api_key_owner (user_id, deleted),
    KEY idx_api_key_status (status, deleted)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='网关 APIKey';
