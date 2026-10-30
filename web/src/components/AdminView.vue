<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, provide, ref } from 'vue'
import { getJSON } from '../lib/http.js'
import AdminDashboard from './admin/AdminDashboard.vue'
import AdminApiPanel from './admin/AdminApiPanel.vue'
import AdminIngredients from './admin/AdminIngredients.vue'
import AdminResearchTasks from './admin/AdminResearchTasks.vue'
import AdminReports from './admin/AdminReports.vue'
import AdminFeedback from './admin/AdminFeedback.vue'
import AdminSystem from './admin/AdminSystem.vue'
import './admin/admin.css'

const TOKEN_KEY = 'cosme:admin-token'

const token = ref(readToken())
const password = ref('')
const loginError = ref('')
const loginLoading = ref(false)

const stats = ref(null)
const ingredientsData = ref({
  items: [],
  total: 0,
  active: 0,
  counts: { pending: 0, confirmed: 0, rejected: 0 },
  storage: 'builtin',
  overridden: false,
  researchEnabled: false
})
const reportItems = ref([])
const feedbackItems = ref([])
const version = ref({})
const loading = ref(false)
const refreshing = ref(false)
const loadError = ref('')

const section = ref('dashboard')
const ingredientFocusStatus = ref('')
const sidebarOpen = ref(false)
const query = ref('')
const searchOpen = ref(false)
const searchWrap = ref(null)
const mainRef = ref(null)
const toast = ref('')
let toastTimer = 0

