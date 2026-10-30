<script setup>
import { computed, inject, reactive, ref, watch } from 'vue'
import {
  PROFILE_OPTIONS,
  RISK_OPTIONS,
  ROLE_OPTIONS,
  SAFETY_OPTIONS,
  SOURCE_LABEL,
  STATUS_OPTIONS,
  riskTags,
  statusMeta
} from '../../lib/admin.js'

const props = defineProps({
  items: { type: Array, default: () => [] },
  total: { type: Number, default: 0 },
  active: { type: Number, default: 0 },
  counts: { type: Object, default: () => ({ pending: 0, confirmed: 0, rejected: 0 }) },
  storage: { type: String, default: 'builtin' },
  overridden: { type: Boolean, default: false },
  researchEnabled: { type: Boolean, default: false },
  query: { type: String, default: '' },
  focusStatus: { type: String, default: '' }
})

const emit = defineEmits(['changed'])
const api = inject('adminApi')
const toast = inject('adminToast')
const clearSearch = inject('adminClearSearch')
const navigate = inject('adminNavigate')

const TABS = STATUS_OPTIONS
const tab = ref('pending')
const page = ref(1)
const pageSize = 10
const role = ref('')
const riskOnly = ref(false)
const sortMode = ref('newest')

const selected = ref([])
const statusChanging = ref(false)
const researching = ref(false)
const researchingName = ref('')
const researchError = ref('')

const modalOpen = ref(false)
const editingOriginal = ref('')
const editingMeta = ref(null)
const saving = ref(false)
const formError = ref('')
const form = reactive(blankForm())

const deleteTarget = ref(null)
const deleting = ref(false)
const deleteError = ref('')

const resetOpen = ref(false)
const resetting = ref(false)
const resetError = ref('')

const researchAllOpen = ref(false)

const tabCounts = computed(() => {
  const c = props.counts || {}
  return {
    pending: Number(c.pending || 0),
    confirmed: Number(c.confirmed || 0),
    rejected: Number(c.rejected || 0)
  }
})

const filtered = computed(() => {
  const q = props.query.trim().toLowerCase()
  const list = (props.items || []).filter((item) => {
    if ((item.status || 'pending') !== tab.value) return false
    if (role.value && item.role !== role.value) return false
    if (riskOnly.value && !hasRisk(item)) return false
    if (!q) return true
    const hay = [item.name, item.cat, item.function, item.description, ...(item.aliases || [])].join(' ').toLowerCase()
    return hay.includes(q)
  })

  const sorted = [...list]
  if (sortMode.value === 'name') {
    sorted.sort((a, b) => String(a.name || '').localeCompare(String(b.name || ''), 'zh-CN'))
  } else if (sortMode.value === 'oldest') {
    sorted.sort((a, b) => createdAtValue(a) - createdAtValue(b))
  } else {
    sorted.sort((a, b) => createdAtValue(b) - createdAtValue(a))
  }
  return sorted
})

function createdAtValue(item) {
  const value = Date.parse(item.createdAt || '')
  return Number.isNaN(value) ? 0 : value
}

const pageCount = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize)))
const paged = computed(() => filtered.value.slice((page.value - 1) * pageSize, page.value * pageSize))
const rangeStart = computed(() => (filtered.value.length ? (page.value - 1) * pageSize + 1 : 0))
const rangeEnd = computed(() => Math.min(page.value * pageSize, filtered.value.length))

const selectedSet = computed(() => new Set(selected.value))
const pageAllSelected = computed(
  () => paged.value.length > 0 && paged.value.every((item) => selectedSet.value.has(item.name))
)
const allFilteredSelected = computed(
  () => filtered.value.length > 0 && filtered.value.every((item) => selectedSet.value.has(item.name))
)
const pendingAllNames = computed(() =>
  (props.items || [])
    .filter((item) => (item.status || 'pending') === 'pending')
    .slice(0, 10)
    .map((item) => item.name)
)

watch(
  () => props.focusStatus,
  (status) => {
    if (TABS.some((t) => t.value === status)) tab.value = status
  },
  { immediate: true }
)
watch([tab, () => props.query, role, riskOnly, sortMode], () => {
  page.value = 1
  selected.value = []
})
watch(pageCount, (count) => {
  if (page.value > count) page.value = count
})

