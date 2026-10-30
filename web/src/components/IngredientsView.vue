<script setup>
import { computed, onMounted, ref } from 'vue'
import { getJSON } from '../lib/http.js'

const props = defineProps({ version: { type: Object, default: () => ({}) } })

const items = ref([])
const meta = ref({ total: 0, libraryVersion: '', source: '' })
const loading = ref(true)
const error = ref('')

const query = ref('')
const role = ref('全部')
const risk = ref('全部')

const RISKS = ['全部', '孕期', '敏感肌', '致痘', '限量']

const roles = computed(() => {
  const set = new Set(items.value.map((item) => item.role).filter(Boolean))
  return ['全部', ...set]
})

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  return items.value.filter((item) => {
    if (role.value !== '全部' && item.role !== role.value) return false
    if (!matchesRisk(item)) return false
    if (!q) return true
    const haystack = [item.name, item.cat, item.function, item.description, ...(item.aliases || [])]
      .join(' ')
      .toLowerCase()
    return haystack.includes(q)
  })
})

function matchesRisk(item) {
  switch (risk.value) {
    case '孕期':
      return item.preg === 'avoid' || item.preg === 'caution'
    case '敏感肌':
      return item.sensitive === 'avoid' || item.sensitive === 'caution'
    case '致痘':
      return Number(item.comedo) >= 3
    case '限量':
      return !!item.limit
    default:
      return true
  }
}

function pregLabel(item) {
  if (item.preg === 'avoid') return '孕期避开'
  if (item.preg === 'caution') return '孕期慎用'
  return ''
}

function sensitiveLabel(item) {
  if (item.sensitive === 'avoid') return '敏感肌避开'
  if (item.sensitive === 'caution') return '敏感肌留意'
  return ''
}

function irritLabel(item) {
  if (item.irrit === 'high') return '强刺激'
  if (item.irrit === 'medium') return '刺激中等'
  return ''
}