const navItems = computed(() => [
  { id: 'dashboard', label: '概览', icon: 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z' },
  { id: 'api', label: 'API 与模型', icon: 'M9 2v6M15 2v6M7 8h10v4a5 5 0 0 1-10 0zM12 17v5' },
  { id: 'ingredients', label: '成分数据', icon: 'M9 3h6M10 3v6l-5 9a2 2 0 0 0 2 3h10a2 2 0 0 0 2-3l-5-9V3', badge: stats.value?.library?.counts?.pending ?? stats.value?.library?.size },
  { id: 'research', label: '研究任务', icon: 'M12 3v4M12 17v4M3 12h4M17 12h4M5.6 5.6l2.8 2.8M15.6 15.6l2.8 2.8M18.4 5.6l-2.8 2.8M8.4 15.6l-2.8 2.8', badge: stats.value?.researchTasks?.active },
  { id: 'reports', label: '分享报告', icon: 'M10 13a5 5 0 0 0 7 0l2-2a5 5 0 0 0-7-7l-1 1M14 11a5 5 0 0 0-7 0l-2 2a5 5 0 0 0 7 7l1-1', badge: stats.value?.reports?.count },
  { id: 'feedback', label: '用户反馈', icon: 'M21 15a4 4 0 0 1-4 4H8l-5 3V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4z', badge: stats.value?.feedback?.count },
  { id: 'system', label: '系统状态', icon: 'M3 12h4l2-6 4 12 2-6h6' }
])

const visionShort = computed(() => {
  const vision = stats.value?.vision
  if (!vision || !vision.enabled) return '视觉未配置'
  return vision.model || '视觉已配置'
})

const results = computed(() => {
  const q = query.value.trim().toLowerCase()
  const empty = { ingredients: [], reports: [], feedback: [] }
  if (!q) return empty

  const ingredients = (ingredientsData.value.items || [])
    .filter((item) => {
      const hay = [item.name, item.cat, item.function, item.description, ...(item.aliases || [])].join(' ').toLowerCase()
      return hay.includes(q)
    })
    .slice(0, 5)

  const reports = (reportItems.value || [])
    .filter((item) => [item.id, item.name, item.analysisId, item.headline].join(' ').toLowerCase().includes(q))
    .slice(0, 5)

  const feedback = (feedbackItems.value || [])
    .filter((item) => [item.message, item.contact, item.page].join(' ').toLowerCase().includes(q))
    .slice(0, 5)

  return { ingredients, reports, feedback }
})

const resultTotal = computed(
  () => results.value.ingredients.length + results.value.reports.length + results.value.feedback.length
)

function readToken() {
  try {
    return sessionStorage.getItem(TOKEN_KEY) || ''
  } catch {
    return ''
  }
}

function writeToken(value) {
  try {
    if (value) sessionStorage.setItem(TOKEN_KEY, value)
    else sessionStorage.removeItem(TOKEN_KEY)
  } catch {
    /* 隐私模式忽略 */
  }
}

async function api(path, options = {}) {
  const headers = {
    ...(token.value ? { Authorization: 'Bearer ' + token.value } : {}),
    ...(options.body ? { 'Content-Type': 'application/json' } : {}),
    ...(options.headers || {})
  }
  const opts = { ...options, headers }
  if (opts.body != null && typeof opts.body !== 'string') {
    opts.body = JSON.stringify(opts.body)
  }
  const res = await fetch('/api/admin' + path, opts)
  let data = {}
  try {
    data = await res.json()
  } catch {
    data = {}
  }
  if (res.status === 401) {
    logoutLocal(data.error || '登录已失效，请重新输入管理密码')
    throw new Error(data.error || '登录已失效')
  }
  if (!res.ok) {
    throw new Error(data.error || `请求失败（${res.status}）`)
  }
  return data
}

async function submitLogin() {
  if (!password.value.trim()) {
    loginError.value = '请输入管理密码'
    return
  }
  loginLoading.value = true
  loginError.value = ''
  try {
    const res = await fetch('/api/admin/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: password.value })
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) throw new Error(data.error || '密码不正确')
    token.value = data.token
    writeToken(data.token)
    password.value = ''
    await loadAll()
  } catch (err) {
    loginError.value = err.message || '登录失败'
  } finally {
    loginLoading.value = false
  }
}

function normalizeIngredients(data) {
  return {
    items: Array.isArray(data?.items) ? data.items : [],
    total: data?.total || 0,
    active: data?.active || 0,
    counts: data?.counts || { pending: 0, confirmed: 0, rejected: 0 },
    storage: data?.storage || 'builtin',
    overridden: !!data?.overridden,
    researchEnabled: !!data?.researchEnabled
  }
}

async function loadAll(silent = false) {
  if (silent) refreshing.value = true
  else loading.value = true
  loadError.value = ''
  try {
    const [statData, ingredientData, reportData, feedbackData, versionData] = await Promise.all([
      api('/stats'),
      api('/ingredients'),
      api('/reports?limit=100'),
      api('/feedback?limit=100'),
      getJSON('/api/version', 10000)
    ])

    stats.value = statData
    ingredientsData.value = normalizeIngredients(ingredientData)
    reportItems.value = Array.isArray(reportData.items) ? reportData.items : []
    feedbackItems.value = Array.isArray(feedbackData.items) ? feedbackData.items : []
    version.value = versionData || {}
  } catch (err) {
    if (token.value) {
      if (silent && stats.value) showToast(err.message)
      else loadError.value = err.message || '控制台数据加载失败'
    }
  } finally {
    loading.value = false
    refreshing.value = false
  }
}

async function refreshStats() {
  try {
    stats.value = await api('/stats')
  } catch (err) {
    showToast(err.message)
  }
}

async function refreshIngredients() {
  try {
    const data = await api('/ingredients')
    ingredientsData.value = normalizeIngredients(data)
    await refreshStats()
  } catch (err) {
    showToast(err.message)
  }
}

async function refreshReports() {
  try {
    const data = await api('/reports?limit=100')
    reportItems.value = Array.isArray(data.items) ? data.items : []
    await refreshStats()
  } catch (err) {
    showToast(err.message)
  }
}

async function onApiSaved() {
  await refreshStats()
  showToast('模型配置已保存，立即生效')
}

async function onIngredientsChanged() {
  await refreshIngredients()
}

async function onReportsChanged() {
  await refreshReports()
}

function logoutLocal(message = '') {
  token.value = ''
  writeToken('')
  stats.value = null
  ingredientsData.value = {
    items: [],
    total: 0,
    active: 0,
    counts: { pending: 0, confirmed: 0, rejected: 0 },
    storage: 'builtin',
    overridden: false,
    researchEnabled: false
  }
  reportItems.value = []
  feedbackItems.value = []
  version.value = {}
  section.value = 'dashboard'
  sidebarOpen.value = false
  query.value = ''
  searchOpen.value = false
  if (message) loginError.value = message
}

async function doLogout() {
  const current = token.value
  logoutLocal()
  loginError.value = ''
  if (!current) return
  try {
    await fetch('/api/admin/logout', {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + current }
    })
  } catch {
    /* 本地退出即可 */
  }
}

