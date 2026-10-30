<script setup>
import { computed, inject, onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({
  researchEnabled: { type: Boolean, default: false },
  query: { type: String, default: '' }
})
const emit = defineEmits(['changed'])

const api = inject('adminApi')
const toast = inject('adminToast')

const items = ref([])
const counts = ref({ queued: 0, researching: 0, failed: 0, done: 0, active: 0, total: 0 })
const researchOn = ref(props.researchEnabled)
const status = ref('active')
const loading = ref(false)
const error = ref('')
let timer = 0
let loadingGuard = false

const STATUS = {
  queued: { label: '待研究', cls: 'warn' },
  researching: { label: '研究中', cls: 'override' },
  failed: { label: '失败', cls: 'danger' },
  done: { label: '已完成', cls: 'good' }
}

const tabs = computed(() => [
  { id: 'active', label: '未完成', n: counts.value.active },
  { id: 'queued', label: '待研究', n: counts.value.queued },
  { id: 'researching', label: '研究中', n: counts.value.researching },
  { id: 'failed', label: '失败', n: counts.value.failed },
  { id: 'done', label: '已完成', n: counts.value.done },
  { id: 'all', label: '全部', n: counts.value.total }
])

const filtered = computed(() => {
  const q = props.query.trim().toLowerCase()
  return items.value.filter((item) => {
    if (status.value !== 'all') {
      if (status.value === 'active') {
        if (item.status === 'done') return false
      } else if (item.status !== status.value) return false
    }
    if (!q) return true
    return [item.query, item.context, item.error, item.resultName, item.id]
      .join(' ')
      .toLowerCase()
      .includes(q)
  })
})

async function load() {
  if (loadingGuard) return
  loadingGuard = true
  loading.value = true
  const previousActive = counts.value.active
  try {
    const data = await api('/research/tasks?limit=200')
    items.value = Array.isArray(data.items) ? data.items : []
    counts.value = data.counts || counts.value
    researchOn.value = data.researchEnabled
    error.value = ''
    if (previousActive !== counts.value.active) emit('changed')
  } catch (err) {
    error.value = err.message || '研究任务加载失败'
  } finally {
    loading.value = false
    loadingGuard = false
  }
}

async function retry(item) {
  await retryIds([item.id], `已将「${item.query}」重新排队`)
}

async function retryAllFailed() {
  try {
    const data = await api('/research/tasks/retry', {
      method: 'POST',
      body: { allFailed: true }
    })
    counts.value = data.counts || counts.value
    toast(`已重新排队 ${data.retried} 个失败任务`)
    await load()
    emit('changed')
  } catch (err) {
    toast(err.message || '重试失败')
  }
}

async function retryIds(ids, message) {
  try {
    const data = await api('/research/tasks/retry', { method: 'POST', body: { ids } })
    counts.value = data.counts || counts.value
    toast(message)
    await load()
    emit('changed')
  } catch (err) {
    toast(err.message || '重试失败')
  }
}

async function remove(item) {
  if (!window.confirm(`删除「${item.query}」的研究任务？`)) return
  try {
    const data = await api('/research/tasks/' + encodeURIComponent(item.id), { method: 'DELETE' })
    counts.value = data.counts || counts.value
    toast('任务已删除')
    await load()
    emit('changed')
  } catch (err) {
    toast(err.message || '删除失败')
  }
}

function statusMeta(item) {
  return STATUS[item.status] || { label: item.status || '—', cls: '' }
}

function timeText(value) {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return '—'
  return d.toLocaleString('zh-CN', { hour12: false })
}

function contextText(item) {
  const text = (item.context || '').replace(/\s+/g, ' ').trim()
  return text || '—'
}

onMounted(() => {
  load()
  timer = window.setInterval(load, 6000)
})
onBeforeUnmount(() => window.clearInterval(timer))
</script>

<template>
  <section>
    <header class="ac-page-head">
      <div>
        <p class="ac-kicker">RESEARCH QUEUE</p>
        <h1 class="ac-page-title">研究任务</h1>
        <p class="ac-page-sub">
          扫描到的未收录成分先进入这里，后台自动研究；失败也有记录，可以重试。成功后写回成分库，报告重新分析即可补全。
        </p>
      </div>
      <div class="ac-head-actions">
        <button type="button" class="ac-btn" :disabled="loading" @click="load">
          {{ loading ? '刷新中…' : '刷新' }}
        </button>
        <button
          type="button"
          class="ac-btn"
          :disabled="!counts.failed"
          @click="retryAllFailed"
        >
          重试全部失败（{{ counts.failed }}）
        </button>
      </div>
    </header>

    <div v-if="!researchOn" class="ac-alert warn">
      <b>研究 API 未配置</b>
      任务会安静排队；到「API 与模型」配置研究模型并保存后，后台 worker 会自动开始处理。
    </div>

    <div v-if="error" class="ac-alert error">
      <b>加载失败</b>{{ error }}
      <button type="button" class="ac-link" @click="load">重试</button>
    </div>

    <div class="task-stats">
      <span><b>{{ counts.active }}</b> 未完成</span>
      <span><b>{{ counts.failed }}</b> 失败</span>
      <span><b>{{ counts.done }}</b> 已完成</span>
      <span class="muted">共 {{ counts.total }} 条</span>
    </div>

    <div class="task-tabs">
      <button
        v-for="tab in tabs"
        :key="tab.id"
        type="button"
        class="task-tab"
        :class="{ on: status === tab.id }"
        @click="status = tab.id"
      >
        {{ tab.label }} <em>{{ tab.n }}</em>
      </button>
    </div>

    <div class="ac-card">
      <div class="ac-table-wrap">
        <table class="ac-table task-table">
          <thead>
            <tr>
              <th>原始名称</th>
              <th>状态</th>
              <th>尝试</th>
              <th>结果 / 错误</th>
              <th>来源上下文</th>
              <th>时间</th>
              <th class="right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in filtered" :key="item.id">
              <td>
                <div class="task-query">{{ item.query }}</div>
                <div class="task-id">{{ item.id }}</div>
              </td>
              <td>
                <span class="ac-badge" :class="statusMeta(item).cls">{{ statusMeta(item).label }}</span>
              </td>
              <td>{{ item.attempts || 0 }}</td>
              <td>
                <div v-if="item.status === 'done'" class="task-result">
                  {{ item.resultName || '已完成' }}
                  <span v-if="item.confidence != null" class="ac-badge override">
                    AI {{ Math.round(Number(item.confidence) * 100) }}%
                  </span>
                </div>
                <div v-else-if="item.error" class="task-error" :title="item.error">{{ item.error }}</div>
                <div v-else class="muted">—</div>
              </td>
              <td>
                <div class="task-context" :title="contextText(item)">{{ contextText(item) }}</div>
              </td>
              <td class="task-time">{{ timeText(item.updatedAt || item.createdAt) }}</td>
              <td class="right">
                <button
                  v-if="item.status === 'failed' || item.status === 'queued'"
                  type="button"
                  class="ac-link"
                  :disabled="item.status === 'queued'"
                  @click="retry(item)"
                >重试</button>
                <button type="button" class="ac-link danger" @click="remove(item)">删除</button>
              </td>
            </tr>
            <tr v-if="!filtered.length">
              <td colspan="7">
                <div class="task-empty">
                  <b>没有匹配的任务</b>
                  <span>
                    {{ status === 'active' ? '当前没有排队、研究中和失败的任务。' : '换一个筛选，或去扫一瓶新的成分表。' }}
                  </span>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>

<style scoped>
.ac-head-actions { display: flex; gap: 8px; flex-wrap: wrap; }

.task-stats {
  display: flex;
  gap: 16px;
  padding: 10px 12px;
  border: 1px solid var(--ac-line);
  border-radius: 8px;
  background: #FCFAF4;
  font-size: 11px;
  color: var(--ac-muted);
}
.task-stats b { color: var(--ac-ink); font-size: 14px; margin-right: 3px; }
.task-stats .muted { margin-left: auto; color: var(--ac-muted-2); }

.task-tabs { display: flex; gap: 2px; margin: 12px 0 8px; overflow-x: auto; }
.task-tab {
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  padding: 7px 10px;
  color: var(--ac-muted);
  font-size: 11px;
  cursor: pointer;
  white-space: nowrap;
}
.task-tab em {
  margin-left: 3px;
  padding: 0 4px;
  border-radius: 4px;
  background: #F1EDE3;
  color: var(--ac-muted-2);
  font-style: normal;
  font-size: 9px;
}
.task-tab.on { border-bottom-color: var(--accent); color: var(--accent-ink); }
.task-tab.on em { background: var(--accent-soft); color: var(--accent-ink); }

.task-table { min-width: 980px; }
.task-query { font-size: 12.5px; color: var(--ac-ink); font-weight: 500; }
.task-id { margin-top: 2px; font-size: 9px; color: var(--ac-muted-2); letter-spacing: .04em; }
.task-result { font-size: 10.5px; color: var(--ac-ink); line-height: 1.65; }
.task-error {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  max-width: 230px;
  overflow: hidden;
  color: #A34035;
  font-size: 10.5px;
  line-height: 1.65;
  white-space: normal;
}
.task-context {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  max-width: 280px;
  overflow: hidden;
  color: var(--ac-muted);
  font-size: 10px;
  line-height: 1.65;
  white-space: normal;
}
.task-time { white-space: nowrap; font-size: 9.5px; color: var(--ac-muted-2); }
.task-empty { padding: 28px 10px; text-align: center; color: var(--ac-muted); }
.task-empty b { display: block; margin-bottom: 5px; color: var(--ac-ink); font-size: 12px; }
.task-empty span { font-size: 10.5px; }
.ac-link {
  border: 0;
  background: transparent;
  padding: 0 4px;
  color: var(--accent);
  font: inherit;
  font-size: 10.5px;
  cursor: pointer;
}
.ac-link.danger { color: #A34035; }
.ac-link:disabled { opacity: .45; cursor: default; }
</style>
