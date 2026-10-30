<script setup>
import { computed } from 'vue'
import { formatUptime } from '../../lib/format.js'

const props = defineProps({
  stats: { type: Object, default: null },
  version: { type: Object, default: () => ({}) }
})

const emit = defineEmits(['refresh'])

const library = computed(() => props.stats?.library || {})
const reports = computed(() => props.stats?.reports || {})
const feedback = computed(() => props.stats?.feedback || {})
const vision = computed(() => props.stats?.vision || {})
const research = computed(() => props.stats?.research || {})
const server = computed(() => props.stats?.server || {})

const endpoints = [
  { method: 'GET', path: '/api/health', desc: '服务探活与版本、视觉配置状态' },
  { method: 'GET', path: '/api/version', desc: '产品、规则库、成分库版本' },
  { method: 'GET', path: '/api/ingredients', desc: '全量成分库，前台成分库页使用' },
  { method: 'POST', path: '/api/analyze', desc: '文本 + 档案 → 个性化报告' },
  { method: 'POST', path: '/api/vision', desc: '拍照识别（需要视觉模型）' },
  { method: 'POST', path: '/api/reports', desc: '保存分享报告，返回短 ID' },
  { method: 'POST', path: '/api/feedback', desc: '写入本机 feedback.jsonl' },
  { method: 'GET', path: '/api/admin/research/tasks', desc: '研究队列：未收录 token、失败原因与重试' },
  { method: 'POST', path: '/api/admin/login', desc: '管理密码换 12 小时令牌' }
]
</script>

<template>
  <section>
    <header class="ac-page-head">
      <div>
        <p class="ac-kicker">SYSTEM</p>
        <h1 class="ac-page-title">系统状态</h1>
        <p class="ac-page-sub">这是单机部署：服务、数据文件和视觉配置都在同一台机器上。下面是当前运行时的真实状态。</p>
      </div>
      <div class="ac-actions">
        <a class="ac-btn" href="/api/health" target="_blank" rel="noopener">查看健康接口</a>
        <button type="button" class="ac-btn primary" @click="emit('refresh')">刷新状态</button>
      </div>
    </header>

    <div class="ac-grid-2">
      <div class="ac-stack">
        <section class="ac-card ac-card-pad">
          <div class="ac-section-title">
            <h2>运行时</h2>
            <span>{{ server.uptimeSeconds ? '已运行 ' + formatUptime(server.uptimeSeconds) : '' }}</span>
          </div>
          <dl class="ac-kv">
            <dt>服务版本</dt><dd>v{{ server.version || version.version || '1.0.0' }}</dd>
            <dt>规则库</dt><dd>{{ server.rulesVersion || version.rulesVersion || '2026.10' }}</dd>
            <dt>成分库</dt><dd>{{ library.size ?? version.librarySize ?? '—' }} 条 · {{ library.overridden ? '自定义' : '内置' }}</dd>
            <dt>API 版本</dt><dd>{{ server.apiVersion || version.apiVersion || 'v1' }}</dd>
            <dt>管理密码</dt><dd class="path">{{ stats?.admin?.passwordSource || '—' }}</dd>
          </dl>
        </section>

        <section class="ac-card ac-card-pad">
          <div class="ac-section-title"><h2>数据文件</h2><span>全部在本机</span></div>
          <div class="path-list">
            <div class="path-row">
              <span>成分库覆盖</span>
              <code>{{ library.overridden ? library.file : '未覆盖 · 使用内置 ingredients.json' }}</code>
            </div>
            <div class="path-row">
              <span>分享报告</span>
              <code>{{ reports.directory || 'server/data/reports' }}（{{ reports.count ?? 0 }} 份）</code>
            </div>
            <div class="path-row">
              <span>用户反馈</span>
              <code>{{ feedback.file || 'server/data/feedback.jsonl' }}（{{ feedback.count ?? 0 }} 条）</code>
            </div>
            <div class="path-row">
              <span>研究队列</span>
              <code>data/research-tasks.json（{{ stats?.researchTasks?.total ?? 0 }} 条）</code>
            </div>
            <div class="path-row">
              <span>视觉配置</span>
              <code>{{ vision.configFile || 'server/data/vision-settings.json' }}</code>
            </div>
          </div>
        </section>
      </div>

      <div class="ac-stack">
        <section class="ac-card ac-card-pad">
          <div class="ac-section-title">
            <h2>视觉服务</h2>
            <span class="ac-badge" :class="vision.enabled ? 'good' : 'neutral'">
              {{ vision.enabled ? '已配置' : '未配置' }}
            </span>
          </div>
          <dl class="ac-kv">
            <dt>模型</dt><dd>{{ vision.model || '—' }}</dd>
            <dt>来源</dt><dd>{{ vision.source || '—' }}</dd>
            <dt>Base URL</dt><dd class="path">{{ vision.baseUrl || '—' }}</dd>
            <dt>Key</dt><dd>{{ vision.keyMasked || '—' }}</dd>
          </dl>
          <p v-if="!vision.enabled" class="ac-alert warn" style="margin-top: 12px">
            拍照识别当前不可用。到「API 与模型」填写支持看图的 Key，或先使用粘贴与示例。
          </p>
        </section>

        <section class="ac-card ac-card-pad">
          <div class="ac-section-title">
            <h2>研究服务</h2>
            <span class="ac-badge" :class="research.enabled ? 'good' : 'neutral'">
              {{ research.enabled ? '已配置' : '未配置' }}
            </span>
          </div>
          <dl class="ac-kv">
            <dt>模型</dt><dd>{{ research.model || '—' }}</dd>
            <dt>来源</dt><dd>{{ research.source || '—' }}</dd>
            <dt>Base URL</dt><dd class="path">{{ research.baseUrl || '—' }}</dd>
            <dt>Key</dt><dd>{{ research.keyMasked || '—' }}</dd>
          </dl>
          <p v-if="!research.enabled" class="ac-alert warn" style="margin-top: 12px">
            研究 API 当前不可用。未收录成分仍会进入研究队列，但不会自动补全；报告只显示「待补全」，配置后队列自动开始处理。
          </p>
        </section>

        <section class="ac-card ac-card-pad">
          <div class="ac-section-title"><h2>公开接口</h2><span>同源 /api</span></div>
          <div class="endpoint-list">
            <div v-for="e in endpoints" :key="e.method + e.path" class="endpoint">
              <span class="method" :class="e.method.toLowerCase()">{{ e.method }}</span>
              <code>{{ e.path }}</code>
              <span class="desc">{{ e.desc }}</span>
            </div>
          </div>
        </section>
      </div>
    </div>

    <section class="ac-card ac-card-pad" style="margin-top: 14px">
      <div class="ac-section-title"><h2>安全与边界</h2></div>
      <div class="ac-checklist">
        <div class="ac-checkrow">
          <i>✓</i>
          <div><b>管理令牌只存在内存</b><p>登录后 12 小时有效，后端重启即失效；所有写操作都要求 Bearer 令牌。</p></div>
        </div>
        <div class="ac-checkrow">
          <i>✓</i>
          <div><b>Key 不回显</b><p>控制台只展示掩码；完整 Key 落盘在 data/ai-settings.json，或走环境变量。</p></div>
        </div>
        <div class="ac-checkrow">
          <i>✓</i>
          <div><b>分享 30 天清理</b><p>报告文件按最后修改时间清理；删除后短链立即失效。</p></div>
        </div>
        <div class="ac-checkrow">
          <i class="warn">!</i>
          <div><b>分享链接依赖当前地址</b><p>localhost 生成的链接换设备打不开；跨设备请用局域网 IP，公网分享需要先部署。</p></div>
        </div>
      </div>
    </section>
  </section>
