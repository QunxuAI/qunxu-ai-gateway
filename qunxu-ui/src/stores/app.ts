import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { darkTheme, type GlobalTheme } from 'naive-ui'

/**
 * 应用级状态：侧边栏折叠 + 深浅主题。
 * 主题与折叠状态持久化到 localStorage，刷新后保持。
 */
export const useAppStore = defineStore('app', () => {
  const collapsed = ref(localStorage.getItem('qunxu.sider.collapsed') === '1')
  const themeMode = ref<'light' | 'dark'>(
    (localStorage.getItem('qunxu.theme') as 'light' | 'dark') || 'light',
  )

  // Naive UI 主题：null 表示浅色，darkTheme 表示深色
  const theme = computed<GlobalTheme | null>(() =>
    themeMode.value === 'dark' ? darkTheme : null,
  )

  function toggleCollapsed() {
    collapsed.value = !collapsed.value
    localStorage.setItem('qunxu.sider.collapsed', collapsed.value ? '1' : '0')
  }

  function toggleTheme() {
    themeMode.value = themeMode.value === 'light' ? 'dark' : 'light'
    localStorage.setItem('qunxu.theme', themeMode.value)
  }

  return { collapsed, themeMode, theme, toggleCollapsed, toggleTheme }
})
