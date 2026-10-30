<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import AboutView from './components/AboutView.vue'
import AdminView from './components/AdminView.vue'
import HistoryView from './components/HistoryView.vue'
import IngredientsView from './components/IngredientsView.vue'
import IntakePanel from './components/IntakePanel.vue'
import MethodView from './components/MethodView.vue'
import NotFoundView from './components/NotFoundView.vue'
import ProfilePanel from './components/ProfilePanel.vue'
import ReportView from './components/ReportView.vue'
import { getJSON, postJSON } from './lib/http.js'
import { ingredientTokens, inspectInput, profileLabel, splitByWhitespace } from './lib/text.js'
import { buildDelta, buildReportText, reportView } from './lib/report.js'
import { clearHistory, loadHistory, removeHistory, upsertHistory } from './lib/history.js'

const STEPS = ['读取成分表', '匹配成分知识库', '运行法规与个体化规则', '生成报告']
const STORAGE_KEY = 'cosme:state:v1'
const MAX_TEXT = 4000

const text = ref('')
const profile = ref({ skin: 'oily', sensitive: true, pregnant: false })
const report = ref(null)
const loading = ref(false)
const phase = ref(0)
const error = ref('')
const recomputeError = ref('')
const delta = ref(null)
const status = ref('')
const toast = ref('')
const backendDown = ref(false)
const copyFallback = ref('')
const copyArea = ref(null)

const productName = ref('')
const meta = ref(null)
const historyItems = ref([])
const version = ref({
  name: '成分镜',
  version: '1.0.0',
  rulesVersion: '2026.10',
  libraryVersion: '2026.10',
  librarySize: 60,
  apiVersion: 'v1'
})

const share = reactive({
  open: false,
  busy: false,
  link: '',
  error: '',
  copied: false,
  forId: ''
})
const shareLinkInput = ref(null)
const shareLoading = ref(parseRoute().startsWith('s/'))
const shareError = ref('')
let shareSeq = 0

const route = ref(parseRoute())
const isAdmin = computed(() => route.value === 'admin')
const routePage = computed(() =>
  ['ingredients', 'method', 'about', 'history'].includes(route.value)
)
const isShareRoute = computed(() => route.value.startsWith('s/'))
const isNotFound = computed(
  () => route.value !== '' && !isAdmin.value && !routePage.value && !isShareRoute.value
)

function parseRoute() {
  try {
    return decodeURIComponent(window.location.hash.replace(/^#\/?/, '')).trim()
  } catch {
    return ''
  }
}

const samples = ref([])
const config = ref({ visionEnabled: null, visionModel: '', librarySize: 0 })

const recognizing = ref(false)
const photo = ref(null)
const photoNote = ref('')
const photoError = ref(false)
const fileInput = ref(null)

const tokens = computed(() => ingredientTokens(text.value))
const issue = computed(() => inspectInput(text.value, tokens.value))
const canAnalyze = computed(() => !!text.value.trim() && !loading.value && !recognizing.value)

/* 没配视觉模型时，文案别继续承诺「拍一下」 */
const tagline = computed(() =>
  config.value.visionEnabled === false
    ? '粘一下，看懂这瓶到底适不适合你'
    : '拍一下，看懂这瓶到底适不适合你'
)

/* ----------------------------- 工具 ----------------------------- */

function prefersReducedMotion() {
  return (
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  )
}

let toastTimer = 0
function showToast(message) {
  toast.value = message
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => {
    toast.value = ''
  }, 2800)
}

/* ----------------------------- 本地记忆 ----------------------------- */

let saveTimer = 0
function persist() {
  try {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        text: text.value.slice(0, MAX_TEXT),
        profile: profile.value,
        productName: productName.value.slice(0, 40)
      })
    )
  } catch {
    /* 隐私模式等场景忽略 */
  }
}

function restore() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return
    const saved = JSON.parse(raw)
    if (saved && saved.profile && typeof saved.profile === 'object') {
      profile.value = {
        skin: saved.profile.skin || 'oily',
        sensitive: !!saved.profile.sensitive,
        pregnant: !!saved.profile.pregnant
      }
    }
    if (saved && typeof saved.text === 'string') {
      text.value = saved.text.slice(0, MAX_TEXT)
    }
    if (saved && typeof saved.productName === 'string') {
      productName.value = saved.productName.slice(0, 40)
    }
  } catch {
    /* 存坏了就当没有 */
  }
}

watch(
  [profile, text, productName],
  () => {
    clearTimeout(saveTimer)
    saveTimer = setTimeout(persist, 320)
  },
  { deep: true }
)

/* ----------------------------- 分析 ----------------------------- */

let reqSeq = 0
let stepTimer = 0
let researchTimer = 0
let researchPolls = 0
const researchPolling = ref(false)
const RESEARCH_POLL_MAX = 20

function clearResearchPoll() {
  clearTimeout(researchTimer)
  researchTimer = 0
}

function scheduleResearchPoll() {
  clearResearchPoll()
  if (route.value !== '' || !report.value) return
  researchTimer = window.setTimeout(() => pollResearch(), 6000)
}

async function pollResearch({ manual = false } = {}) {
  if (researchPolling.value || loading.value) return
  if (!report.value || route.value !== '') return
  const content = text.value.trim()
  if (!content) return
  if (!manual && researchPolls >= RESEARCH_POLL_MAX) return

  const seq = reqSeq
  researchPolls += 1
  researchPolling.value = true
  clearResearchPoll()
  try {
    const json = await postJSON('/api/analyze', { text: content, profile: profile.value }, 20000)
    if (seq !== reqSeq || route.value !== '' || !report.value) return
    report.value = json
    if (meta.value) {
      meta.value = { ...meta.value, revisedAt: new Date().toISOString() }
      resetShareLink()
      rememberReport(json, meta.value, true)
    }
    if (json.researchEnabled && ((json.researchQueued || 0) + (json.researchRunning || 0) > 0)) {
      scheduleResearchPoll()
    } else {
      researchPolls = 0
      if ((json.researchFailed || 0) > 0) {
        showToast(`${json.researchFailed} 个成分研究失败，已保留在管理控制台可重试`)
      } else {
        showToast('AI 研究完成，报告已更新')
      }
    }
  } catch (err) {
    // 轮询失败不打断阅读，报告上的「立即刷新」仍然可用
  } finally {
    researchPolling.value = false
  }
}

