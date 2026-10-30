<script setup>
import { computed } from 'vue'
import { formatDateTime, formatUptime } from '../../lib/format.js'
import { levelMeta } from '../../lib/admin.js'

const props = defineProps({
  stats: { type: Object, default: null },
  reports: { type: Array, default: () => [] },
  feedback: { type: Array, default: () => [] },
  version: { type: Object, default: () => ({}) }
})

const emit = defineEmits(['navigate'])

const library = computed(() => props.stats?.library || {})
const counts = computed(() => library.value.counts || { pending: 0, confirmed: 0, rejected: 0 })
const vision = computed(() => props.stats?.vision || {})
const research = computed(() => props.stats?.research || {})
const queue = computed(() => props.stats?.researchTasks || {})
const server = computed(() => props.stats?.server || {})

const recentReports = computed(() => (props.reports || []).slice(0, 5))
const recentFeedback = computed(() => (props.feedback || []).slice(0, 4))

const checks = computed(() => [
  {
    ok: !!research.value.enabled,
    title: research.value.enabled ? '研究 API 已配置' : '研究 API 未配置',
    desc: research.value.enabled
      ? `${research.value.model || '未命名模型'} · 未收录成分会进入研究队列，由后台自动补全`
      : '未收录成分会先进入研究队列；配置研究 API 后自动处理'
  },
  {
    ok: !(queue.value.active > 0),
    title: queue.value.active > 0 ? `研究队列 ${queue.value.active} 条` : '研究队列空闲',
    desc: queue.value.active > 0
      ? `排队 ${queue.value.queued || 0} · 研究中 ${queue.value.researching || 0} · 失败 ${queue.value.failed || 0}`
      : `已完成 ${queue.value.done || 0} 条`
  },
  {
    ok: !!vision.value.enabled,
    title: vision.value.enabled ? '视觉模型已配置' : '视觉模型未配置',
    desc: vision.value.enabled
      ? `${vision.value.model || '未命名模型'} · 建议在 API 页点一次测试识别`
      : '拍照识别不可用；可在 API 页填 Key 与模型，或先用粘贴/示例'
  },
  {
    ok: true,
    title: library.value.overridden ? '成分库已自定义' : '成分库使用内置版本',
    desc: `待研究 ${counts.value.pending || 0} · 已确认 ${counts.value.confirmed || 0} · 已拒绝 ${counts.value.rejected || 0}`
  },
  {
    ok: true,
    title: '分享报告 30 天自动清理',
    desc: `当前 ${props.stats?.reports?.count || 0} 份；保存在本机 data/reports/`
  },
  {
    ok: true,
    title: '反馈通道本机落盘',
    desc: `当前 ${props.stats?.feedback?.count || 0} 条；保存在 data/feedback.jsonl`
  }
])

function shortPath(value) {
  if (!value) return '—'
  const parts = String(value).split(/[\\/]/).filter(Boolean)
  return parts.slice(-2).join('/')
}