function aliasText(item) {
  const list = item.aliases || []
  if (!list.length) return ''
  const head = list.slice(0, 3).join('、')
  return list.length > 3 ? `${head} 等 ${list.length} 个` : head
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await getJSON('/api/ingredients', 15000)
    items.value = Array.isArray(data.items) ? data.items : []
    meta.value = {
      total: data.total || items.value.length,
      libraryVersion: data.libraryVersion || props.version.libraryVersion || '',
      source: data.source || '《化妆品安全技术规范》与常见 INCI 名称整理'
    }
  } catch (err) {
    error.value = err.message || '成分库加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <article class="page-view">
    <header class="page-head">
      <div>
        <p class="eyebrow">成分库</p>
        <h1 class="page-title serif">先认识成分，再看位置</h1>
        <p class="lead">
          这里收录 {{ meta.total || version.librarySize || 60 }} 个高频成分：作用、致痘性、刺激性、孕期与敏感肌提示、法规限量。
          带「AI 深度研究」标记的成分，会展示研究后的完整描述；其余成分先显示一句话作用。
        </p>
      </div>
      <div class="head-side">
        <span class="side-label">库版本</span>
        <b>{{ meta.libraryVersion || version.libraryVersion || '2026.10' }}</b>
        <span class="side-source">{{ meta.source }}</span>
      </div>
    </header>

    <section class="toolbar card">
      <div class="search">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="11" cy="11" r="6.5" />
          <path d="M16 16l4.5 4.5" />
        </svg>
        <input
          v-model="query"
          type="search"
          placeholder="搜成分名、别名或作用，例如：烟酰胺 / Niacinamide / 防腐"
          spellcheck="false"
        />
      </div>

      <div class="filters">
        <div class="filter-row">
          <span class="filter-label">角色</span>
          <button
            v-for="r in roles"
            :key="r"
            type="button"
            class="chip-btn"
            :class="{ on: role === r }"
            @click="role = r"
          >
            {{ r }}
          </button>
        </div>
        <div class="filter-row">
          <span class="filter-label">风险</span>
          <button
            v-for="r in RISKS"
            :key="r"
            type="button"
            class="chip-btn"
            :class="{ on: risk === r }"
            @click="risk = r"
          >
            {{ r }}
          </button>
        </div>
      </div>

      <div class="result-line">
        <span>共 {{ filtered.length }} 条</span>
        <button v-if="query || role !== '全部' || risk !== '全部'" type="button" class="reset" @click="query = ''; role = '全部'; risk = '全部'">
          清除筛选
        </button>
      </div>
    </section>

    <div v-if="loading" class="grid">
      <div v-for="i in 6" :key="i" class="card skeleton">
        <span class="sk-line w40"></span>
        <span class="sk-line w80"></span>
        <span class="sk-line w60"></span>
      </div>
    </div>

    <section v-else-if="error" class="card state">
      <b>成分库暂时没加载出来</b>
      <p>{{ error }}</p>
      <button type="button" class="btn" @click="load">重试</button>
    </section>

    <section v-else-if="!filtered.length" class="card state">
      <b>没有匹配的成分</b>
      <p>换个写法试试：可以搜中文名、英文 INCI 或作用关键词。</p>
      <button type="button" class="btn" @click="query = ''; role = '全部'; risk = '全部'">清除筛选</button>
    </section>

    <div v-else class="grid">
      <article v-for="item in filtered" :key="item.name" class="ing card">
        <header class="ing-top">
          <h2 class="ing-name">{{ item.name }}</h2>
          <span class="role">{{ item.role }}</span>
          <span class="cat">{{ item.cat }}</span>
          <span v-if="item.researched" class="ai-badge">AI 深度研究</span>
        </header>

        <p v-if="item.description" class="deep">{{ item.description }}</p>
        <p v-else class="fn">{{ item.function }}</p>
        <p v-if="item.description && item.function" class="fn-short">一句话：{{ item.function }}</p>

        <div v-if="pregLabel(item) || sensitiveLabel(item) || irritLabel(item) || item.comedo >= 3 || item.limit" class="tags">
          <span v-if="pregLabel(item)" class="tag" :class="item.preg === 'avoid' ? 'danger' : 'warn'">{{ pregLabel(item) }}</span>
          <span v-if="sensitiveLabel(item)" class="tag" :class="item.sensitive === 'avoid' ? 'danger' : 'warn'">{{ sensitiveLabel(item) }}</span>
          <span v-if="irritLabel(item)" class="tag" :class="item.irrit === 'high' ? 'danger' : 'warn'">{{ irritLabel(item) }}</span>
          <span v-if="item.comedo >= 3" class="tag warn">致痘 {{ item.comedo }}/5</span>
          <span v-if="item.limit" class="tag info">法规限量</span>
        </div>

        <p v-if="aliasText(item)" class="alias">别名：{{ aliasText(item) }}</p>
        <p v-if="item.limit" class="limit">限量：{{ item.limit }}</p>
        <p v-if="item.note" class="note">{{ item.note }}</p>
      </article>
    </div>

    <p class="disclaimer">
      成分库是高频成分摘录，未收录不代表没有风险；限量值与标签标注不一致时，以法规原文与产品标签为准。
      想知道我们怎么用这些数据，可以看 <a href="#/method">解读方法</a>。
    </p>
  </article>
</template>

<style scoped>
.page-view { max-width: 980px; margin: 0 auto; }

.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  flex-wrap: wrap;
  margin-bottom: 18px;
}

.page-title { margin: 8px 0 8px; font-size: 34px; font-weight: 600; line-height: 1.35; }

.lead { max-width: 600px; font-size: 13px; line-height: 1.9; color: var(--ink2); }

.head-side { display: flex; flex-direction: column; align-items: flex-end; gap: 1px; max-width: 260px; text-align: right; }

.side-label { font-size: 10.5px; letter-spacing: .08em; color: var(--ink3); }

.head-side b { font-size: 15px; color: var(--accent-ink); }

.side-source { font-size: 10.5px; line-height: 1.6; color: var(--ink3); }

.toolbar { padding: 15px 18px; margin-bottom: 14px; }