function goTab(next) {
  tab.value = next
}

function blankForm() {
  return {
    name: '',
    aliases: '',
    role: '基质',
    cat: '',
    function: '',
    description: '',
    safety: 'low',
    comedo: 0,
    irrit: 'low',
    preg: 'safe',
    sensitive: 'safe',
    limit: '',
    note: '',
    status: 'pending'
  }
}

function hasRisk(item) {
  return (
    (item.comedo || 0) >= 3 ||
    item.irrit !== 'low' ||
    item.preg !== 'safe' ||
    item.sensitive !== 'safe' ||
    item.safety === 'high'
  )
}

function toggleSelect(name) {
  if (selectedSet.value.has(name)) selected.value = selected.value.filter((n) => n !== name)
  else selected.value = [...selected.value, name]
}

function togglePage(event) {
  const checked = event.target.checked
  const names = paged.value.map((i) => i.name)
  if (checked) selected.value = [...new Set([...selected.value, ...names])]
  else selected.value = selected.value.filter((n) => !names.includes(n))
}

function toggleAllFiltered(event) {
  if (event.target.checked) selected.value = filtered.value.map((i) => i.name)
  else selected.value = []
}

function toggleAllCurrent() {
  if (allFilteredSelected.value) selected.value = []
  else selected.value = filtered.value.map((i) => i.name)
}

function selectedNames(max = 10) {
  if (!selected.value.length) {
    toast('先勾选要处理的成分')
    return []
  }
  return selected.value.slice(0, max)
}

async function setStatusSelected(status) {
  const names = selectedNames(9999)
  if (!names.length) return
  statusChanging.value = true
  try {
    await api('/ingredients/status', { method: 'POST', body: { names, status } })
    toast(`已更新 ${names.length} 个成分的状态`)
    selected.value = []
    emit('changed')
  } catch (err) {
    toast(err.message)
  } finally {
    statusChanging.value = false
  }
}

async function setStatusOne(item, status) {
  statusChanging.value = true
  try {
    await api('/ingredients/status', { method: 'POST', body: { names: [item.name], status } })
    toast('状态已更新')
    emit('changed')
  } catch (err) {
    toast(err.message)
  } finally {
    statusChanging.value = false
  }
}

function researchReady() {
  if (!props.researchEnabled) {
    toast('还没有配置研究 API，先到「API 与模型」配置')
    return false
  }
  return true
}

async function researchNames(names) {
  if (!researchReady() || !names.length) return
  researchError.value = ''
  researching.value = true
  try {
    const data = await api('/ingredients/research', {
      method: 'POST',
      body: { names: names.slice(0, 10) }
    })
    const ok = data.researched || []
    const failed = data.failed || []
    toast(`AI 研究完成 ${ok.length} 个${failed.length ? `，${failed.length} 个失败` : ''}`)
    if (failed.length) {
      researchError.value = Object.entries(data.errors || {})
        .map(([name, msg]) => `${name}：${msg}`)
        .join('；')
    }
    selected.value = []
    emit('changed')
  } catch (err) {
    researchError.value = err.message
    toast(err.message)
  } finally {
    researching.value = false
    researchingName.value = ''
  }
}

async function researchSelected() {
  const names = selectedNames()
  if (!names.length) return
  if (names.length < selected.value.length) toast('单次最多研究 10 个，已取前 10 个')
  await researchNames(names)
}

async function researchOne(item) {
  if (!researchReady()) return
  researchingName.value = item.name
  await researchNames([item.name])
}

function openResearchAll() {
  if (!researchReady()) return
  if (!pendingAllNames.value.length) {
    toast('没有待研究成分')
    return
  }
  researchAllOpen.value = true
}

async function confirmResearchAll() {
  researchAllOpen.value = false
  await researchNames(pendingAllNames.value)
}

function openCreate() {
  editingOriginal.value = ''
  editingMeta.value = null
  Object.assign(form, blankForm())
  formError.value = ''
  modalOpen.value = true
}

