import type { Directive, DirectiveBinding } from 'vue'
import { useUserStore } from '@/stores/user'

/**
 * 按钮级权限指令：
 * <n-button v-permission="'system:user:create'">新增</n-button>
 * <n-button v-permission="['system:user:create', 'system:user:update']">…
 * 无权限时移除该元素。
 */
export const permission: Directive<HTMLElement, string | string[] | undefined> = {
  mounted(el: HTMLElement, binding: DirectiveBinding<string | string[] | undefined>) {
    const user = useUserStore()
    if (!user.hasPerm(binding.value)) {
      el.parentNode?.removeChild(el)
    }
  },
}