function stopTimers() {
  clearInterval(stepTimer)
  stepTimer = 0
}

async function analyze({ silent = false } = {}) {
  const content = text.value.trim()
  if (!content) {
    error.value = '先粘贴或选择一份成分表，再开始解读'
    return
  }
  if (loading.value && !silent) return

  clearResearchPoll()
  researchPolls = 0
  const seq = ++reqSeq
  const started = performance.now()

  if (!silent) {
    error.value = ''
    report.value = null
    delta.value = null
    toast.value = ''
  }
  recomputeError.value = ''
  loading.value = true
  phase.value = 0
  stopTimers()

  if (!silent) {
    // 后端其实很快，这一步是为了让「规则在跑」这件事被看见
    stepTimer = setInterval(() => {
      if (phase.value < STEPS.length - 1) phase.value += 1
    }, 180)
  }

  try {
    const json = await postJSON('/api/analyze', { text: content, profile: profile.value }, 20000)
    if (seq !== reqSeq) return

    if (!silent) {
      const wait = 700 - (performance.now() - started)
      if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait))
      if (seq !== reqSeq) return
    }

    const previous = report.value
    report.value = json

    if (silent) {
      meta.value = {
        ...(meta.value || {}),
        revisedAt: new Date().toISOString(),
        productName: productName.value.trim(),
        ruleVersion: version.value.rulesVersion
      }
      resetShareLink()
      rememberReport(json, meta.value, true)
      delta.value = buildDelta(previous, json)
      status.value = delta.value
        ? `${delta.value.title}，${delta.value.detail}`
        : '档案已更新，结论没有变化'
    } else {
      meta.value = makeMeta()
      resetShareLink()
      rememberReport(json, meta.value, false)
      delta.value = null
      status.value = `解读完成：${json.verdict?.headline || ''}`
      nextTick(() => {
        window.scrollTo({ top: 0, behavior: prefersReducedMotion() ? 'auto' : 'smooth' })
        document.getElementById('report-top')?.focus({ preventScroll: true })
      })
    }

    if (json.researchEnabled && ((json.researchQueued || 0) + (json.researchRunning || 0) > 0)) {
      researchPolls = 0
      scheduleResearchPoll()
    }
  } catch (err) {
    if (seq !== reqSeq) return
    if (silent) {
      recomputeError.value = '重算失败：' + (err.message || '未知错误')
    } else {
      error.value = err.message || '分析失败，请重试'
    }
  } finally {
    if (seq === reqSeq) {
      stopTimers()
      loading.value = false
      phase.value = 0
    }
  }
}

/* 在报告页改档案：保留旧结论、后台重算，再把变化说出来 */
let reTimer = 0
watch(
  profile,
  () => {
    if (!report.value) return
    if (sameProfile(profile.value, report.value.profile)) return
    clearTimeout(reTimer)
    reTimer = setTimeout(() => analyze({ silent: true }), 260)
  },
  { deep: true }
)

function sameProfile(a, b) {
  if (!a || !b) return false
  return a.skin === b.skin && !!a.sensitive === !!b.sensitive && !!a.pregnant === !!b.pregnant
}

function updateProfile(next) {
  profile.value = { ...profile.value, ...next }
}

function retryAnalyze() {
  if (text.value.trim()) analyze()
  else loadMeta()
}

/* ----------------------------- 报告编号与历史 ----------------------------- */

function makeMeta() {
  const now = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  const stamp = `${String(now.getFullYear()).slice(2)}${pad(now.getMonth() + 1)}${pad(now.getDate())}`
  const rand = Math.random().toString(36).slice(2, 6).toUpperCase()
  return {
    id: `CK-${stamp}-${rand}`,
    createdAt: now.toISOString(),
    revisedAt: null,
    productName: productName.value.trim(),
    ruleVersion: version.value.rulesVersion
  }
}

function historyEntryFor(json, m, fromShare = false) {
  const view = reportView(json)
  return {
    id: m.id,
    productName: m.productName || '',
    createdAt: m.createdAt || new Date().toISOString(),
    revisedAt: m.revisedAt || null,
    level: view.level,
    headline: view.headline,
    matched: json.matched || 0,
    total: json.total || 0,
    profile: { ...profile.value },
    text: text.value.trim(),
    report: json,
    meta: { ...m },
    fromShare
  }
}

function rememberReport(json, m, fromShare = false) {
  historyItems.value = upsertHistory(historyEntryFor(json, m, fromShare))
}

function renameReport(value) {
  const clean = String(value || '').trim().slice(0, 40)
  productName.value = clean
  if (!meta.value) return
  meta.value = { ...meta.value, productName: clean }
  if (report.value) {
    historyItems.value = upsertHistory(historyEntryFor(report.value, meta.value))
  }
  resetShareLink()
  persist()
}

function openHistoryEntry(entry) {
  if (!entry || !entry.report) return
  invalidate()
  shareSeq += 1
  text.value = entry.text || ''
  profile.value = { ...profile.value, ...(entry.profile || {}) }
  productName.value = entry.productName || ''
  report.value = entry.report
  meta.value = entry.meta || {
    id: entry.id,
    createdAt: entry.createdAt,
    productName: entry.productName || '',
    ruleVersion: version.value.rulesVersion
  }
  delta.value = null
  recomputeError.value = ''
  error.value = ''
  resetShareLink()
  route.value = ''
  if (window.location.hash) {
    window.history.replaceState(null, '', window.location.pathname + window.location.search)
  }
  nextTick(() => {
    window.scrollTo({ top: 0, behavior: 'auto' })
    document.getElementById('report-top')?.focus({ preventScroll: true })
  })
}