</template>

<style scoped>
.path { font-family: "SFMono-Regular", Consolas, monospace; font-size: 10px; }

.path-list { display: flex; flex-direction: column; gap: 9px; }

.path-row {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-bottom: 9px;
  border-bottom: 1px solid var(--ac-line-soft);
}

.path-row:last-child { border-bottom: none; padding-bottom: 0; }

.path-row span { color: var(--ac-muted-2); font-size: 10px; }

.path-row code {
  color: var(--ink2);
  font-family: "SFMono-Regular", Consolas, monospace;
  font-size: 10px;
  line-height: 1.6;
  word-break: break-all;
}

.endpoint-list { display: flex; flex-direction: column; }

.endpoint {
  display: flex;
  align-items: baseline;
  gap: 7px;
  padding: 6px 0;
  border-bottom: 1px solid var(--ac-line-soft);
  font-size: 10px;
}

.endpoint:last-child { border-bottom: none; }

.method {
  width: 36px;
  flex-shrink: 0;
  padding: 0 4px;
  border-radius: 3px;
  background: #F1EEE5;
  color: var(--ac-muted);
  font-size: 8.5px;
  font-weight: 600;
  text-align: center;
}

.method.get { background: var(--accent-soft); color: var(--accent); }
.method.post { background: var(--warn-soft); color: var(--warn); }

.endpoint code { flex-shrink: 0; color: var(--ink2); font-family: "SFMono-Regular", Consolas, monospace; }

.endpoint .desc {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--ac-muted-2);
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 520px) {
  .endpoint .desc { display: none; }
}
</style>