function reportLevel(item) {
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
        <p class="ac-kicker">OVERVIEW</p>
        <h1 class="ac-page-title">概览</h1>
        <p class="ac-page-sub">今天要处理的事，一眼看完。所有数据都来自本机服务，不经过第三方。</p>
      </div>
      <div class="ac-actions">
        <button type="button" class="ac-btn" @click="emit('navigate', 'ingredients')">管理成分</button>
        <button type="button" class="ac-btn primary" @click="emit('navigate', 'api')">配置 API</button>
      </div>
    </header>

    <div class="ac-kpi-grid">
      <button type="button" class="ac-kpi" @click="emit('navigate', 'ingredients')">
        <span class="ac-kpi-arrow">↗</span>
        <span class="ac-kpi-label">
          <svg viewBox="0 0 24 24"><path d="M9 3h6M10 3v6l-5 9a2 2 0 0 0 2 3h10a2 2 0 0 0 2-3l-5-9V3" /></svg>
          成分库
        </span>
        <span class="ac-kpi-value">{{ library.size ?? '—' }}<small>条</small></span>
        <span class="ac-kpi-meta">待研究 {{ counts.pending || 0 }} · 已确认 {{ counts.confirmed || 0 }}</span>
      </button>

      <button type="button" class="ac-kpi" @click="emit('navigate', { id: 'ingredients', status: 'pending' })">
        <span class="ac-kpi-arrow">↗</span>
        <span class="ac-kpi-label">
          <svg viewBox="0 0 24 24"><path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z" /></svg>
          待研究
        </span>
        <span class="ac-kpi-value">{{ counts.pending ?? '—' }}<small>条</small></span>
        <span class="ac-kpi-meta">AI 研究结果需人工确认</span>
      </button>

      <button type="button" class="ac-kpi" @click="emit('navigate', { id: 'ingredients', status: 'confirmed' })">
        <span class="ac-kpi-arrow">↗</span>
        <span class="ac-kpi-label">
          <svg viewBox="0 0 24 24"><path d="M20 6 9 17l-5-5" /></svg>
          已确认
        </span>
        <span class="ac-kpi-value">{{ counts.confirmed ?? '—' }}<small>条</small></span>
        <span class="ac-kpi-meta">参与报告匹配</span>
      </button>

      <button type="button" class="ac-kpi" @click="emit('navigate', 'reports')">
        <span class="ac-kpi-arrow">↗</span>
        <span class="ac-kpi-label">
          <svg viewBox="0 0 24 24"><path d="M10 13a5 5 0 0 0 7 0l2-2a5 5 0 0 0-7-7l-1 1M14 11a5 5 0 0 0-7 0l-2 2a5 5 0 0 0 7 7l1-1" /></svg>
          分享报告
        </span>
        <span class="ac-kpi-value">{{ stats?.reports?.count ?? '—' }}<small>份</small></span>
        <span class="ac-kpi-meta">30 天后自动清理</span>
      </button>
    </div>

    <div class="ac-grid-2">
      <div class="ac-stack">
        <section class="ac-card ac-card-pad">
          <div class="ac-section-title">
            <h2>最近分享</h2>
            <a href="#" @click.prevent="emit('navigate', 'reports')">全部 →</a>
          </div>

          <div v-if="recentReports.length" class="ac-recent">
            <div v-for="item in recentReports" :key="item.id" class="ac-recent-row">
              <span class="ac-badge" :class="reportLevel(item).cls">{{ reportLevel(item).label }}</span>
              <div class="ac-recent-main">
                <b>{{ item.name || '未命名产品' }}</b>
                <p class="ac-mono">{{ item.id }} · {{ item.headline || '无标题' }}</p>
              </div>
              <time>{{ formatDateTime(item.sharedAt) }}</time>
            </div>
          </div>
          <div v-else class="ac-empty" style="padding: 26px 10px">
            <b>还没有分享报告</b>
            <p>回前台解读一瓶，点「分享」生成短链后，这里会出现记录。</p>
          </div>
        </section>

        <section class="ac-card ac-card-pad">
          <div class="ac-section-title">
            <h2>最新反馈</h2>
            <a href="#" @click.prevent="emit('navigate', 'feedback')">全部 →</a>
          </div>

          <div v-if="recentFeedback.length" class="ac-recent">
            <div v-for="item in recentFeedback" :key="item.receivedAt + item.message" class="ac-recent-row">
              <span class="ac-badge neutral">{{ item.page || '—' }}</span>
              <div class="ac-recent-main">
                <b>{{ item.message }}</b>
                <p>{{ item.contact || '未留联系方式' }}</p>
              </div>
              <time>{{ formatDateTime(item.receivedAt) }}</time>
            </div>
          </div>
          <div v-else class="ac-empty" style="padding: 26px 10px">
            <b>还没有收到反馈</b>
            <p>前台「关于」页有反馈入口；内测时可以让试用者直接提交。</p>
          </div>
        </section>
      </div>

      <div class="ac-stack">
        <section class="ac-card ac-card-pad">
          <div class="ac-section-title">
            <h2>运行状态</h2>
            <span>{{ server.uptimeSeconds ? '已运行 ' + formatUptime(server.uptimeSeconds) : '' }}</span>
          </div>
          <dl class="ac-kv">
            <dt>服务版本</dt><dd>v{{ server.version || version.version || '1.0.0' }}</dd>
            <dt>规则库</dt><dd>{{ server.rulesVersion || version.rulesVersion || '2026.10' }}</dd>
            <dt>成分库</dt><dd>{{ library.size || 0 }} 条 · {{ library.overridden ? '自定义' : '内置' }}</dd>
            <dt>视觉模型</dt><dd>{{ vision.model || '未配置' }}</dd>
            <dt>视觉来源</dt><dd>{{ vision.source || '—' }}</dd>
            <dt>研究模型</dt><dd>{{ research.model || '未配置' }}</dd>
            <dt>研究来源</dt><dd>{{ research.source || '—' }}</dd>
            <dt>配置文件</dt><dd>{{ shortPath(vision.configFile) }}</dd>
            <dt>管理密码</dt><dd>{{ shortPath(stats?.admin?.passwordSource) }}</dd>
          </dl>
        </section>

        <section class="ac-card ac-card-pad">
          <div class="ac-section-title"><h2>检查清单</h2></div>
          <div class="ac-checklist">
            <div v-for="item in checks" :key="item.title" class="ac-checkrow">
              <i :class="{ warn: !item.ok }">{{ item.ok ? '✓' : '!' }}</i>
              <div>
                <b>{{ item.title }}</b>
                <p>{{ item.desc }}</p>
              </div>
            </div>
          </div>
        </section>
      </div>
    </div>
  </section>
</template>

<style scoped>
.ac-kpi-value.text { padding-top: 5px; font-size: 19px; }
.ac-kpi-value.text.muted { color: var(--ac-muted-2); }
</style>
