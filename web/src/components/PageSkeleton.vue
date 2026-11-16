<script setup>
/**
 * 路由级异步组件的占位骨架。
 * 只在首次进入某个页面、对应 chunk 还在下载时出现，避免白屏。
 */
defineProps({
  label: { type: String, default: '正在加载…' }
})
</script>

<template>
  <div class="page-state card" role="status" aria-live="polite">
    <i class="spinner" aria-hidden="true"></i>
    <span>{{ label }}</span>
  </div>
</template>

<style scoped>
.page-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  min-height: 180px;
  color: var(--ink3);
  font-size: 13.5px;
}
</style>