.search { display: flex; align-items: center; gap: 9px; padding: 0 12px; border: 1px solid var(--line); border-radius: 10px; background: #FCFBF7; }

.search svg { width: 16px; height: 16px; fill: none; stroke: var(--ink3); stroke-width: 1.7; stroke-linecap: round; }

.search input {
  flex: 1;
  min-width: 0;
  padding: 11px 0;
  border: none;
  background: transparent;
  font-size: 13px;
  color: var(--ink);
  outline: none;
}

.search:focus-within { border-color: var(--accent); box-shadow: var(--focus); }

.filters { display: flex; flex-direction: column; gap: 7px; margin-top: 12px; }

.filter-row { display: flex; align-items: center; gap: 5px; flex-wrap: wrap; }

.filter-label { width: 34px; flex-shrink: 0; font-size: 11px; color: var(--ink3); }

.chip-btn {
  padding: 3px 10px;
  border: 1px solid transparent;
  border-radius: 999px;
  background: #F5F2EA;
  color: var(--ink2);
  font-size: 11.5px;
  transition: all .15s;
}

.chip-btn:hover { border-color: var(--line2); }

.chip-btn.on { background: var(--accent); color: #fff; border-color: var(--accent); }

.result-line { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-top: 12px; padding-top: 10px; border-top: 1px dashed var(--line); font-size: 11.5px; color: var(--ink3); }

.reset { font-size: 11.5px; color: var(--accent); }

.grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }

.ing { padding: 16px 17px; }

.ing-top { display: flex; align-items: baseline; gap: 7px; flex-wrap: wrap; }

.ing-name { font-size: 15px; font-weight: 600; }

.role { padding: 1px 7px; border-radius: 4px; background: var(--accent-soft); color: var(--accent-ink); font-size: 10.5px; }

.cat { font-size: 10.5px; color: var(--ink3); }

.ai-badge {
  margin-left: auto;
  padding: 1px 6px;
  border-radius: 4px;
  background: #EEF3F8;
  color: #3C6788;
  font-size: 9.5px;
  line-height: 1.7;
  white-space: nowrap;
}

.deep {
  margin-top: 7px;
  color: var(--ink2);
  font-size: 12.5px;
  line-height: 1.85;
  white-space: pre-wrap;
}

.fn-short { margin-top: 5px; color: var(--ink3); font-size: 10.5px; line-height: 1.7; }

.fn { margin-top: 5px; font-size: 12.5px; line-height: 1.8; color: var(--ink2); }

.tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 8px; }

.tag { padding: 1px 7px; border-radius: 4px; font-size: 10.5px; line-height: 1.7; }

.tag.danger { background: var(--danger-soft); color: var(--danger); }
.tag.warn { background: var(--warn-soft); color: var(--warn); }
.tag.info { background: #F3F0E8; color: var(--ink2); }

.alias { margin-top: 7px; font-size: 11px; line-height: 1.7; color: var(--ink3); }

.limit { margin-top: 5px; font-size: 11px; line-height: 1.7; color: var(--warn); }

.note { margin-top: 5px; padding-top: 5px; border-top: 1px dashed var(--line); font-size: 11px; line-height: 1.7; color: var(--ink3); }

.skeleton { padding: 18px; }

.sk-line { display: block; height: 10px; margin-bottom: 9px; border-radius: 4px; background: linear-gradient(90deg, #F2EEE4, #FAF8F3, #F2EEE4); background-size: 200% 100%; animation: shimmer 1.4s ease-in-out infinite; }

.sk-line.w40 { width: 40%; }
.sk-line.w80 { width: 80%; }
.sk-line.w60 { width: 60%; }

@keyframes shimmer {
  from { background-position: 100% 0; }
  to { background-position: -100% 0; }
}

.state { padding: 26px; text-align: center; }

.state b { font-size: 14px; }

.state p { margin: 5px 0 14px; font-size: 12.5px; line-height: 1.8; color: var(--ink3); }

.disclaimer { margin-top: 18px; font-size: 11.5px; line-height: 1.9; color: var(--ink3); }

.disclaimer a { color: var(--accent); }

@media (max-width: 760px) {
  .page-title { font-size: 27px; }
  .head-side { align-items: flex-start; text-align: left; max-width: none; }
  .grid { grid-template-columns: 1fr; }
  .filter-label { width: auto; }
  .toolbar { padding: 12px; }
}
</style>