function deleteHistoryEntry(id) {
  historyItems.value = removeHistory(id)
  showToast('已删除这条历史')
}

function clearAllHistory() {
  historyItems.value = clearHistory()
  showToast('历史已清空')
}

/* ----------------------------- 分享 ----------------------------- */

function resetShareLink() {
  share.open = false
  share.busy = false
  share.link = ''
  share.error = ''
  share.copied = false
  share.forId = ''
}

async function shareReport() {
  if (!report.value || share.busy) return
  if (!meta.value) meta.value = makeMeta()
  if (share.link && share.forId === meta.value?.id) {
    share.open = true
    return
  }

  share.busy = true
  share.error = ''
  share.copied = false
  showToast('正在生成分享链接…')
  const targetId = meta.value.id
  try {
    const data = await postJSON('/api/reports', {
      name: productName.value.trim(),
      text: text.value.trim(),
      profile: profile.value,
      report: report.value,
      meta: meta.value
    }, 20000)
    if (!report.value || meta.value?.id !== targetId || route.value !== '') return
    share.link = `${window.location.origin}${window.location.pathname}#/s/${data.id}`
    share.forId = targetId
    share.open = true
  } catch (err) {
    if (!report.value || meta.value?.id !== targetId || route.value !== '') return
    share.error = err.message || '生成分享链接失败'
    share.open = true
  } finally {
    share.busy = false
  }
}

async function copyShareLink() {
  if (!share.link) return
  try {
    await navigator.clipboard.writeText(share.link)
    share.copied = true
    showToast('分享链接已复制')
  } catch {
    const input = shareLinkInput.value
    if (input) {
      input.focus()
      input.select()
    }
    showToast('已全选链接，请按 Ctrl+C / 长按复制')
  }
}

function closeShare() {
  share.open = false
}

function copyFromShare() {
  share.open = false
  copyReport()
}

async function openShared(id) {
  if (!id) return
  const seq = ++shareSeq
  shareLoading.value = true
  shareError.value = ''
  try {
    const data = await getJSON('/api/reports/' + encodeURIComponent(id), 15000)
    if (seq !== shareSeq) return
    if (!data || !data.report) throw new Error('分享内容不完整')

    productName.value = data.name || ''
    text.value = data.text || ''
    profile.value = { ...profile.value, ...(data.profile || {}) }
    report.value = data.report

    const sharedMeta = data.meta && typeof data.meta === 'object' ? data.meta : {}
    meta.value = {
      ...sharedMeta,
      id: sharedMeta.id || ('S-' + data.id),
      createdAt: sharedMeta.createdAt || data.sharedAt || new Date().toISOString(),
      productName: data.name || sharedMeta.productName || '',
      ruleVersion: sharedMeta.ruleVersion || version.value.rulesVersion
    }
    recallSharedReport(data.report, meta.value)
    resetShareLink()
    route.value = ''
    if (window.location.hash) {
      window.history.replaceState(null, '', window.location.pathname + window.location.search)
    }
    await nextTick()
    window.scrollTo({ top: 0, behavior: 'auto' })
    document.getElementById('report-top')?.focus({ preventScroll: true })
  } catch (err) {
    if (seq !== shareSeq) return
    shareError.value = err.message || '分享链接打不开'
  } finally {
    if (seq === shareSeq) shareLoading.value = false
  }
}

function recallSharedReport(json, m) {
  historyItems.value = upsertHistory(historyEntryFor(json, m, true))
}

function closeAdmin() {
  if (window.location.hash) {
    window.location.hash = ''
  } else {
    route.value = ''
    loadMeta()
  }
}

function onHashChange() {
  const previous = route.value
  route.value = parseRoute()
  window.scrollTo({ top: 0, behavior: 'auto' })

  if (route.value.startsWith('s/')) {
    openShared(route.value.slice(2))
    return
  }

  shareSeq += 1
  shareError.value = ''
  shareLoading.value = false
  if (previous === 'admin' && route.value !== 'admin') loadMeta()
}

function startNew() {
  invalidate()
  shareSeq += 1
  report.value = null
  meta.value = null
  delta.value = null
  recomputeError.value = ''
  error.value = ''
  resetShareLink()
  route.value = ''
  if (window.location.hash) {
    window.history.replaceState(null, '', window.location.pathname + window.location.search)
  }
  nextTick(() => {
    window.scrollTo({ top: 0, behavior: 'auto' })
    document.querySelector('.ta')?.focus({ preventScroll: true })
  })
}

/* ----------------------------- 页面切换 ----------------------------- */

function invalidate() {
  reqSeq += 1
  clearResearchPoll()
  researchPolls = 0
  researchPolling.value = false
  clearTimeout(reTimer)
  stopTimers()
  cancelVision()
  loading.value = false
  phase.value = 0
}

function backToInput() {
  invalidate()
  shareSeq += 1
  report.value = null
  meta.value = null
  delta.value = null
  recomputeError.value = ''
  copyFallback.value = ''
  resetShareLink()
  route.value = ''
  window.scrollTo({ top: 0, behavior: prefersReducedMotion() ? 'auto' : 'smooth' })
}

function startOver() {
  invalidate()
  shareSeq += 1
  report.value = null
  meta.value = null
  delta.value = null
  recomputeError.value = ''
  copyFallback.value = ''
  resetShareLink()
  text.value = ''
  productName.value = ''
  photo.value = null
  photoNote.value = ''
  photoError.value = false
  error.value = ''
  route.value = ''
  window.scrollTo({ top: 0 })
  showToast('已清空，可以换一瓶')
}

