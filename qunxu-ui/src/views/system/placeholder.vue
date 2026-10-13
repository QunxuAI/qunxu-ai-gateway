<template>
  <n-card class="placeholder-card">
    <n-empty :description="`「${title}」页面建设中，敬请期待`">
      <template #icon>
        <n-icon :size="48"><HammerOutline /></n-icon>
      </template>
    </n-empty>
  </n-card>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { NCard, NEmpty, NIcon } from 'naive-ui'
import { HammerOutline } from '@vicons/ionicons5'

const route = useRoute()
const title = computed(() => (route.meta.title as string) || '')
</script>

<style scoped>
.placeholder-card {
  min-height: 60vh;
}
</style>
