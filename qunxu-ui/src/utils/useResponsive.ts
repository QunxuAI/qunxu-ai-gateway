import { onBeforeUnmount, onMounted, readonly, ref } from 'vue'

/**
 * 响应式断点组合式函数。
 * - isMobile：小于 768px，侧边栏以抽屉呈现
 * - isTablet：小于 1024px，侧边栏自动折叠为图标模式
 * 通过监听 resize 实时更新，组件卸载时自动移除监听。
 */
export function useResponsive() {
  const MOBILE = 768
  const TABLET = 1024

  const isMobile = ref(false)
  const isTablet = ref(false)
  const width = ref(0)

  const update = () => {
    width.value = window.innerWidth
    isMobile.value = window.innerWidth < MOBILE
    isTablet.value = window.innerWidth < TABLET
  }

  onMounted(() => {
    update()
    window.addEventListener('resize', update)
  })
  onBeforeUnmount(() => window.removeEventListener('resize', update))

  return { isMobile, isTablet, width: readonly(width) }
}