function useSample(sample) {
  invalidate()
  text.value = sample.text
  productName.value = String(sample.name).replace(/^示例\s*·\s*/, '')
  report.value = null
  meta.value = null
  delta.value = null
  error.value = ''
  photo.value = null
  photoNote.value = ''
  photoError.value = false
  resetShareLink()
  route.value = ''
  showToast(`已载入「${productName.value}」，点「开始解读」`)
  if (window.innerWidth < 980) {
    nextTick(() => {
      document.getElementById('intake-title')?.scrollIntoView({
        behavior: prefersReducedMotion() ? 'auto' : 'smooth',
        block: 'start'
      })
    })
  }
}

function clearText() {
  invalidate()
  text.value = ''
  photo.value = null
  photoNote.value = ''
  photoError.value = false
  error.value = ''
  showToast('已清空')
}

/* 用户在识别过程中自己改了文字，就以他改的为准，别让识别结果回来覆盖 */
function onTextUpdate(value) {
  if (recognizing.value) cancelVision()
  text.value = value
}

function fixWhitespace() {
  invalidate()
  text.value = splitByWhitespace(text.value)
  showToast('已按空格重新切分，检查一下成分是否完整')
}

async function pasteFromClipboard() {
  try {
    if (!navigator.clipboard || typeof navigator.clipboard.readText !== 'function') {
      throw new Error('unsupported')
    }
    const value = await navigator.clipboard.readText()
    if (!value || !value.trim()) {
      showToast('剪贴板里没有文字')
      return
    }
    invalidate()
    text.value = value.slice(0, MAX_TEXT)
    showToast('已从剪贴板粘贴')
  } catch {
    showToast('浏览器不允许读取剪贴板，点输入框后按 Ctrl+V / 长按粘贴')
  }
}

/* ----------------------------- 拍照识别 ----------------------------- */

let visionSeq = 0

function cancelVision() {
  visionSeq += 1
  recognizing.value = false
}

function pickFile() {
  fileInput.value?.click()
}

function compressImage(file, maxSide = 1600, quality = 0.86) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file)
    const img = new Image()

    img.onload = () => {
      URL.revokeObjectURL(url)
      const scale = Math.min(1, maxSide / Math.max(img.naturalWidth, img.naturalHeight))
      const width = Math.max(1, Math.round(img.naturalWidth * scale))
      const height = Math.max(1, Math.round(img.naturalHeight * scale))
      const canvas = document.createElement('canvas')
      canvas.width = width
      canvas.height = height
      const ctx = canvas.getContext('2d')
      if (!ctx) {
        reject(new Error('这台设备无法处理图片，请直接粘贴文本'))
        return
      }
      ctx.fillStyle = '#fff'
      ctx.fillRect(0, 0, width, height)
      ctx.drawImage(img, 0, 0, width, height)
      resolve(canvas.toDataURL('image/jpeg', quality))
    }

    img.onerror = () => {
      URL.revokeObjectURL(url)
      reject(new Error('这张图片打不开，换一张 JPG / PNG 试试'))
    }

    img.src = url
  })
}

function applyRecognizedProductName(value) {
  const name = String(value || '').trim().slice(0, 40)
  if (!name) return 'none'
  const current = productName.value.trim()
  if (!current) {
    productName.value = name
    return 'filled'
  }
  return current === name ? 'same' : 'kept'
}

async function onFileChange(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return

  if (!file.type.startsWith('image/')) {
    error.value = '请选择图片文件（JPG / PNG）'
    return
  }
  if (file.size > 15 * 1024 * 1024) {
    error.value = '图片超过 15MB，请换一张或先压缩'
    return
  }

  error.value = ''
  recognizing.value = true
  const vseq = ++visionSeq

  let dataUrl
  try {
    dataUrl = await compressImage(file)
  } catch (err) {
    if (vseq === visionSeq) {
      recognizing.value = false
      error.value = err.message
    }
    return
  }
  if (vseq !== visionSeq) return

  photo.value = { url: dataUrl, name: file.name }
  photoNote.value = ''
  photoError.value = false

  try {
    const json = await postJSON('/api/vision', { image: dataUrl, mime: 'image/jpeg' }, 90000)
    if (vseq !== visionSeq) return
    const recognized = String(json.text || '').trim()
    if (!recognized || recognized.includes('未识别到成分表')) {
      throw new Error('这张照片里没找到成分表，换个角度或拍清楚一点再试')
    }
    text.value = recognized.slice(0, MAX_TEXT)
    const recognizedName = String(json.productName || '').trim().slice(0, 40)
    const nameState = applyRecognizedProductName(recognizedName)
    const count = ingredientTokens(recognized).length
    if (nameState === 'filled') {
      photoNote.value = `已识别出 ${count} 个成分，产品名「${recognizedName}」已自动填入；核对后再开始解读`
      showToast('照片识别完成，产品名已自动填入')
    } else if (nameState === 'same') {
      photoNote.value = `已识别出 ${count} 个成分，产品名「${recognizedName}」与当前一致；核对后再开始解读`
      showToast('照片识别完成')
    } else if (nameState === 'kept') {
      photoNote.value = `已识别出 ${count} 个成分；识别到产品名「${recognizedName}」，保留当前名称`
      showToast('照片识别完成，已保留当前产品名')
    } else {
      photoNote.value = `已识别出 ${count} 个成分，核对文字后再开始解读`
      showToast('照片识别完成')
    }
  } catch (err) {
    if (vseq !== visionSeq) return
    photoError.value = true
    photoNote.value = err.message || '识别失败，可以重拍或直接粘贴文本'
  } finally {
    if (vseq === visionSeq) recognizing.value = false
  }
}

function removePhoto() {
  cancelVision()
  photo.value = null
  photoNote.value = ''
  photoError.value = false
}