function go(id, q = '', status = '') {
  section.value = id
  sidebarOpen.value = false
  query.value = q
  ingredientFocusStatus.value = id === 'ingredients' ? status : ''
  searchOpen.value = false
  nextTick(() => {
    window.scrollTo({ top: 0, behavior: 'smooth' })
    mainRef.value?.scrollTo({ top: 0, behavior: 'smooth' })
  })
}

function pick(type, item) {
  if (type === 'ingredient') go('ingredients', item.name, item.status || 'pending')
  else if (type === 'report') go('reports', item.id)
  else go('feedback', item.message)
}

function onNavigate(target) {
  if (target && typeof target === 'object') go(target.id, '', target.status || '')
  else go(target)
}

function openFirstResult() {
  if (results.value.ingredients[0]) return pick('ingredient', results.value.ingredients[0])
  if (results.value.reports[0]) return pick('report', results.value.reports[0])
  if (results.value.feedback[0]) return pick('feedback', results.value.feedback[0])
}

function clearSearch() {
  query.value = ''
  searchOpen.value = false
}

function snippet(value, max = 44) {
  const str = String(value || '')
  return str.length > max ? str.slice(0, max) + '…' : str
}

function showToast(message) {
  toast.value = message
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => {
    toast.value = ''
  }, 2800)
}

function onDocClick(event) {
  if (searchWrap.value && !searchWrap.value.contains(event.target)) {
    searchOpen.value = false
  }
}

provide('adminApi', api)
provide('adminToast', showToast)
provide('adminClearSearch', clearSearch)
provide('adminNavigate', go)

let previousTitle = ''

onMounted(() => {
  previousTitle = document.title
  document.title = '管理控制台 · 成分镜'
  if (token.value) loadAll()
  document.addEventListener('mousedown', onDocClick)
})

onBeforeUnmount(() => {
  document.title = previousTitle || '成分镜 · 看懂这瓶适不适合你'
  document.removeEventListener('mousedown', onDocClick)
  clearTimeout(toastTimer)
})
</script>

