<template>
  <div class="security-page">
    <n-tabs type="line" animated>
      <n-tab-pane name="guardrail" tab="安全围栏">
        <GuardrailSection />
      </n-tab-pane>
      <n-tab-pane name="moderation" tab="内容审计">
        <ModerationSection />
      </n-tab-pane>
      <n-tab-pane name="sensitive" tab="敏感词">
        <SensitiveWordSection />
      </n-tab-pane>
    </n-tabs>
  </div>
</template>

<script setup lang="ts">
import { NTabs, NTabPane, useThemeVars } from 'naive-ui'
import GuardrailSection from './GuardrailSection.vue'
import ModerationSection from './ModerationSection.vue'
import SensitiveWordSection from './SensitiveWordSection.vue'

const themeVars = useThemeVars()
</script>

<!--
  三个 Tab 共用的 CRUD 样式（与 system/user 模板一致）。
  使用 .security-page 前缀作为作用域，避免泄漏到其它页面。
-->
<style>
.security-page {
  max-width: 1240px;
  margin: 0 auto;
}

/* KPI 响应式栅格：自动适配列数 */
.security-page .kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 14px;
  margin-bottom: 16px;
}
.security-page .kpi-card {
  background: v-bind('themeVars.cardColor');
  border: 1px solid rgba(128, 138, 158, 0.18);
  border-radius: 14px;
  padding: 16px 18px;
  display: flex;
  align-items: center;
  gap: 14px;
  transition: box-shadow 0.2s, transform 0.2s;
}
.security-page .kpi-card:hover {
  box-shadow: 0 6px 20px rgba(16, 24, 40, 0.08);
  transform: translateY(-1px);
}
.security-page .kpi-icon {
  width: 44px;
  height: 44px;
  border-radius: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.security-page .kpi-label {
  font-size: 12px;
  color: #98a2b3;
  font-weight: 500;
}
.security-page .kpi-num {
  font-size: 23px;
  font-weight: 700;
  line-height: 1.25;
  font-variant-numeric: tabular-nums;
}
.security-page .kpi-num small {
  font-size: 13px;
  font-weight: 600;
  margin-left: 2px;
}

.security-page .card-block {
  margin-bottom: 16px;
  border-radius: 14px;
}
.security-page .search-actions {
  display: flex;
  gap: 10px;
  margin-top: 6px;
}
.security-page .table-header {
  display: flex;
  align-items: center;
  gap: 10px;
}
.security-page .table-title {
  font-size: 15px;
  font-weight: 700;
}
.security-page .pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
.security-page .batch-alert {
  margin-bottom: 12px;
}

/* 小屏适配 */
@media (max-width: 768px) {
  .security-page .kpi-grid {
    grid-template-columns: 1fr 1fr;
    gap: 10px;
  }
  .security-page .kpi-card {
    padding: 12px;
  }
  .security-page .pagination-wrap {
    justify-content: center;
  }
}
</style>