/* 视觉识别失败时的出口：收起照片，把光标放到输入框里 */
function useTextInstead() {
  removePhoto()
  nextTick(() => {
    const ta = document.querySelector('.ta')
    if (!ta) return
    ta.scrollIntoView({ behavior: prefersReducedMotion() ? 'auto' : 'smooth', block: 'center' })
    ta.focus({ preventScroll: true })
  })
}

/* ----------------------------- 复制 / 打印 ----------------------------- */

async function copyReport() {
  const content = buildReportText(report.value, meta.value)
  if (!content) return

  try {
    if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
      await navigator.clipboard.writeText(content)
      showToast('已复制完整结论，可直接粘贴到聊天或文档')
      return
    }
  } catch {
    /* 非安全上下文或权限被拒，走兜底 */
  }

  const ta = document.createElement('textarea')
  ta.value = content
  ta.setAttribute('readonly', '')
  ta.style.cssText = 'position:fixed;opacity:0;pointer-events:none'
  document.body.appendChild(ta)
  ta.select()
  let ok = false
  try {
    ok = document.execCommand('copy')
  } catch {
    ok = false
  }
  ta.remove()

  if (ok) {
    showToast('已复制完整结论')
    return
  }

  // 局域网 http 下 Clipboard API 不可用，至少把文字全选好，让用户一次按键复制
  copyFallback.value = content
  await nextTick()
  const area = copyArea.value
  if (area) {
    area.focus()
    area.select()
    area.scrollTop = 0
  }
}

function selectCopyText() {
  const area = copyArea.value
  if (!area) return
  area.focus()
  area.select()
  area.scrollTop = 0
}

function printReport() {
  window.print()
}

/* ----------------------------- 初始化 ----------------------------- */

async function loadMeta() {
  backendDown.value = false
  const [cfg, sm, ver] = await Promise.allSettled([
    getJSON('/api/config'),
    getJSON('/api/samples'),
    getJSON('/api/version')
  ])
  if (cfg.status === 'fulfilled') {
    config.value = { ...config.value, ...cfg.value }
  } else {
    backendDown.value = true
  }
  if (sm.status === 'fulfilled' && Array.isArray(sm.value)) {
    samples.value = sm.value
  }
  if (ver.status === 'fulfilled' && ver.value) {
    version.value = { ...version.value, ...ver.value }
  }
}

async function applyDemo() {
  if (isAdmin.value || route.value !== '') return

  const params = new URLSearchParams(window.location.search)
  const raw = params.has('sample') ? params.get('sample') : params.has('demo') ? params.get('demo') : null
  if (raw === null || !samples.value.length) return

  const index = Math.min(Math.max(parseInt(raw, 10) || 1, 1), samples.value.length)
  const sample = samples.value[index - 1]
  text.value = sample.text
  productName.value = String(sample.name).replace(/^示例\s*·\s*/, '')
  profile.value = { skin: 'oily', sensitive: true, pregnant: false }
  await nextTick()
  analyze()
}

function onKeydown(event) {
  if (event.key === 'Escape' && copyFallback.value) {
    copyFallback.value = ''
    return
  }
  if (event.key === 'Escape' && share.open) {
    share.open = false
    return
  }
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
    event.preventDefault()
    if (!report.value) analyze()
  }
}

onMounted(async () => {
  restore()
  historyItems.value = loadHistory()
  await loadMeta()
  route.value = parseRoute()
  if (route.value.startsWith('s/')) {
    await openShared(route.value.slice(2))
  } else if (route.value === '') {
    applyDemo()
  }
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('hashchange', onHashChange)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('hashchange', onHashChange)
  clearTimeout(reTimer)
  clearResearchPoll()
  clearTimeout(saveTimer)
  clearTimeout(toastTimer)
  stopTimers()
})
</script>