function openEdit(item) {
  editingOriginal.value = item.name
  editingMeta.value = {
    source: item.source,
    confidence: item.confidence,
    evidence: item.evidence,
    model: item.researchModel,
    researchedAt: item.researchedAt
  }
  Object.assign(form, blankForm(), {
    name: item.name,
    aliases: (item.aliases || []).join('、'),
    role: item.role || '基质',
    cat: item.cat || '',
    function: item.function || '',
    description: item.description || '',
    safety: item.safety || 'low',
    comedo: Number(item.comedo) || 0,
    irrit: item.irrit || 'low',
    preg: item.preg || 'safe',
    sensitive: item.sensitive || 'safe',
    limit: item.limit || '',
    note: item.note || '',
    status: item.status || 'pending'
  })
  formError.value = ''
  modalOpen.value = true
}

function closeModal() {
  if (saving.value) return
  modalOpen.value = false
}

async function save() {
  if (!form.name.trim()) {
    formError.value = '标准名不能为空'
    return
  }
  saving.value = true
  formError.value = ''
  const payload = {
    name: form.name.trim(),
    aliases: form.aliases
      .split(/[,，、\n]/)
      .map((s) => s.trim())
      .filter(Boolean),
    role: form.role,
    cat: form.cat,
    function: form.function,
    description: form.description,
    safety: form.safety,
    comedo: Number(form.comedo) || 0,
    irrit: form.irrit,
    preg: form.preg,
    sensitive: form.sensitive,
    limit: form.limit,
    note: form.note,
    status: form.status
  }
  try {
    const creating = !editingOriginal.value
    const path = creating
      ? '/ingredients'
      : '/ingredients?name=' + encodeURIComponent(editingOriginal.value)
    await api(path, { method: creating ? 'POST' : 'PUT', body: payload })
    toast(creating ? '已新增，报告立即使用新数据' : '已保存，报告立即使用新数据')
    modalOpen.value = false
    if (creating) {
      tab.value = form.status || 'pending'
      page.value = 1
      selected.value = []
    }
    emit('changed')
  } catch (err) {
    formError.value = err.message
  } finally {
    saving.value = false
  }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleting.value = true
  deleteError.value = ''
  try {
    await api('/ingredients?name=' + encodeURIComponent(deleteTarget.value.name), { method: 'DELETE' })
    toast('已删除「' + deleteTarget.value.name + '」')
    deleteTarget.value = null
    emit('changed')
  } catch (err) {
    deleteError.value = err.message
  } finally {
    deleting.value = false
  }
}

async function confirmReset() {
  resetting.value = true
  resetError.value = ''
  try {
    await api('/ingredients/reset', { method: 'POST' })
    toast('已恢复内置成分库')
    resetOpen.value = false
    page.value = 1
    selected.value = []
    emit('changed')
  } catch (err) {
    resetError.value = err.message
  } finally {
    resetting.value = false
  }
}

function sourceLabel(item) {
  return SOURCE_LABEL[item.source] || item.source || '—'
}

function confidenceText(item) {
  if (item.confidence == null) return ''
  return `AI ${Math.round(Number(item.confidence) * 100)}%`
}

