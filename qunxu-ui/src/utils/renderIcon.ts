import { h, type Component } from 'vue'
import { NIcon } from 'naive-ui'

/**
 * 将图标组件转换为 Naive UI 菜单 / 表格等可用的渲染函数。
 * 用法：renderIcon(PeopleOutline)
 */
export function renderIcon(icon: Component) {
  return () => h(NIcon, null, { default: () => h(icon) })
}
