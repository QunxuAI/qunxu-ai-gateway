package tech.qunxu.module.system.enums;

import tech.qunxu.framework.common.exception.BusinessErrorCode;
import tech.qunxu.framework.common.exception.ErrorCode;

/**
 * system 模块错误码常量。
 * <p>
 * 业务错误码统一从 1_000_000_000 开始分配，按子模块分段：
 * <ul>
 *     <li>1_001_xxx_xxx：用户</li>
 *     <li>1_002_xxx_xxx：部门</li>
 *     <li>1_003_xxx_xxx：岗位</li>
 *     <li>1_004_xxx_xxx：角色</li>
 *     <li>1_005_xxx_xxx：菜单</li>
 *     <li>1_006_xxx_xxx：权限</li>
 *     <li>1_007_xxx_xxx：认证</li>
 *     <li>1_008_xxx_xxx：租户</li>
 *     <li>1_009_xxx_xxx：字典</li>
 *     <li>1_010_xxx_xxx：通知公告</li>
 *     <li>1_011_xxx_xxx：OAuth2</li>
 * </ul>
 */
public interface ErrorCodeConstants {

    // ========== 用户 1_001_000_000 ==========
    ErrorCode USER_NOT_EXISTS = new BusinessErrorCode(1_001_000_000, "用户不存在");
    ErrorCode USER_USERNAME_EXISTS = new BusinessErrorCode(1_001_000_001, "用户账号已存在");
    ErrorCode USER_MOBILE_EXISTS = new BusinessErrorCode(1_001_000_002, "手机号已被使用");
    ErrorCode USER_PASSWORD_ERROR = new BusinessErrorCode(1_001_000_003, "密码不正确");
    ErrorCode USER_STATUS_DISABLE = new BusinessErrorCode(1_001_000_004, "账号已禁用，请联系管理员");
    ErrorCode USER_DELETE_SELF_ERROR = new BusinessErrorCode(1_001_000_005, "不能删除自己");
    ErrorCode USER_OLD_PASSWORD_ERROR = new BusinessErrorCode(1_001_000_006, "旧密码不正确");

    // ========== 部门 1_002_000_000 ==========
    ErrorCode DEPT_NOT_EXISTS = new BusinessErrorCode(1_002_000_000, "部门不存在");
    ErrorCode DEPT_NAME_DUPLICATE = new BusinessErrorCode(1_002_000_001, "部门名已存在");
    ErrorCode DEPT_PARENT_ERROR = new BusinessErrorCode(1_002_000_002, "不能设置自己为父部门");
    ErrorCode DEPT_EXISTS_CHILDREN = new BusinessErrorCode(1_002_000_003, "存在子部门，无法删除");
    ErrorCode DEPT_EXISTS_USER = new BusinessErrorCode(1_002_000_004, "部门下存在用户，无法删除");

    // ========== 岗位 1_003_000_000 ==========
    ErrorCode POST_NOT_EXISTS = new BusinessErrorCode(1_003_000_000, "岗位不存在");
    ErrorCode POST_CODE_EXISTS = new BusinessErrorCode(1_003_000_001, "岗位编码已存在");

    // ========== 角色 1_004_000_000 ==========
    ErrorCode ROLE_NOT_EXISTS = new BusinessErrorCode(1_004_000_000, "角色不存在");
    ErrorCode ROLE_CODE_EXISTS = new BusinessErrorCode(1_004_000_001, "角色编码已存在");
    ErrorCode ROLE_NAME_EXISTS = new BusinessErrorCode(1_004_000_002, "角色名已存在");
    ErrorCode ROLE_SYSTEM_CANNOT_DELETE = new BusinessErrorCode(1_004_000_003, "系统内置角色不能删除");
    ErrorCode ROLE_SYSTEM_CANNOT_UPDATE = new BusinessErrorCode(1_004_000_004, "系统内置角色不能修改");

