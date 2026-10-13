import type { Component } from 'vue'
import {
  BookOutline, BriefcaseOutline, BusinessOutline, CloudOutline, CubeOutline, DocumentTextOutline,
  GitNetworkOutline, HomeOutline, KeyOutline, ListOutline, MegaphoneOutline, PeopleOutline, PulseOutline,
  SettingsOutline, ShieldCheckmarkOutline, SpeedometerOutline, StorefrontOutline, TimeOutline,
} from '@vicons/ionicons5'

/** 菜单名称 → 图标映射（后端返回的 icon 字段为通用字符串，这里按名称映射为 ionicons） */
const ICON_MAP: Record<string, Component> = {
  系统管理: SettingsOutline,
  用户管理: PeopleOutline,
  角色管理: ShieldCheckmarkOutline,
  菜单管理: ListOutline,
  部门管理: GitNetworkOutline,
  岗位管理: BriefcaseOutline,
  字典管理: BookOutline,
  租户管理: BusinessOutline,
  租户套餐: CubeOutline,
  通知公告: MegaphoneOutline,
  日志管理: DocumentTextOutline,
  操作日志: DocumentTextOutline,
  登录日志: TimeOutline,
  // 控制台（原模型网关）
  控制台: CloudOutline,
  数据看板: HomeOutline,
  模型广场: StorefrontOutline,
  供应商管理: BusinessOutline,
  逻辑模型: CubeOutline,
  模型通道: GitNetworkOutline,
  健康监控: PulseOutline,
  调用审计: DocumentTextOutline,
  安全策略: ShieldCheckmarkOutline,
  限流配额: SpeedometerOutline,
  '令牌管理': KeyOutline,
}

export function mapMenuIcon(name: string): Component {
  return ICON_MAP[name] || ListOutline
}