function createdAtText(item) {
  const value = item.createdAt
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
</script>

<template>
  <section>
    <header class="ac-page-head">
      <div>
        <p class="ac-kicker">DATA LIBRARY</p>
        <h1 class="ac-page-title">成分数据</h1>
        <p class="ac-page-sub">
          待研究与已确认参与报告匹配，已拒绝不参与。AI 研究结果先回到待研究并注明 AI 补全；人工确认后转为已确认。
        </p>
      </div>
      <div class="ac-actions">
        <button
          v-if="overridden"
          type="button"
          class="ac-btn"
          @click="resetOpen = true"
        >
          恢复内置库
        </button>
        <button
          type="button"
          class="ac-btn"
          :disabled="!pendingAllNames.length || !researchEnabled || researching"
          @click="openResearchAll"
        >
          <i v-if="researching" class="ac-spinner"></i>
          AI 研究待研究
        </button>
        <button type="button" class="ac-btn primary" @click="openCreate">+ 新增成分</button>
      </div>
    </header>

    <div class="ac-tabs">
      <button
        v-for="t in TABS"
        :key="t.value"
        type="button"
        :class="{ on: tab === t.value }"
        @click="goTab(t.value)"
      >
        {{ t.label }}
        <em>{{ tabCounts[t.value] || 0 }}</em>
      </button>
    </div>

    <p v-if="!researchEnabled" class="ac-alert warn data-warning">
      研究 API 未配置：未收录成分仍会进入研究队列，但不会自动处理。可以先到「API 与模型」配置总 API 或研究 API。
      <button type="button" class="ac-btn sm" style="margin-left: 6px" @click="navigate('api')">去配置</button>
    </p>

    <p v-else class="ac-alert data-warning">
      AI 研究只生成候选数据，并先进入「待研究」；报告会注明 AI 补全。风险字段请人工复核后再改为「已确认」。待研究与已确认都参与匹配，已拒绝不参与。
    </p>

    <div class="ac-card data-bar">
      <div class="db-main">
        <span class="ac-badge" :class="overridden ? 'override' : 'neutral'">
          {{ storage === 'override' ? '自定义库' : '内置库' }}
        </span>
        <b>{{ active }}</b><span>条参与匹配</span>
        <span class="ac-muted">/ 共 {{ total }} 条</span>
        <span v-if="props.query" class="ac-badge neutral">筛选：{{ props.query }}</span>
      </div>
      <div class="db-filters">
        <select v-model="role" class="ac-select" aria-label="按角色筛选">
          <option value="">全部角色</option>
          <option v-for="r in ROLE_OPTIONS" :key="r" :value="r">{{ r }}</option>
        </select>
        <select v-model="sortMode" class="ac-select" aria-label="排序方式">
          <option value="newest">最新添加</option>
          <option value="oldest">最早添加</option>
          <option value="name">名称 A–Z</option>
        </select>
        <label class="ac-check">
          <input v-model="riskOnly" type="checkbox" />
          只看有风险
        </label>
        <button v-if="props.query" type="button" class="ac-btn sm ghost" @click="clearSearch">清除筛选</button>
      </div>
    </div>

    <div class="ac-batchbar">
      <label class="ac-check">
        <input type="checkbox" :checked="allFilteredSelected" @change="toggleAllFiltered" />
        全选当前筛选
      </label>
      <span class="batch-count">已选 {{ selected.length }} 个</span>
      <button
        type="button"
        class="ac-btn sm"
        :disabled="!selected.length || !researchEnabled || researching"
        @click="researchSelected"
      >
        <i v-if="researching" class="ac-spinner"></i>
        AI 研究
      </button>
      <button
        type="button"
        class="ac-btn sm"
        :disabled="!selected.length || statusChanging"
        @click="setStatusSelected('confirmed')"
      >
        批量确认
      </button>
      <button
        type="button"
        class="ac-btn sm"
        :disabled="!selected.length || statusChanging"
        @click="setStatusSelected('rejected')"
      >
        批量拒绝
      </button>
      <span v-if="!researchEnabled" class="ac-muted">未配置研究 API</span>
    </div>

    <p v-if="researchError" class="ac-alert error" style="margin-bottom: 10px">
      部分研究失败：{{ researchError }}
    </p>

    <section class="ac-card">
      <div v-if="paged.length" class="ac-table-wrap">
        <table class="ac-table">
          <thead>
            <tr>
              <th style="width: 34px">
                <input type="checkbox" :checked="pageAllSelected" aria-label="全选当前页" @change="togglePage" />
              </th>
              <th>成分</th>
              <th>描述</th>
              <th>角色 / 分类</th>
              <th>风险标签</th>
              <th>添加时间</th>
              <th>状态</th>
              <th class="right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in paged" :key="item.name">
              <td>
                <input
                  type="checkbox"
                  :checked="selectedSet.has(item.name)"
                  :aria-label="'选择 ' + item.name"
                  @change="toggleSelect(item.name)"
                />
              </td>
              <td>
                <div class="ing-name">
                  {{ item.name }}
                  <span v-if="item.source === 'ai'" class="ac-badge override">AI</span>
                </div>
                <div class="ing-alias">{{ (item.aliases || []).slice(0, 3).join(' / ') || '—' }}</div>
              </td>
              <td>
                <div class="ing-desc" :title="item.description || item.function || ''">
                  {{ item.description || item.function || '—' }}
                </div>
                <div v-if="item.source === 'ai' && item.description" class="ing-source">AI 深度研究</div>
              </td>
              <td>
                <span class="ac-chip">{{ item.role }}</span>
                <span class="ing-cat">{{ item.cat || '—' }}</span>
              </td>
              <td>
                <div v-if="riskTags(item).length" class="ac-tags">
                  <span v-for="tag in riskTags(item)" :key="tag.text" class="ac-badge" :class="tag.cls">{{ tag.text }}</span>
                </div>
                <span v-else class="ac-muted">—</span>
              </td>
              <td>
                <div class="ing-time" :title="createdAtText(item)">{{ createdAtText(item) }}</div>
                <div v-if="item.createdFallback" class="ing-time-src">
                  {{ item.source === 'builtin' ? '内置库' : '早期记录' }}
                </div>
              </td>
              <td>
                <span class="ac-badge" :class="statusMeta(item.status).cls">{{ statusMeta(item.status).label }}</span>
                <div class="ing-source">
                  {{ sourceLabel(item) }}
                  <template v-if="item.confidence != null"> · {{ confidenceText(item) }}</template>
                </div>
              </td>
              <td class="right">
                <button
                  type="button"
                  class="ac-icon-btn"
                  :disabled="!researchEnabled || researching"
                  @click="researchOne(item)"
                >
                  <i v-if="researchingName === item.name" class="ac-spinner"></i>
                  {{ researchingName === item.name ? '研究中' : '研究' }}
                </button>
                <button
                  v-if="tab !== 'confirmed'"
                  type="button"
                  class="ac-icon-btn"
                  :disabled="statusChanging"
                  @click="setStatusOne(item, 'confirmed')"
                >
                  确认
                </button>
                <button
                  v-if="tab !== 'pending'"
                  type="button"
                  class="ac-icon-btn"
                  :disabled="statusChanging"
                  @click="setStatusOne(item, 'pending')"
                >
                  待研究
                </button>
                <button
                  v-if="tab !== 'rejected'"
                  type="button"
                  class="ac-icon-btn danger"
                  :disabled="statusChanging"
                  @click="setStatusOne(item, 'rejected')"
                >
                  拒绝
                </button>
                <button type="button" class="ac-icon-btn" @click="openEdit(item)">编辑</button>
                <button type="button" class="ac-icon-btn danger" @click="deleteTarget = item">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-else class="ac-empty">
        <b>
          {{ props.query ? '没有匹配的成分' : tab === 'pending' ? '没有待研究成分' : tab === 'confirmed' ? '还没有确认的成分' : '没有拒绝的成分' }}
        </b>
        <p>
          {{ props.query ? '换个关键词，或清除角色/风险筛选。' : '扫描到未收录成分后会自动提交 AI 研究，成功后出现在待研究；也可以手动新增。' }}
        </p>
        <div class="ac-actions" style="margin-top: 4px">
          <button v-if="props.query" type="button" class="ac-btn sm" @click="clearSearch">清除筛选</button>
          <button v-else-if="tab === 'pending'" type="button" class="ac-btn sm" @click="openCreate">新增成分</button>
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

    <!-- 新增 / 编辑 -->
    <div v-if="modalOpen" class="ac-modal-mask" @click.self="closeModal">
      <div class="ac-modal wide" role="dialog" aria-modal="true" aria-labelledby="ing-title">
        <div class="ac-modal-head">
          <div>
            <p class="ac-kicker">{{ editingOriginal ? 'EDIT' : 'NEW' }}</p>
            <h2 id="ing-title">{{ editingOriginal ? '编辑成分' : '新增成分' }}</h2>
          </div>
          <button type="button" class="ac-x" aria-label="关闭" @click="closeModal">×</button>
        </div>

        <div class="ac-modal-body">
          <div v-if="editingMeta && editingMeta.source === 'ai'" class="ai-meta">
            <div>
              <span class="ac-badge override">AI 研究</span>
              <span v-if="editingMeta.confidence != null" class="ac-muted">
                置信度 {{ Math.round(Number(editingMeta.confidence) * 100) }}%
              </span>
              <span v-if="editingMeta.model" class="ac-muted">· {{ editingMeta.model }}</span>
            </div>
            <p v-if="editingMeta.evidence">依据：{{ editingMeta.evidence }}</p>
          </div>

          <div class="ac-form-grid">
            <label class="ac-field">
              <span>标准名 *</span>
              <input v-model="form.name" class="ac-input" maxlength="40" placeholder="例如 烟酰胺" />
            </label>

            <label class="ac-field">
              <span>别名（用逗号或顿号分隔）</span>
              <input v-model="form.aliases" class="ac-input" placeholder="Niacinamide、维生素B3" />
            </label>

            <label class="ac-field">
              <span>角色</span>
              <select v-model="form.role" class="ac-select">
                <option v-for="r in ROLE_OPTIONS" :key="r" :value="r">{{ r }}</option>
              </select>
            </label>

            <label class="ac-field">
              <span>分类</span>
              <input v-model="form.cat" class="ac-input" maxlength="20" placeholder="例如 美白 / 保湿 / 防腐" />
            </label>

            <label class="ac-field">
              <span>审核状态</span>
              <select v-model="form.status" class="ac-select">
                <option v-for="s in STATUS_OPTIONS" :key="s.value" :value="s.value">{{ s.label }}</option>
              </select>
            </label>

            <label class="ac-field">
              <span>安全等级</span>
              <select v-model="form.safety" class="ac-select">
                <option v-for="o in SAFETY_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</option>
              </select>
            </label>

            <label class="ac-field full">
              <span>作用说明</span>
              <textarea v-model="form.function" class="ac-textarea" maxlength="200" placeholder="一句话说明它在配方里做什么" />
            </label>

            <label class="ac-field full">
              <span>深度描述（前台成分卡片展示，AI 研究可覆盖）</span>
              <textarea
                v-model="form.description"
                class="ac-textarea deep-input"
                maxlength="600"
                placeholder="2–4 句：它是什么、在配方里的实际作用、适合/不适合谁、常见误区或注意；AI 深度研究会写入这里"
              />
              <em>留空时前台卡片回退显示上面的「作用说明」；保存后立即生效。</em>
            </label>

            <label class="ac-field">
              <span>致痘性（0–5）</span>
              <select v-model="form.comedo" class="ac-select">
                <option v-for="n in [0, 1, 2, 3, 4, 5]" :key="n" :value="n">{{ n }} / 5</option>
              </select>
            </label>

            <label class="ac-field">
              <span>刺激性</span>
              <select v-model="form.irrit" class="ac-select">
                <option v-for="o in RISK_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</option>
              </select>
            </label>

            <label class="ac-field">
              <span>孕期提示</span>
              <select v-model="form.preg" class="ac-select">
                <option v-for="o in PROFILE_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</option>
              </select>
            </label>

            <label class="ac-field">
              <span>敏感肌提示</span>
              <select v-model="form.sensitive" class="ac-select">
                <option v-for="o in PROFILE_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</option>
              </select>
            </label>

            <label class="ac-field full">
              <span>法规限量</span>
              <input v-model="form.limit" class="ac-input" maxlength="200" placeholder="例如 驻留类 ≤0.5%；没有就留空" />
            </label>

            <label class="ac-field full">
              <span>备注</span>
              <input v-model="form.note" class="ac-input" maxlength="200" placeholder="给运营/审核看的补充说明，可为空" />
            </label>
          </div>

          <p v-if="formError" class="ac-alert error" style="margin-top: 13px">{{ formError }}</p>
        </div>

        <div class="ac-modal-actions">
          <button type="button" class="ac-btn" :disabled="saving" @click="closeModal">取消</button>
          <button type="button" class="ac-btn primary" :disabled="saving" @click="save">
            <i v-if="saving" class="ac-spinner"></i>
            {{ saving ? '保存中…' : '保存并生效' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 删除确认 -->
    <div v-if="deleteTarget" class="ac-modal-mask" @click.self="deleteTarget = null">
      <div class="ac-modal" role="dialog" aria-modal="true">
        <div class="ac-modal-head">
          <div>
            <p class="ac-kicker">DELETE</p>
            <h2>删除「{{ deleteTarget.name }}」？</h2>
          </div>
          <button type="button" class="ac-x" aria-label="关闭" @click="deleteTarget = null">×</button>
        </div>
        <div class="ac-modal-body">
          <p class="ac-modal-note">删除后，成分表里再次出现这个名字会被判为「待补全」，且无法从内置库自动找回这一条。</p>
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

    <!-- 恢复内置库 -->
    <div v-if="resetOpen" class="ac-modal-mask" @click.self="resetOpen = false">
      <div class="ac-modal" role="dialog" aria-modal="true">
        <div class="ac-modal-head">
          <div>
            <p class="ac-kicker">RESET</p>
            <h2>恢复内置成分库？</h2>
          </div>
          <button type="button" class="ac-x" aria-label="关闭" @click="resetOpen = false">×</button>
        </div>
        <div class="ac-modal-body">
          <p class="ac-modal-note">会删除 data/ingredients.json，回到打包时的 60 条内置库。控制台做过的所有成分修改与 AI 研究结果都会丢失。</p>
          <p v-if="resetError" class="ac-alert error">{{ resetError }}</p>
        </div>
        <div class="ac-modal-actions">
          <button type="button" class="ac-btn" :disabled="resetting" @click="resetOpen = false">取消</button>
          <button type="button" class="ac-btn danger" :disabled="resetting" @click="confirmReset">
            <i v-if="resetting" class="ac-spinner"></i>
            {{ resetting ? '恢复中…' : '恢复内置库' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 批量研究确认 -->
    <div v-if="researchAllOpen" class="ac-modal-mask" @click.self="researchAllOpen = false">
      <div class="ac-modal" role="dialog" aria-modal="true">
        <div class="ac-modal-head">
          <div>
            <p class="ac-kicker">AI RESEARCH</p>
            <h2>研究 {{ pendingAllNames.length }} 个待研究成分？</h2>
          </div>
          <button type="button" class="ac-x" aria-label="关闭" @click="researchAllOpen = false">×</button>
        </div>
        <div class="ac-modal-body">
          <p class="ac-modal-note">
            会调用研究 API 逐条生成候选数据，单次最多 10 个。每条都可能产生模型费用；结果先回到待研究，不会自动确认。
          </p>
        </div>
        <div class="ac-modal-actions">
          <button type="button" class="ac-btn" @click="researchAllOpen = false">取消</button>
          <button type="button" class="ac-btn primary" :disabled="researching" @click="confirmResearchAll">
            <i v-if="researching" class="ac-spinner"></i>
            {{ researching ? '研究中…' : '开始研究' }}
          </button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.data-warning { margin-bottom: 10px; }

.data-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 11px 14px;
  margin-bottom: 9px;
}

.db-main { display: flex; align-items: center; gap: 5px; flex-wrap: wrap; font-size: 11.5px; }

.db-main b { font-size: 16px; font-weight: 600; }

.db-filters { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }

.db-filters .ac-select { width: 120px; height: 30px; padding: 4px 8px; font-size: 11px; }

.ac-batchbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 10px;
  padding: 8px 12px;
  border: 1px solid var(--ac-line);
  border-radius: 8px;
  background: #FAF8F2;
}

.batch-count { color: var(--ac-muted-2); font-size: 10.5px; }

.ing-name {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12.5px;
  font-weight: 500;
  color: var(--ink);
}

.ing-alias {
  max-width: 270px;
  overflow: hidden;
  margin-top: 1px;
  color: var(--ac-muted-2);
  font-size: 9.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ing-desc {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  max-width: 300px;
  overflow: hidden;
  color: var(--ink2);
  font-size: 10.5px;
  line-height: 1.65;
  white-space: normal;
}

.deep-input { min-height: 98px; }

.ing-time { white-space: nowrap; color: var(--ac-muted); font-size: 10px; line-height: 1.7; }
.ing-time-src { margin-top: 1px; color: var(--ac-muted-2); font-size: 9px; }

.ing-cat { margin-left: 5px; color: var(--ac-muted-2); font-size: 10px; }

.ing-source { margin-top: 2px; color: var(--ac-muted-2); font-size: 9px; }

.ai-meta {
  margin-bottom: 14px;
  padding: 9px 11px;
  border: 1px solid var(--accent-line);
  border-radius: 8px;
  background: var(--accent-soft);
  color: var(--accent-ink);
  font-size: 10.5px;
  line-height: 1.7;
}

.ai-meta p { margin-top: 3px; color: var(--accent-ink); font-size: 10.5px; }

@media (max-width: 720px) {
  .db-filters { width: 100%; }
  .db-filters .ac-select { flex: 1; width: auto; }
  .ac-table { min-width: 1160px; }
  .ac-batchbar { gap: 6px; }
}
</style>