    // ========== 菜单 1_005_000_000 ==========
    ErrorCode MENU_NOT_EXISTS = new BusinessErrorCode(1_005_000_000, "菜单不存在");
    ErrorCode MENU_NAME_DUPLICATE = new BusinessErrorCode(1_005_000_001, "同级菜单名已存在");
    ErrorCode MENU_PARENT_ERROR = new BusinessErrorCode(1_005_000_002, "不能设置自己为父菜单");
    ErrorCode MENU_PARENT_NOT_EXISTS = new BusinessErrorCode(1_005_000_003, "父菜单不存在");
    ErrorCode MENU_EXISTS_CHILDREN = new BusinessErrorCode(1_005_000_004, "存在子菜单，无法删除");

    // ========== 权限 1_006_000_000 ==========
    ErrorCode PERMISSION_USER_ROLE_EXISTS = new BusinessErrorCode(1_006_000_000, "用户已分配该角色");
    ErrorCode PERMISSION_ROLE_MENU_EXISTS = new BusinessErrorCode(1_006_000_001, "角色已分配该菜单");

    // ========== 认证 1_007_000_000 ==========
    ErrorCode AUTH_LOGIN_BAD_CREDENTIALS = new BusinessErrorCode(1_007_000_000, "登录失败，账号密码不正确");
    ErrorCode AUTH_LOGIN_FAILED = new BusinessErrorCode(1_007_000_001, "登录失败");
    ErrorCode AUTH_CAPTCHA_NOT_FOUND = new BusinessErrorCode(1_007_000_002, "验证码不存在或已过期");
    ErrorCode AUTH_CAPTCHA_ERROR = new BusinessErrorCode(1_007_000_003, "验证码不正确");
    ErrorCode AUTH_TOKEN_EXPIRED = new BusinessErrorCode(1_007_000_004, "刷新令牌已过期");

    // ========== 租户 1_008_000_000 ==========
    ErrorCode TENANT_NOT_EXISTS = new BusinessErrorCode(1_008_000_000, "租户不存在");
    ErrorCode TENANT_NAME_EXISTS = new BusinessErrorCode(1_008_000_001, "租户名已存在");
    ErrorCode TENANT_STATUS_DISABLE = new BusinessErrorCode(1_008_000_002, "租户已禁用");
    ErrorCode TENANT_EXPIRE = new BusinessErrorCode(1_008_000_003, "租户已过期");
    ErrorCode TENANT_SYSTEM_CANNOT_DELETE = new BusinessErrorCode(1_008_000_004, "系统内置租户不能删除");
    ErrorCode TENANT_PACKAGE_NOT_EXISTS = new BusinessErrorCode(1_008_000_005, "租户套餐不存在");
    ErrorCode TENANT_PACKAGE_NAME_EXISTS = new BusinessErrorCode(1_008_000_006, "租户套餐名已存在");
    ErrorCode TENANT_PACKAGE_HAS_TENANT = new BusinessErrorCode(1_008_000_007, "租户套餐下存在租户，无法删除");

    // ========== 字典 1_009_000_000 ==========
    ErrorCode DICT_TYPE_NOT_EXISTS = new BusinessErrorCode(1_009_000_000, "字典类型不存在");
    ErrorCode DICT_TYPE_EXISTS = new BusinessErrorCode(1_009_000_001, "字典类型已存在");
    ErrorCode DICT_TYPE_HAS_DATA = new BusinessErrorCode(1_009_000_002, "字典类型下存在数据，无法删除");
    ErrorCode DICT_DATA_NOT_EXISTS = new BusinessErrorCode(1_009_000_003, "字典数据不存在");
    ErrorCode DICT_DATA_EXISTS = new BusinessErrorCode(1_009_000_004, "字典数据已存在");

    // ========== 通知公告 1_010_000_000 ==========
    ErrorCode NOTICE_NOT_EXISTS = new BusinessErrorCode(1_010_000_000, "通知公告不存在");

    // ========== OAuth2 1_011_000_000 ==========
    ErrorCode OAUTH2_CLIENT_NOT_EXISTS = new BusinessErrorCode(1_011_000_000, "OAuth2 客户端不存在");
    ErrorCode OAUTH2_CLIENT_SECRET_ERROR = new BusinessErrorCode(1_011_000_001, "OAuth2 客户端密钥不正确");

}