<template>
  <div class="ac">
    <!-- 未登录：独立登录页，不显示侧栏 -->
    <div v-if="!token" class="ac-login">
      <div class="ac-login-wrap">
        <div class="ac-login-brand">
          <span class="ac-logo">
            <svg viewBox="0 0 32 32">
              <circle cx="16" cy="16" r="13.2" />
              <path d="M16 5.2c3.7 3.3 4.9 6.9 4.9 10.8s-1.2 7.5-4.9 10.8c-3.7-3.3-4.9-6.9-4.9-10.8S12.3 8.5 16 5.2Z" />
              <path d="M6 16h20" opacity=".45" />
            </svg>
          </span>
          <div>
            <b>成分镜</b>
            <span>MANAGEMENT CONSOLE</span>
          </div>
        </div>

        <form class="ac-login-card" @submit.prevent="submitLogin">
          <p class="ac-kicker">ADMIN CONSOLE · V1.0</p>
          <h1>登录管理控制台</h1>
          <p class="ac-login-sub">管理成分数据、视觉模型、分享报告与用户反馈。所有操作只影响本机这份数据。</p>

          <label class="ac-field">
            <span>管理密码</span>
            <input
              v-model="password"
              class="ac-input"
              type="password"
              autocomplete="current-password"
              placeholder="粘贴 admin-password.txt 中的密码"
              :disabled="loginLoading"
            />
          </label>

          <p v-if="loginError" class="ac-alert error" role="alert">{{ loginError }}</p>

          <button type="submit" class="ac-btn primary block" :disabled="loginLoading">
            <i v-if="loginLoading" class="ac-spinner"></i>
            {{ loginLoading ? '验证中…' : '进入控制台' }}
          </button>

          <p class="ac-login-hint">
            密码只在本机校验。首次启动会自动生成在 <code>server/data/admin-password.txt</code>，
            也可以用 <code>ADMIN_PASSWORD</code> 环境变量指定。
          </p>
        </form>

        <a class="ac-login-back" href="#/">← 返回成分镜前台</a>
      </div>
    </div>

    <!-- 已登录：控制台壳层 -->
    <div v-else class="ac-shell">
      <header class="ac-top">
        <button type="button" class="ac-menu" aria-label="打开导航" @click="sidebarOpen = !sidebarOpen">
          <svg viewBox="0 0 24 24"><path d="M4 7h16M4 12h16M4 17h16" /></svg>
        </button>

        <button type="button" class="ac-brand" @click="go('dashboard')">
          <span class="ac-logo">
            <svg viewBox="0 0 32 32">
              <circle cx="16" cy="16" r="13.2" />
              <path d="M16 5.2c3.7 3.3 4.9 6.9 4.9 10.8s-1.2 7.5-4.9 10.8c-3.7-3.3-4.9-6.9-4.9-10.8S12.3 8.5 16 5.2Z" />
              <path d="M6 16h20" opacity=".45" />
            </svg>
          </span>
          <span class="ac-brand-text">
            <b>成分镜</b>
            <em>CONTROL ROOM</em>
          </span>
        </button>

        <div ref="searchWrap" class="ac-search">
          <svg class="ac-search-icon" viewBox="0 0 24 24">
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-4-4" />
          </svg>
          <input
            v-model="query"
            type="search"
            placeholder="搜索成分、报告编号、产品名或反馈…"
            aria-label="全局搜索"
            spellcheck="false"
            @focus="searchOpen = true"
            @input="searchOpen = true"
            @keydown.enter.prevent="openFirstResult"
            @keydown.esc="searchOpen = false"
          />
          <button v-if="query" type="button" class="ac-search-clear" aria-label="清空搜索" @click="clearSearch">×</button>

          <div v-if="searchOpen && query.trim()" class="ac-results">
            <template v-if="resultTotal">
              <div v-if="results.ingredients.length" class="ac-result-group">
                <p>成分</p>
                <button
                  v-for="item in results.ingredients"
                  :key="item.name"
                  type="button"
                  class="ac-result"
                  @click="pick('ingredient', item)"
                >
                  <span class="ac-result-type">成分</span>
                  <b>{{ item.name }}</b>
                  <small>{{ item.role }} · {{ item.cat }}</small>
                </button>
              </div>

              <div v-if="results.reports.length" class="ac-result-group">
                <p>分享报告</p>
                <button
                  v-for="item in results.reports"
                  :key="item.id"
                  type="button"
                  class="ac-result"
                  @click="pick('report', item)"
                >
                  <span class="ac-result-type">报告</span>
                  <b>{{ item.name || '未命名产品' }}</b>
                  <small class="ac-mono">{{ item.id }}</small>
                </button>
              </div>

              <div v-if="results.feedback.length" class="ac-result-group">
                <p>用户反馈</p>
                <button
                  v-for="item in results.feedback"
                  :key="item.receivedAt + item.message"
                  type="button"
                  class="ac-result"
                  @click="pick('feedback', item)"
                >
                  <span class="ac-result-type">反馈</span>
                  <b>{{ snippet(item.message) }}</b>
                  <small>{{ item.page || '—' }}</small>
                </button>
              </div>
            </template>
            <p v-else class="ac-result-empty">没有匹配的结果</p>
          </div>
        </div>

        <div class="ac-top-actions">
          <button
            type="button"
            class="ac-refresh"
            :class="{ spinning: refreshing }"
            :disabled="refreshing"
            title="刷新数据"
            aria-label="刷新数据"
            @click="loadAll(true)"
          >
            <svg viewBox="0 0 24 24"><path d="M20 11a8 8 0 1 0-2.3 6.7M20 4v7h-7" /></svg>
          </button>
          <span class="ac-status" :class="{ on: stats?.vision?.enabled }" :title="visionShort">
            <i></i>
            <span>{{ visionShort }}</span>
          </span>
          <a class="ac-front" href="#/">前台</a>
          <button type="button" class="ac-logout" @click="doLogout">退出</button>
        </div>
      </header>

      <div class="ac-body">
        <aside class="ac-side" :class="{ open: sidebarOpen }">
          <div class="ac-side-scroll">
            <p class="ac-side-label">工作台</p>
            <nav class="ac-nav">
              <button
                v-for="item in navItems"
                :key="item.id"
                type="button"
                :class="{ on: section === item.id }"
                @click="go(item.id)"
              >
                <svg viewBox="0 0 24 24"><path :d="item.icon" /></svg>
                <span>{{ item.label }}</span>
                <em v-if="item.badge">{{ item.badge }}</em>
              </button>
            </nav>

            <div class="ac-side-card">
              <p>当前成分库</p>
              <b>{{ stats?.library?.size ?? '—' }} <span>条</span></b>
              <small>{{ stats?.library?.overridden ? '自定义库 · 已落盘' : '内置库 · 未改动' }}</small>
            </div>
          </div>

          <div class="ac-side-foot">
            <div class="ac-side-status">
              <span class="ac-dot" :class="{ on: stats?.vision?.enabled }"></span>
              {{ stats?.vision?.enabled ? '视觉模型已配置' : '视觉模型未配置' }}
            </div>
            <p>v{{ stats?.server?.version || version.version || '1.0.0' }} · 规则 {{ version.rulesVersion || '2026.10' }}</p>
            <a href="#/">← 返回成分镜前台</a>
          </div>
        </aside>

        <div v-if="sidebarOpen" class="ac-scrim" @click="sidebarOpen = false"></div>

        <main ref="mainRef" class="ac-main">
          <div v-if="loading" class="ac-main-loading">
            <div class="ac-skeleton" style="height: 58px; max-width: 420px"></div>
            <div class="ac-kpi-grid">
              <div v-for="n in 4" :key="n" class="ac-skeleton" style="height: 104px"></div>
            </div>
            <div class="ac-skeleton" style="height: 280px"></div>
          </div>

          <div v-else-if="loadError" class="ac-card ac-error-state">
            <b>控制台数据没加载出来</b>
            <p>{{ loadError }}</p>
            <div class="ac-actions">
              <button type="button" class="ac-btn primary" @click="loadAll()">重试</button>
              <button type="button" class="ac-btn" @click="doLogout">退出登录</button>
            </div>
          </div>

          <template v-else>
            <AdminDashboard
              v-if="section === 'dashboard'"
              :stats="stats"
              :reports="reportItems"
              :feedback="feedbackItems"
              :version="version"
              @navigate="onNavigate"
            />
            <AdminApiPanel v-else-if="section === 'api'" @saved="onApiSaved" />
            <AdminIngredients
              v-else-if="section === 'ingredients'"
              :items="ingredientsData.items"
              :total="ingredientsData.total"
              :active="ingredientsData.active"
              :counts="ingredientsData.counts"
              :storage="ingredientsData.storage"
              :overridden="ingredientsData.overridden"
              :research-enabled="ingredientsData.researchEnabled"
              :query="query"
              :focus-status="ingredientFocusStatus"
              @changed="onIngredientsChanged"
            />
            <AdminResearchTasks
              v-else-if="section === 'research'"
              :research-enabled="ingredientsData.researchEnabled"
              :query="query"
              @changed="loadAll(true)"
            />
            <AdminReports
              v-else-if="section === 'reports'"
              :items="reportItems"
              :query="query"
              @changed="onReportsChanged"
            />
            <AdminFeedback v-else-if="section === 'feedback'" :items="feedbackItems" :query="query" />
            <AdminSystem v-else :stats="stats" :version="version" @refresh="loadAll(true)" />
          </template>
        </main>
      </div>

      <transition name="ac-toast">
        <div v-if="toast" class="ac-toast" role="status">{{ toast }}</div>
      </transition>
    </div>
  </div>
</template>
