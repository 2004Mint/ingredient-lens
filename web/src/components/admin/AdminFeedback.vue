<script setup>
import { computed, inject, ref, watch } from 'vue'
import { formatDateTime } from '../../lib/format.js'

const props = defineProps({
  items: { type: Array, default: () => [] },
  query: { type: String, default: '' }
})

const clearSearch = inject('adminClearSearch')
const page = ref(1)
const pageSize = 12

const filtered = computed(() => {
  const q = props.query.trim().toLowerCase()
  if (!q) return props.items || []
  return (props.items || []).filter((item) =>
    [item.message, item.contact, item.page].join(' ').toLowerCase().includes(q)
  )
})

const pageCount = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize)))
const paged = computed(() => filtered.value.slice((page.value - 1) * pageSize, page.value * pageSize))
const rangeStart = computed(() => (filtered.value.length ? (page.value - 1) * pageSize + 1 : 0))
const rangeEnd = computed(() => Math.min(page.value * pageSize, filtered.value.length))

watch(() => props.query, () => (page.value = 1))
watch(pageCount, (count) => {
  if (page.value > count) page.value = count
})
</script>

<template>
  <section>
    <header class="ac-page-head">
      <div>
        <p class="ac-kicker">INBOX</p>
        <h1 class="ac-page-title">用户反馈</h1>
        <p class="ac-page-sub">前台「关于」页提交的反馈，按收到时间倒序。只存在本机 data/feedback.jsonl，不接第三方工单。</p>
      </div>
      <div class="ac-actions">
        <span class="ac-badge neutral">共 {{ items.length }} 条</span>
        <button v-if="props.query" type="button" class="ac-btn sm ghost" @click="clearSearch">清除筛选</button>
      </div>
    </header>

    <div v-if="paged.length" class="feedback-list">
      <article v-for="item in paged" :key="item.receivedAt + item.message" class="ac-card feedback-card">
        <div class="fb-side">
          <span class="ac-badge neutral">{{ item.page || '—' }}</span>
          <time>{{ formatDateTime(item.receivedAt) || '—' }}</time>
        </div>
        <div class="fb-main">
          <p class="fb-message">{{ item.message }}</p>
          <p class="fb-contact">
            <span v-if="item.contact">联系方式：{{ item.contact }}</span>
            <span v-else class="ac-muted">未留联系方式</span>
          </p>
        </div>
      </article>
    </div>

    <div v-else class="ac-card ac-empty">
      <b>{{ items.length ? '没有匹配的反馈' : '还没有收到反馈' }}</b>
      <p>{{ items.length ? '换个关键词，或清除顶部搜索。' : '把前台「关于」页发给内测用户，反馈会出现在这里。' }}</p>
      <div class="ac-actions" style="margin-top: 4px">
        <button v-if="props.query" type="button" class="ac-btn sm" @click="clearSearch">清除筛选</button>
        <a v-else class="ac-btn sm" href="#/">打开前台</a>
      </div>
    </div>

    <div v-if="filtered.length" class="ac-pagination" style="border: none; padding-left: 2px">
      <span>显示 {{ rangeStart }}–{{ rangeEnd }} / 共 {{ filtered.length }} 条</span>
      <div class="pages">
        <button type="button" :disabled="page <= 1" @click="page--">上一页</button>
        <button type="button" disabled>{{ page }} / {{ pageCount }}</button>
        <button type="button" :disabled="page >= pageCount" @click="page++">下一页</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.feedback-list { display: flex; flex-direction: column; gap: 9px; }

.feedback-card {
  display: flex;
  gap: 16px;
  padding: 14px 16px;
}

.fb-side {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 5px;
  width: 118px;
  flex-shrink: 0;
}

.fb-side time { color: var(--ac-muted-2); font-size: 9.5px; line-height: 1.5; }

.fb-main { flex: 1; min-width: 0; }

.fb-message {
  color: var(--ink2);
  font-size: 12.5px;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}

.fb-contact { margin-top: 6px; color: var(--ac-muted-2); font-size: 10px; }

@media (max-width: 640px) {
  .feedback-card { flex-direction: column; gap: 8px; }
  .fb-side { flex-direction: row; align-items: center; width: auto; }
}
</style>