<template>
  <div class="page" :class="{ 'has-report': !!report && !isAdmin, 'is-admin': isAdmin }">
    <template v-if="!isAdmin">
      <header class="site-head no-print">
        <a class="brand" href="#/" aria-label="成分镜首页">
          <span class="brand-mark" aria-hidden="true">
            <svg viewBox="0 0 32 32">
              <circle cx="16" cy="16" r="13.2" />
              <path d="M16 5.2c3.7 3.3 4.9 6.9 4.9 10.8s-1.2 7.5-4.9 10.8c-3.7-3.3-4.9-6.9-4.9-10.8S12.3 8.5 16 5.2Z" />
              <path d="M6 16h20" opacity=".45" />
            </svg>
          </span>
          <span class="brand-copy">
            <span class="logo serif">成分镜</span>
            <span class="tagline">{{ tagline }}</span>
          </span>
        </a>

        <nav class="site-nav" aria-label="主导航">
          <a href="#/" :class="{ on: route === '' }">首页</a>
          <a href="#/ingredients" :class="{ on: route === 'ingredients' }">成分库</a>
          <a href="#/method" :class="{ on: route === 'method' }">方法</a>
          <a href="#/history" :class="{ on: route === 'history' }">
            历史
            <span v-if="historyItems.length" class="nav-count">{{ historyItems.length }}</span>
          </a>
          <a href="#/about" :class="{ on: route === 'about' }">关于</a>
        </nav>

        <button type="button" class="head-cta" @click="startNew">开始解读</button>
      </header>

      <div v-if="backendDown" class="notice no-print" role="alert">
        <div class="notice-copy">
          <b>没连上本机服务</b>
          <span>分析需要后端在 8082 端口运行。启动 start-server.bat 后点重试。</span>
        </div>
        <button type="button" class="btn" @click="loadMeta">重试</button>
      </div>
    </template>

    <AdminView v-if="isAdmin" />

    <div v-else-if="shareLoading" class="page-state card">
      <i class="spinner"></i>
      正在打开分享报告…
    </div>

    <NotFoundView
      v-else-if="shareError"
      title="分享链接打不开"
      :message="shareError"
    />

    <IngredientsView v-else-if="route === 'ingredients'" :version="version" />
    <MethodView v-else-if="route === 'method'" :version="version" />
    <AboutView v-else-if="route === 'about'" :version="version" />
    <HistoryView
      v-else-if="route === 'history'"
      :entries="historyItems"
      :version="version"
      @open="openHistoryEntry"
      @remove="deleteHistoryEntry"
      @clear="clearAllHistory"
    />
    <NotFoundView v-else-if="isNotFound" />

    <template v-else-if="!report">
      <section class="hero">
        <p class="eyebrow">成分表 · 位置即含量</p>
        <h1 class="hero-title serif">广告会夸张，<em>成分表不会</em>。</h1>
        <p class="hero-sub">
          法规要求成分按含量从高到低排列。成分镜把这个顺序，翻译成「对你」的一句话结论：该避开什么、哪些功效只是概念添加。
        </p>
        <p class="hero-points">
          <span>按排位识别概念添加</span>
          <i aria-hidden="true">·</i>
          <span>按肤质 / 敏感 / 孕期重排风险</span>
          <i aria-hidden="true">·</i>
          <span>只读标签，不评价商品</span>
        </p>
      </section>

      <div class="intake-grid">
        <IntakePanel
          :model-value="text"
          :product-name="productName"
          :tokens="tokens"
          :issue="issue"
          :samples="samples"
          :vision-enabled="config.visionEnabled === true"
          :loading="loading"
          :recognizing="recognizing"
          :photo="photo"
          :photo-note="photoNote"
          :photo-error="photoError"
          @update:model-value="onTextUpdate"
          @update:product-name="productName = $event"
          @clear="clearText"
          @paste="pasteFromClipboard"
          @pick-file="pickFile"
          @fix-space="fixWhitespace"
          @sample="useSample"
          @remove-photo="removePhoto"
          @use-text="useTextInstead"
        />

        <aside class="side">
          <ProfilePanel
            v-model="profile"
            :loading="loading"
            :phase="phase"
            :steps="STEPS"
            :error="error"
            :can-submit="canAnalyze"
            :has-text="!!text.trim()"
            :recognizing="recognizing"
            @submit="analyze()"
            @retry="retryAnalyze"
          />
        </aside>
      </div>
    </template>

    <ReportView
      v-else
      :data="report"
      :profile="profile"
      :meta="meta"
      :version="version"
      :recomputing="loading"
      :delta="delta"
      :error="recomputeError"
      @back="backToInput"
      @restart="startOver"
      @copy="copyReport"
      @print="printReport"
      @share="shareReport"
      @rename="renameReport"
      @update:profile="updateProfile"
      @dismiss-delta="delta = null"
      @retry="analyze({ silent: true })"
      @refresh-research="pollResearch({ manual: true })"
    />

    <template v-if="!isAdmin">
      <footer class="site-foot no-print">
        <nav class="foot-links" aria-label="页脚导航">
          <a href="#/method">解读方法</a>
          <a href="#/ingredients">成分库</a>
          <a href="#/history">历史报告</a>
          <a href="#/about">关于与隐私</a>
          <a href="#/admin">管理控制台</a>
        </nav>
        <p>本工具只做标签信息解读，不评价商品优劣，不构成医疗或用药建议。</p>
        <p class="foot-meta">
          成分镜 v{{ version.version || '1.0.0' }} · 规则库 {{ version.rulesVersion || '2026.10' }}
          · 成分库 {{ version.librarySize || config.librarySize || 60 }} 条
          · 数据更新 {{ version.libraryVersion || '2026.10' }}
        </p>
      </footer>

      <div v-if="route === '' && !report" class="mobile-cta no-print">
        <div class="mc-top">
          <span>{{ profileLabel(profile) }}</span>
          <span v-if="recognizing">正在识别照片</span>
          <span v-else-if="!text.trim()">还没有成分表</span>
          <span v-else>{{ tokens.length }} 个成分</span>
        </div>
        <button
          type="button"
          class="mc-btn"
          :class="{ loading: loading || recognizing }"
          :disabled="!canAnalyze"
          :aria-busy="loading || recognizing"
          @click="analyze()"
        >
          <template v-if="recognizing">
            <i class="spinner"></i>正在识别照片…
          </template>
          <template v-else-if="loading">
            <i class="spinner"></i>{{ STEPS[phase] }}…
          </template>
          <template v-else>开始解读</template>
        </button>
      </div>
    </template>

    <input
      id="photo-input"
      ref="fileInput"
      class="sr-only"
      type="file"
      accept="image/*"
      aria-label="选择或拍摄成分表照片"
      @change="onFileChange"
    />

    <p class="sr-only" role="status" aria-live="polite">{{ status }}</p>

    <transition name="sheet">
      <div v-if="!isAdmin && copyFallback" class="mask no-print" @click.self="copyFallback = ''">
        <div class="sheet" role="dialog" aria-modal="true" aria-labelledby="copy-title">
          <div class="sheet-head">
            <h3 id="copy-title">复制报告</h3>
            <button type="button" class="sheet-x" aria-label="关闭" @click="copyFallback = ''">
              ×
            </button>
          </div>
          <p class="sheet-hint">
            这段文字已经全选。按 Ctrl+C / ⌘C 复制；手机上长按文字选「拷贝」。
          </p>
          <textarea
            ref="copyArea"
            class="sheet-ta"
            readonly
            :value="copyFallback"
            @focus="$event.target.select()"
          ></textarea>
          <div class="sheet-actions">
            <button type="button" class="btn" @click="copyFallback = ''">关闭</button>
            <button type="button" class="btn primary" @click="selectCopyText">重新全选</button>
          </div>
        </div>
      </div>
    </transition>

    <transition name="sheet">
      <div v-if="!isAdmin && share.open" class="mask no-print" @click.self="closeShare">
        <div class="sheet share-sheet" role="dialog" aria-modal="true" aria-labelledby="share-title">
          <div class="sheet-head">
            <h3 id="share-title">分享这份报告</h3>
            <button type="button" class="sheet-x" aria-label="关闭" @click="closeShare">×</button>
          </div>

          <template v-if="share.error">
            <p class="share-error" role="alert">{{ share.error }}</p>
            <p class="sheet-hint">可以先把报告全文复制出去，或稍后再试。</p>
            <div class="sheet-actions">
              <button type="button" class="btn" @click="closeShare">关闭</button>
              <button type="button" class="btn primary" @click="copyFromShare">复制报告全文</button>
            </div>
          </template>

          <template v-else>
            <p class="sheet-hint">
              {{ meta?.productName || '未命名产品' }} · {{ meta?.id || '' }}<br />
              链接包含你填写的档案与解读结果，拿到链接的人都能查看。
            </p>
            <div class="share-link">
              <input
                ref="shareLinkInput"
                :value="share.link"
                readonly
                aria-label="分享链接"
                @focus="$event.target.select()"
              />
              <button type="button" class="btn primary" @click="copyShareLink">
                {{ share.copied ? '已复制' : '复制链接' }}
              </button>
            </div>
            <p class="share-note">
              链接使用你当前的访问地址；跨设备查看请先用局域网 IP 打开本应用再分享。
              报告保存在本机服务，30 天后自动清理；不要把含孕期信息的链接发到公开群。
            </p>
            <div class="sheet-actions">
              <button type="button" class="btn" @click="closeShare">关闭</button>
              <button type="button" class="btn" @click="copyFromShare">复制报告全文</button>
            </div>
          </template>
        </div>
      </div>
    </transition>

    <transition name="toast">
      <div v-if="!isAdmin && toast" class="toast" role="status">{{ toast }}</div>
    </transition>
  </div>
