<script setup>
import { onBeforeUnmount, ref } from 'vue'
import { formatDateTime } from '../lib/format.js'
import { profileLabel, VERDICT_LABEL } from '../lib/text.js'

defineProps({
  entries: { type: Array, default: () => [] },
  version: { type: Object, default: () => ({}) }
})

const emit = defineEmits(['open', 'remove', 'clear'])

const confirming = ref(false)
let timer = 0

function askClear() {
  if (!confirming.value) {
    confirming.value = true
    clearTimeout(timer)
    timer = setTimeout(() => {
      confirming.value = false
    }, 3000)
    return
  }
  confirming.value = false
  emit('clear')
}

const LEVEL_CLASS = {
  avoid: 'lv-avoid',
  caution: 'lv-caution',
  good: 'lv-good',
  insufficient: 'lv-insufficient',
  unknown: 'lv-unknown'
}

function levelClass(level) {
  return LEVEL_CLASS[level] || 'lv-good'
}

function levelLabel(level) {
  return VERDICT_LABEL[level] || (level === 'insufficient' ? '信息不足' : level === 'unknown' ? '未能解读' : '')
}

onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <article class="page-view">
    <header class="page-head">
      <div>
        <p class="eyebrow">历史报告</p>
        <h1 class="page-title serif">你解读过的瓶子</h1>
        <p class="lead">
          解读过的瓶子会按时间倒序留在这里，只保存在这台浏览器。刷新、关掉页面都不会丢；换设备或清空浏览器数据后会消失。
        </p>
      </div>
      <div class="head-actions">
        <button
          v-if="entries.length"
          type="button"
          class="btn"
          :class="{ danger: confirming }"
          @click="askClear"
        >
          {{ confirming ? '再点一次确认清空' : '清空历史' }}
        </button>
        <a class="btn primary" href="#/">开始解读</a>
      </div>
    </header>

    <section v-if="!entries.length" class="empty card">
      <div class="empty-mark serif">00</div>
      <h2 class="h2 serif">还没有报告</h2>
      <p>贴一份成分表，或从示例配方开始。解读完成后会自动存到这里。</p>
      <a class="btn primary" href="#/">去解读第一瓶</a>
    </section>

    <div v-else class="list">
      <article v-for="entry in entries" :key="entry.id" class="entry card">
        <div class="entry-main">
          <div class="entry-top">
            <span class="badge" :class="levelClass(entry.level)">
              {{ levelLabel(entry.level) }}
            </span>
            <h2 class="product">{{ entry.productName || '未命名产品' }}</h2>
            <span class="time">{{ formatDateTime(entry.createdAt) }}</span>
          </div>
          <p class="headline">{{ entry.headline || '—' }}</p>
          <div class="entry-meta">
            <span>{{ profileLabel(entry.profile) }}</span>
            <span>识别 {{ entry.matched || 0 }} / {{ entry.total || 0 }} 个成分</span>
            <span class="id">{{ entry.id }}</span>
          </div>
        </div>
        <div class="entry-actions">
          <button type="button" class="btn" @click="emit('open', entry)">查看报告</button>
          <button type="button" class="btn ghost" @click="emit('remove', entry.id)">删除</button>
        </div>
      </article>
    </div>

    <p class="tiny">
      历史记录包含你填写的肤质、敏感史与孕期状态，只存在本机浏览器；管理控制台不会读取它。
      规则库当前版本 {{ version.rulesVersion || '2026.10' }}。
    </p>
  </article>
</template>

<style scoped>
.page-view { max-width: 860px; margin: 0 auto; }

.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 20px;
}

.page-title { margin: 8px 0 8px; font-size: 32px; font-weight: 600; }

.lead { max-width: 560px; font-size: 13px; line-height: 1.9; color: var(--ink2); }

.head-actions { display: flex; gap: 6px; flex-wrap: wrap; }

.btn.danger { border-color: var(--danger-line); color: var(--danger); background: var(--danger-soft); }

.empty { padding: 42px 24px; text-align: center; }

.empty-mark { font-size: 52px; line-height: 1; color: var(--accent); opacity: .16; }

.empty .h2 { margin: 8px 0; }

.empty p { margin-bottom: 18px; font-size: 13px; color: var(--ink3); }

.empty .btn { text-decoration: none; }

.list { display: flex; flex-direction: column; gap: 10px; }

.entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 18px 20px;
  transition: border-color .16s, box-shadow .16s;
}

.entry:hover { border-color: var(--accent-line); box-shadow: var(--shadow-sm); }

.entry-main { min-width: 0; flex: 1; }

.entry-top { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; }

.badge {
  flex-shrink: 0;
  padding: 2px 8px;
  border-radius: 5px;
  background: #F3F0E8;
  font-size: 11px;
  font-weight: 600;
}

.badge.lv-avoid { background: var(--danger-soft); color: var(--danger); }
.badge.lv-caution { background: var(--warn-soft); color: var(--warn); }
.badge.lv-good { background: var(--good-soft); color: var(--good); }
.badge.lv-insufficient { background: var(--warn-soft); color: var(--warn); }
.badge.lv-unknown { background: #F3F0E8; color: var(--ink2); }

.product { font-size: 15px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.time { margin-left: auto; font-size: 11px; color: var(--ink3); }

.headline { margin-top: 4px; font-size: 13px; line-height: 1.7; color: var(--ink2); }

.entry-meta { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 5px; font-size: 11px; color: var(--ink3); }

.entry-meta .id { font-variant-numeric: tabular-nums; opacity: .8; }

.entry-actions { display: flex; flex-shrink: 0; gap: 4px; }

.tiny { margin-top: 14px; font-size: 11px; line-height: 1.8; color: var(--ink3); }

@media (max-width: 720px) {
  .page-title { font-size: 26px; }
  .entry { flex-direction: column; align-items: stretch; gap: 10px; padding: 16px; }
  .entry-actions { justify-content: flex-end; }
  .time { margin-left: 0; width: 100%; }
}
</style>
