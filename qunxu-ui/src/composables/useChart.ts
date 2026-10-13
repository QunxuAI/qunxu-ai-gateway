import { onBeforeUnmount, onMounted, shallowRef, type Ref } from 'vue'
import * as echarts from 'echarts'

/**
 * ECharts 封装：负责实例创建、resize 监听与销毁。
 * 用法：const { setOption } = useChart(elRef)；数据变化时调用 setOption(option)。
 */
export function useChart(el: Ref<HTMLElement | null>) {
  const chart = shallowRef<echarts.ECharts | null>(null)
  let observer: ResizeObserver | null = null

  function resize() {
    chart.value?.resize()
  }

  function setOption(option: echarts.EChartsOption) {
    chart.value?.setOption(option, true)
  }

  onMounted(() => {
    if (el.value) {
      chart.value = echarts.init(el.value)
    }
    if (typeof ResizeObserver !== 'undefined') {
      observer = new ResizeObserver(resize)
      if (el.value) observer.observe(el.value)
    }
    window.addEventListener('resize', resize)
  })

  onBeforeUnmount(() => {
    observer?.disconnect()
    window.removeEventListener('resize', resize)
    chart.value?.dispose()
    chart.value = null
  })

  return { chart, setOption, resize }
}
