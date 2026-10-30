<script setup>
import { computed, inject, ref, watch } from 'vue'
import { formatBytes, formatDateTime } from '../../lib/format.js'
import { levelMeta } from '../../lib/admin.js'

const props = defineProps({
  items: { type: Array, default: () => [] },
  query: { type: String, default: '' }
})

const emit = defineEmits(['changed'])
const api = inject('adminApi')
const toast = inject('adminToast')
const clearSearch = inject('adminClearSearch')

const page = ref(1)
const pageSize = 12
const deleteTarget = ref(null)
const deleting = ref(false)
const deleteError = ref('')

const filtered = computed(() => {
  const q = props.query.trim().toLowerCase()
  if (!q) return props.items || []
  return (props.items || []).filter((item) =>
    [item.id, item.name, item.analysisId, item.headline].join(' ').toLowerCase().includes(q)
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

function link(id) {
  return `${window.location.origin}${window.location.pathname}#/s/${id}`
}

function openReport(item) {
  window.open(link(item.id), '_blank', 'noopener')
}

async function copyLink(item) {
  const url = link(item.id)
  try {
    await navigator.clipboard.writeText(url)
    toast('分享链接已复制')
    return
  } catch {
    /* 局域网 HTTP 下 Clipboard API 可能不可用 */
  }

  const ta = document.createElement('textarea')
  ta.value = url
  ta.style.cssText = 'position:fixed;opacity:0;pointer-events:none'
  document.body.appendChild(ta)
  ta.select()
  let ok = false
  try {
    ok = document.execCommand('copy')
  } catch {
    ok = false
  }
  document.body.removeChild(ta)
  toast(ok ? '分享链接已复制' : '复制失败，请点「打开」后复制地址栏')
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleting.value = true
  deleteError.value = ''
  try {
    await api('/reports/' + encodeURIComponent(deleteTarget.value.id), { method: 'DELETE' })
    toast('已删除分享报告 ' + deleteTarget.value.id)
    deleteTarget.value = null
    emit('changed')
  } catch (err) {
    deleteError.value = err.message
  } finally {
    deleting.value = false
  }
}

function level(item) {
  const matched = Number(item?.matched || 0)
  const total = Number(item?.total || 0)
  if (total > 0 && matched === 0) return levelMeta('unknown')
  if (total >= 2 && matched / total < 0.5 && item.level === 'good') return levelMeta('insufficient')
  return levelMeta(item.level)
}
</script>

<template>
  <section>
    <header class="ac-page-head">
      <div>
        <p class="ac-kicker">SHARED REPORTS</p>
        <h1 class="ac-page-title">分享报告</h1>
        <p class="ac-page-sub">前台生成的短链都保存在本机 data/reports/，30 天后自动清理；这里可以打开、复制或删除。</p>
      </div>
      <div class="ac-actions">
        <button type="button" class="ac-btn" @click="emit('changed')">刷新</button>
      </div>
    </header>

    <p class="ac-alert">
      分享链接使用当前访问地址：同一台设备可直接打开；跨设备请先用局域网 IP 访问前台再分享。链接包含档案信息，删除后立即失效。
    </p>

    <section class="ac-card" style="margin-top: 12px">
      <div class="ac-card-pad reports-toolbar">
        <div>
          <b>{{ items.length }}</b> 份分享
          <span v-if="props.query" class="ac-badge neutral">筛选：{{ props.query }}</span>
        </div>
        <button v-if="props.query" type="button" class="ac-btn sm ghost" @click="clearSearch">清除筛选</button>
      </div>

      <div v-if="paged.length" class="ac-table-wrap">
        <table class="ac-table">
          <thead>
            <tr>
              <th>编号</th>
              <th>产品</th>
              <th>结论</th>
              <th>分享时间</th>
              <th>大小</th>
              <th class="right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in paged" :key="item.id">
              <td>
                <div class="ac-mono">{{ item.id }}</div>
                <div class="report-analysis ac-mono">{{ item.analysisId || '—' }}</div>
              </td>
              <td>
                <div class="report-name">{{ item.name || '未命名产品' }}</div>
                <div class="report-headline">{{ item.headline || '—' }}</div>
              </td>
              <td>
                <span class="ac-badge" :class="level(item).cls">{{ level(item).label }}</span>
              </td>
              <td class="ac-muted">{{ formatDateTime(item.sharedAt) || '—' }}</td>
              <td class="ac-muted">{{ formatBytes(item.size) }}</td>
              <td class="right">
                <button type="button" class="ac-icon-btn" @click="openReport(item)">打开</button>
                <button type="button" class="ac-icon-btn" @click="copyLink(item)">复制</button>
                <button type="button" class="ac-icon-btn danger" @click="deleteTarget = item">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-else class="ac-empty">
        <b>{{ items.length ? '没有匹配的分享报告' : '还没有分享报告' }}</b>
        <p>{{ items.length ? '换个编号或产品名，或清除筛选。' : '回前台解读一瓶，点报告页的「分享」生成短链后，这里会出现记录。' }}</p>
        <div class="ac-actions" style="margin-top: 4px">
          <button v-if="props.query" type="button" class="ac-btn sm" @click="clearSearch">清除筛选</button>
          <a v-else class="ac-btn sm" href="#/">去前台</a>
        </div>
      </div>

      <div class="ac-pagination">
        <span>显示 {{ rangeStart }}–{{ rangeEnd }} / 共 {{ filtered.length }} 条</span>
        <div class="pages">
          <button type="button" :disabled="page <= 1" @click="page--">上一页</button>
          <button type="button" disabled>{{ page }} / {{ pageCount }}</button>
          <button type="button" :disabled="page >= pageCount" @click="page++">下一页</button>
        </div>
      </div>
    </section>

    <div v-if="deleteTarget" class="ac-modal-mask" @click.self="deleteTarget = null">
      <div class="ac-modal" role="dialog" aria-modal="true">
        <div class="ac-modal-head">
          <div>
            <p class="ac-kicker">DELETE</p>
            <h2>删除分享报告？</h2>
          </div>
          <button type="button" class="ac-x" aria-label="关闭" @click="deleteTarget = null">×</button>
        </div>
        <div class="ac-modal-body">
          <p class="ac-modal-note">
            将删除 <span class="ac-mono">{{ deleteTarget.id }}</span>（{{ deleteTarget.name || '未命名产品' }}）。
            已发出去的链接会立即打不开，无法恢复。
          </p>
          <p v-if="deleteError" class="ac-alert error">{{ deleteError }}</p>
        </div>
        <div class="ac-modal-actions">
          <button type="button" class="ac-btn" :disabled="deleting" @click="deleteTarget = null">取消</button>
          <button type="button" class="ac-btn danger" :disabled="deleting" @click="confirmDelete">
            <i v-if="deleting" class="ac-spinner"></i>
            {{ deleting ? '删除中…' : '确认删除' }}
          </button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.reports-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  padding: 11px 14px;
  border-bottom: 1px solid var(--ac-line-soft);
  font-size: 11.5px;
  color: var(--ac-muted);
}

.reports-toolbar b { color: var(--ink); font-size: 15px; }

.report-analysis { margin-top: 1px; color: var(--ac-muted-2); font-size: 9.5px; }

.report-name { font-size: 12px; font-weight: 500; }

.report-headline {
  max-width: 280px;
  overflow: hidden;
  margin-top: 1px;
  color: var(--ac-muted-2);
  font-size: 9.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 720px) {
  .ac-table { min-width: 720px; }
}
</style>
