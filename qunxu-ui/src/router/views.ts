/**
 * 页面组件加载器：通过后端返回的 component 字符串（如 system/user/index）
 * 懒加载对应的视图文件。路径约定：@/views/{component}.vue
 */
const viewModules = import.meta.glob('../views/**/*.vue')

export function resolveView(component: string): (() => Promise<unknown>) | undefined {
  const key = `../views/${component}.vue`
  return viewModules[key] as (() => Promise<unknown>) | undefined
}