</template>

<style scoped>
.page {
  max-width: 1040px;
  margin: 0 auto;
  padding: 30px 22px 72px;
}

.page.is-admin {
  max-width: none;
  min-height: 100vh;
  padding: 0;
  background: #F4F1EA;
}

/* ----------------------------- 顶栏 ----------------------------- */
.site-head {
  display: flex;
  align-items: center;
  gap: 18px;
  flex-wrap: wrap;
  margin-bottom: 22px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
  color: inherit;
  text-decoration: none;
}

.brand-mark {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  border-radius: 13px;
  background: linear-gradient(145deg, #2C7C69, var(--accent-ink));
  color: #fff;
  box-shadow: 0 10px 22px -16px rgba(20, 76, 64, .95);
}

.brand-mark svg {
  width: 25px;
  height: 25px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.15;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.brand-copy { display: flex; flex-direction: column; }

.logo { font-size: 22px; font-weight: 700; line-height: 1.25; letter-spacing: .05em; }

.tagline { font-size: 11.5px; line-height: 1.6; color: var(--ink3); letter-spacing: .02em; }

.site-nav {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-left: auto;
  overflow-x: auto;
  scrollbar-width: none;
}

.site-nav::-webkit-scrollbar { display: none; }

.site-nav a {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  padding: 6px 10px;
  border-radius: 8px;
  color: var(--ink2);
  font-size: 12.5px;
  text-decoration: none;
  white-space: nowrap;
  transition: background .16s, color .16s;
}

.site-nav a:hover { background: rgba(31, 107, 90, .06); color: var(--accent-ink); }

.site-nav a.on { background: var(--accent-soft); color: var(--accent-ink); font-weight: 600; }

.nav-count {
  padding: 1px 5px;
  border-radius: 999px;
  background: rgba(31, 107, 90, .14);
  font-size: 9.5px;
  line-height: 1.5;
  font-weight: 600;
}

.head-cta {
  flex-shrink: 0;
  padding: 9px 16px;
  border-radius: 9px;
  background: var(--ink);
  color: #fff;
  font-size: 12.5px;
  font-weight: 600;
  letter-spacing: .02em;
  transition: background .16s;
}

.head-cta:hover { background: #2B2722; }

/* ----------------------------- 提示条 ----------------------------- */
.notice {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  padding: 11px 14px;
  border: 1px solid var(--danger-line);
  border-radius: 12px;
  background: var(--danger-soft);
}

.notice-copy { flex: 1; display: flex; flex-direction: column; gap: 1px; }

.notice-copy b { font-size: 13px; color: var(--danger); }

.notice-copy span { font-size: 12px; line-height: 1.7; color: #7C3A2E; }

/* ----------------------------- 首屏 ----------------------------- */
.hero { position: relative; max-width: 730px; padding: 2px 0 20px; }

.hero-title {
  position: relative;
  z-index: 0;
  margin: 8px 0 9px;
  font-size: 38px;
  font-weight: 600;
  line-height: 1.3;
  letter-spacing: -.025em;
}

.hero-title em {
  position: relative;
  z-index: 0;
  font-style: normal;
  color: var(--accent);
  white-space: nowrap;
}

.hero-title em::after {
  content: '';
  position: absolute;
  left: -2px;
  right: -2px;
  bottom: 3px;
  height: 9px;
  z-index: -1;
  border-radius: 4px;
  background: linear-gradient(90deg, rgba(31, 107, 90, .2), rgba(31, 107, 90, .04));
}

.hero-sub { max-width: 650px; font-size: 14px; line-height: 1.9; color: var(--ink2); }

.hero-points {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 11px;
  font-size: 11.5px;
  color: var(--ink3);
}

.hero-points i { font-style: normal; opacity: .45; }

/* ----------------------------- 布局 ----------------------------- */
.intake-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(310px, .92fr);
  gap: 16px;
  align-items: start;
}

.side { position: sticky; top: 20px; }

/* ----------------------------- 页脚 ----------------------------- */
.site-foot {
  margin-top: 36px;
  padding-top: 18px;
  border-top: 1px solid var(--line);
  text-align: center;
}

.foot-links {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}

.foot-links a { color: var(--ink2); font-size: 11.5px; text-decoration: none; }

.foot-links a:hover { color: var(--accent); }

.site-foot p { font-size: 11.5px; line-height: 1.9; color: var(--ink3); }

.foot-meta { opacity: .78; }

/* ----------------------------- 页面状态与分享 ----------------------------- */
.page-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  max-width: 560px;
  margin: 48px auto;
  padding: 44px 20px;
  font-size: 13px;
  color: var(--ink3);
}

.share-sheet { width: min(600px, 100%); }

.share-link { display: flex; align-items: center; gap: 8px; margin-top: 6px; }

.share-link input {
  flex: 1;
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: #FCFBF7;
  color: var(--ink2);
  font-size: 12px;
  outline: none;
}

.share-link input:focus { border-color: var(--accent); box-shadow: var(--focus); }

.share-note { margin-top: 8px; font-size: 11px; line-height: 1.7; color: var(--ink3); }

.share-error {
  padding: 10px 12px;
  border: 1px solid var(--danger-line);
  border-radius: 9px;
  background: var(--danger-soft);
  color: #7C3A2E;
  font-size: 12.5px;
  line-height: 1.7;
}

/* ----------------------------- 移动端底栏 ----------------------------- */
.mobile-cta { display: none; }

/* ----------------------------- 复制兜底弹层 ----------------------------- */
.mask {
  position: fixed;
  inset: 0;
  z-index: 70;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 18px;
  background: rgba(26, 23, 19, .44);
}

.sheet {
  display: flex;
  flex-direction: column;
  width: min(560px, 100%);
  max-height: 86vh;
  padding: 18px;
  border-radius: 16px;
  background: var(--card);
  box-shadow: var(--shadow-md);
}

.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.sheet-head h3 { font-size: 16px; font-weight: 600; }

.sheet-x {
  width: 28px;
  height: 28px;
  border-radius: 7px;
  color: var(--ink3);
  font-size: 18px;
  line-height: 1;
}

.sheet-x:hover { background: #F3F0E8; color: var(--ink); }

.sheet-hint { margin-bottom: 10px; font-size: 12px; line-height: 1.7; color: var(--ink3); }

.sheet-ta {
  flex: 1;
  width: 100%;
  min-height: 220px;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #FCFBF7;
  color: var(--ink2);
  font-size: 12.5px;
  line-height: 1.85;
  resize: none;
  outline: none;
}

.sheet-ta:focus { border-color: var(--accent); box-shadow: var(--focus); }

.sheet-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 12px; }

.sheet-enter-active,
.sheet-leave-active { transition: opacity .18s ease; }

.sheet-enter-from,
.sheet-leave-to { opacity: 0; }

.sheet-enter-active .sheet,
.sheet-leave-active .sheet { transition: transform .18s ease; }

.sheet-enter-from .sheet,
.sheet-leave-to .sheet { transform: translateY(8px); }

/* ----------------------------- 提示 ----------------------------- */
.toast {
  position: fixed;
  left: 50%;
  bottom: 26px;
  z-index: 60;
  max-width: min(88vw, 460px);
  padding: 9px 16px;
  border-radius: 10px;
  background: rgba(26, 23, 19, .93);
  color: #fff;
  font-size: 12.5px;
  line-height: 1.6;
  text-align: center;
  transform: translateX(-50%);
  box-shadow: var(--shadow-md);
}

.toast-enter-active,
.toast-leave-active { transition: opacity .2s ease, transform .2s ease; }

.toast-enter-from,
.toast-leave-to { opacity: 0; transform: translate(-50%, 8px); }

@media (max-width: 979px) {
  .page { padding: 20px 16px 108px; }
  .page.has-report { padding-bottom: 56px; }

  .site-head { margin-bottom: 16px; gap: 10px; }

  .brand { flex: 1; }

  .brand-mark { width: 36px; height: 36px; border-radius: 11px; }

  .logo { font-size: 20px; }

  .tagline { display: none; }

  .head-cta { display: none; }

  .site-nav {
    order: 3;
    width: 100%;
    margin-left: 0;
    padding-top: 8px;
    border-top: 1px solid var(--line);
  }

  .hero { padding: 2px 0 20px; }

  .hero-title { font-size: 31px; margin: 8px 0 10px; }

  .hero-sub { font-size: 13.5px; line-height: 1.9; }

  .hero-points { gap: 8px; margin-top: 10px; }

  .intake-grid { grid-template-columns: 1fr; }

  .side { position: static; }

  .mobile-cta {
    display: block;
    position: fixed;
    left: 0;
    right: 0;
    bottom: 0;
    z-index: 40;
    padding: 8px 14px calc(8px + env(safe-area-inset-bottom));
    border-top: 1px solid var(--line);
    background: var(--bg);
  }

  .mc-top {
    display: flex;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 5px;
    padding: 0 2px;
    font-size: 10.5px;
    color: var(--ink3);
  }

  .mc-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 7px;
    width: 100%;
    padding: 13px;
    border-radius: 11px;
    background: var(--ink);
    color: #fff;
    font-size: 15px;
    font-weight: 600;
    letter-spacing: .04em;
    transition: opacity .16s;
  }

  .mc-btn:disabled { opacity: .4; cursor: not-allowed; }

  .mc-btn.loading:disabled { opacity: 1; cursor: progress; }

  .mc-btn .spinner { width: 13px; height: 13px; }

  .toast { bottom: 96px; }
}

@media (max-width: 520px) {
  .site-nav a { padding: 6px 8px; font-size: 12px; }
  .share-link { flex-wrap: wrap; }
  .share-link .btn { width: 100%; }
}
</style>
