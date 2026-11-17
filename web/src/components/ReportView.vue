<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AnimatedNumber from './AnimatedNumber.vue'
import IngredientDetail from './IngredientDetail.vue'
import LadderChart from './LadderChart.vue'
import CompareBars from './CompareBars.vue'
import StreamText from './StreamText.vue'
import ProfileControls from './ProfileControls.vue'
import { profileLabel } from '../lib/text.js'
import { reportView } from '../lib/report.js'
import { formatDateTime } from '../lib/format.js'
import { getJSON } from '../lib/http.js'
import { speak, stopSpeaking, synthesisSupported } from '../lib/speech.js'

const props = defineProps({
  data: { type: Object, required: true },
  profile: { type: Object, required: true },
  meta: { type: Object, default: null },
  version: { type: Object, default: null },
  recomputing: { type: Boolean, default: false },
  delta: { type: Object, default: null },
  error: { type: String, default: '' }
})

const emit = defineEmits(['back', 'restart', 'copy', 'print', 'share', 'rename', 'update:profile', 'dismiss-delta', 'retry', 'refresh-research'])

const drawerOpen = ref(false)
const library = ref([])
const detail = ref(null)

const view = computed(() => reportView(props.data))

/* 报告或档案一变，解释就该重写：换 key 让流式组件重新挂载 */
const explainKey = computed(() =>
  [
    props.data.verdict && props.data.verdict.level ? props.data.verdict.level : '',
    props.data.total || 0,
    props.data.matched || 0,
    props.data.unknownCount || 0,
    Number(props.data.researchDone || 0),
    props.profile.skin || '',
    props.profile.sensitive ? 1 : 0,
    props.profile.pregnant ? 1 : 0
  ].join('|')
)

const flags = computed(() => props.data.flags || [])
const highFlags = computed(() => flags.value.filter((f) => f.level === 'high'))
const medFlags = computed(() => flags.value.filter((f) => f.level === 'medium'))
const infoFlags = computed(() => flags.value.filter((f) => f.level === 'info'))

const actives = computed(() => props.data.actives || [])
const insights = computed(() => props.data.insights || [])
const unknown = computed(() => props.data.unknown || [])
const researched = computed(() => props.data.researched || [])
const researchQueued = computed(() => Number(props.data.researchQueued || 0) + Number(props.data.researchRunning || 0))
const researchFailed = computed(() => Number(props.data.researchFailed || 0))
const researchReview = computed(() => Number(props.data.researchReview || 0))
const failedReason = computed(() => String(props.data.researchFailedReason || ''))

/** 未收录成分的名字：研究期间用来告诉用户「正在查哪些」 */
const unknownNames = computed(() => {
  const list = props.data?.unknown || props.data?.unresolved || []
  if (!Array.isArray(list)) return []
  return list.map((item) => (item && typeof item === 'object' ? item.name || '' : String(item))).filter(Boolean)
})

// 「还有几个没查完」以本次报告里真正没解析出来的成分为准。
// 服务端的 queued/running 只统计这一轮新入队的，上一轮还在跑的那条不在里面，
// 于是出现过「有 2 个不在库里」却列了 3 行的自相矛盾。
const pendingCount = computed(() => Math.max(researchQueued.value, unknownNames.value.length))

/**
 * 研究还没跑完时，先不给结论：只显示进度。
 * 研究 API 没配置时不算「研究中」，否则页面会永远卡在进度页。
 */
const researching = computed(() => {
  if (props.data?.researchEnabled === false) return false
  return pendingCount.value > 0
})

/* 等了几秒：让用户看到页面是活的，而不是一张静止的图 */
const elapsed = ref(0)
let elapsedTimer = 0
watch(
  researching,
  (on) => {
    window.clearInterval(elapsedTimer)
    elapsed.value = 0
    if (!on) return
    elapsedTimer = window.setInterval(() => { elapsed.value += 1 }, 1000)
  },
  { immediate: true }
)
onBeforeUnmount(() => {
  window.clearInterval(elapsedTimer)
  if (speaking.value) stopSpeakingNow()
})

const progressWidth = computed(() => {
  const total = Math.max(1, Number(props.data?.total) || 0)
  const done = Math.max(0, total - pendingCount.value - researchReview.value)
  return Math.max(4, Math.min(100, Math.round((done / total) * 100)))
})
const researchEnabled = computed(() => props.data.researchEnabled !== false)
const chartMode = ref('bars')

/** 每个成分的风险等级：来自 flags，用来在对比图上打红黄点 */
const riskByName = computed(() => {
  const map = new Map()
  for (const flag of flags.value || []) {
    if (flag.level !== 'high' && flag.level !== 'medium') continue
    const names = (flag.ingredients && flag.ingredients.length)
      ? flag.ingredients
      : (flag.details || []).map((d) => d && d.name)
    for (const name of names) {
      if (name && !map.has(name)) map.set(name, flag.level)
    }
  }
  return map
})

const sequence = computed(() =>
  props.data.sequence && props.data.sequence.length ? props.data.sequence : props.data.ladder || []
)

const unrecognized = computed(() => view.value.unrecognized)

const conceptCount = computed(() => actives.value.filter((a) => a.tier === 'tail').length)

/** 同一成分可能同时命中两条规则（孕期 + 敏感肌），统计要按成分去重 */
function distinctCount(groups) {
  const names = new Set()
  for (const f of groups) {
    for (const n of f.ingredients || []) if (n) names.add(n)
  }
  return names.size
}

const highCount = computed(() => distinctCount(highFlags.value))
const riskGroups = computed(() => highFlags.value.length + medFlags.value.length)

/* 结论本身就是「认得太少」时，不再补一句「没有需要回避的成分」 */
const showRisks = computed(
  () => !unrecognized.value && (riskGroups.value > 0 || view.value.level !== 'insufficient')
)

const profileText = computed(() => profileLabel(props.profile))

const totalText = computed(() => {
  const total = props.data.total ?? sequence.value.length
  const miss = props.data.unknownCount ?? unknown.value.length
  return `共 ${total} 个成分${miss ? ` · ${miss} 个待补全` : ''}`
})

const stats = computed(() => [
  {
    label: '识别出的成分',
    value: props.data.matched ?? 0,
    tip: '知识库命中的成分数；待补全的不计入',
    tone: ''
  },
  { label: '功效成分', value: actives.value.length, tip: '配方中归类为「功效」的成分', tone: '' },
  {
    label: '疑似概念添加',
    value: conceptCount.value,
    tip: '排在第 11 位之后的功效成分，通常不是主要功效来源',
    tone: conceptCount.value > 0 ? 'hot' : ''
  },
  {
    label: '建议避开',
    value: highCount.value,
    tip: '按你当前档案判定为高风险的成分数',
    tone: highCount.value > 0 ? 'danger' : ''
  }
])

const anchors = computed(() => {
  const list = [{ id: 'verdict', label: '结论' }]
  if (!unrecognized.value) {
    if (sequence.value.length) list.push({ id: 'ladder', label: '成分对比' })
    if (showRisks.value) list.push({ id: 'risks', label: '风险' })
    if (actives.value.length) list.push({ id: 'actives', label: '功效' })
    if (insights.value.length) list.push({ id: 'insights', label: '发现' })
    if (infoFlags.value.length) list.push({ id: 'regs', label: '法规' })
  }
  return list
})

const reduced =
  typeof window.matchMedia === 'function' &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches

/* 朗读结论：用浏览器自带的 TTS，不上传音频；不支持就隐藏入口 */
const canSpeak = ref(synthesisSupported())
const speaking = ref(false)
let stopSpeak = null

function stopSpeakingNow() {
  if (stopSpeak) {
    try {
      stopSpeak()
    } catch {
      /* 忽略 */
    }
    stopSpeak = null
  }
  stopSpeaking()
  speaking.value = false
}

function toggleSpeak() {
  if (speaking.value) {
    stopSpeakingNow()
    return
  }
  const content = [view.value.headline, view.value.summary].filter(Boolean).join('。')
  stopSpeak = speak(content, {
    onEnd: () => {
      speaking.value = false
      stopSpeak = null
    }
  })
  if (stopSpeak) speaking.value = true
}

/* 报告或档案一变，正在读的那段就过期了，先停掉 */
watch(explainKey, () => {
  if (speaking.value) stopSpeakingNow()
})

function jump(id) {
  const el = document.getElementById(id)
  if (!el) return
  el.scrollIntoView({ behavior: reduced ? 'auto' : 'smooth', block: 'start' })
}

function marker(active) {
  const total = props.data.total ?? sequence.value.length
  if (total <= 1) return 0
  return Math.min(100, (active.index / (total - 1)) * 100)
}

/* 消息本身已经以类型开头时（「敏感肌建议避开：…」），不再重复挂一个类型标签 */
function showType(flag) {
  const type = flag?.type || ''
  const message = flag?.message || ''
  return !!type && !message.startsWith(type)
}

function onProfileChange(next) {
  emit('update:profile', next)
}

function openIngredient(name, context = {}) {
  if (name && typeof name === 'object') {
    context = name
    name = name.name
  }
  const clean = String(name || '').trim()
  if (!clean || clean === '待补全') return
  const item = library.value.find(
    (row) => row.name === clean || (row.aliases || []).includes(clean)
  )
  // 从风险标签点进来时只带了名字：排位必须回到本次报告里查，
  // 否则详情页会拿不到 index，模型就会把「没有排位」说成「排在第一位」。
  const row = sequence.value.find((r) => r && r.name === clean)
  const merged = { ...(row || {}) }
  for (const [k, v] of Object.entries(context)) {
    if (v !== undefined && v !== null && v !== '') merged[k] = v
  }
  merged.name = clean
  detail.value = {
    // 点开的是「CI 77891」这类别名时，标题保留用户看到的名字，
    // 标准名与其余别名交给详情卡的「别名」一栏，别把标题悄悄换成「二氧化钛」。
    item: item
      ? { ...item, name: clean }
      : {
          name: clean,
          role: merged.role || '',
          cat: merged.cat || '',
          function: merged.fn || merged.comment || '',
          aliases: []
        },
    // 成分库里有没有这条记录：没有就不能说「没有特别的风险」，那是两回事
    known: !!item,
    context: merged
  }
}

onMounted(async () => {
  try {
    const data = await getJSON('/api/ingredients', 10000)
    library.value = Array.isArray(data.items) ? data.items : []
  } catch {
    /* 成分库拉不到时，详情卡仍可用报告里的基础信息降级展示 */
  }
})
</script>

<template>
  <article id="report-top" class="report" tabindex="-1">
    <!-- 吸顶工具条：结论、档案、动作常驻，避免长报告里迷失 -->
    <div class="bar no-print">
      <div class="bar-inner">
        <div class="bar-left">
          <span class="badge" :class="view.cls">{{ view.label }}</span>
          <span class="bar-profile">{{ profileText }}</span>
          <span v-if="recomputing" class="recalc"><i class="spinner"></i>重算</span>
        </div>

        <div v-if="!researching" class="bar-actions">
          <button
            type="button"
            class="bar-btn"
            :aria-expanded="drawerOpen"
            @click="drawerOpen = !drawerOpen"
          >
            调整档案
          </button>
          <button type="button" class="bar-btn" @click="emit('share')">分享</button>
          <button type="button" class="bar-btn" @click="emit('copy')">复制结论</button>
          <button
            v-if="canSpeak"
            type="button"
            class="bar-btn"
            :aria-pressed="speaking"
            @click="toggleSpeak"
          >
            {{ speaking ? '停止朗读' : '朗读' }}
          </button>
          <button type="button" class="bar-btn hide-sm" @click="emit('print')">打印</button>
          <button type="button" class="bar-btn" @click="emit('back')">修改成分表</button>
        </div>
        <span v-else class="bar-researching"><i class="pulse"></i>正在研究未收录成分</span>
      </div>

      <div v-if="drawerOpen" class="drawer">
        <ProfileControls compact :model-value="profile" @update:model-value="onProfileChange" />
        <p class="drawer-note">改动后立即重算，结论和风险会跟着变。</p>
      </div>

      <nav v-if="!researching && anchors.length > 1" class="anchors" aria-label="报告目录">
        <button v-for="a in anchors" :key="a.id" type="button" @click="jump(a.id)">
          {{ a.label }}
        </button>
      </nav>

      <i v-if="recomputing" class="bar-line" aria-hidden="true"></i>
    </div>

    <div v-if="error" class="bar-error" role="alert">
      <span>{{ error }}</span>
      <button type="button" class="btn" @click="emit('retry')">重试</button>
    </div>

    <!-- 研究未完成：只给进度，先不给结论 -->
    <section v-if="researching" class="researching reveal" aria-live="polite">
      <p class="eyebrow">Agent 正在补全成分库</p>
      <h1 class="rh serif">正在把这瓶的成分查清楚</h1>
      <p class="rsub">
        有 {{ pendingCount }} 个成分不在成分库里。Agent 正在逐个查证并自动收录，
        全部完成之前先不给结论 —— 少一个成分就下的结论，不如不给。
      </p>

      <div class="rbar" role="progressbar" :aria-valuenow="view.total - pendingCount" :aria-valuemin="0" :aria-valuemax="view.total">
        <i :style="{ width: progressWidth + '%' }"></i>
      </div>
      <div class="rmeta">
        <span><b>{{ view.total - pendingCount }}</b> / {{ view.total }} 已就绪</span>
        <span v-if="pendingCount" class="pending">
          <i class="ring"></i>{{ pendingCount }} 个研究中
        </span>
        <span class="elapsed">已等待 {{ elapsed }}s</span>
        <span class="auto">完成后自动显示结论，不用刷新</span>
      </div>

      <p v-if="elapsed >= 60" class="rslow">
        这个成分比较生僻，Agent 正在多查几次（查库、查限量、比对同类）。可以先去忙别的，回来就能看到结果。
      </p>

      <ul v-if="unknownNames.length" class="rlist">
        <li v-for="name in unknownNames.slice(0, 8)" :key="name">
          <i class="ring small"></i>
          <span class="rname">{{ name }}</span>
          <span class="rtag">查证中</span>
        </li>
        <li v-if="unknownNames.length > 8" class="rmore">还有 {{ unknownNames.length - 8 }} 个…</li>
      </ul>

      <p class="rfoot">
        收录结果会在成分库标记为「AI 研究」，你可以随时在管理台复核。
        <button type="button" class="vnote-link" @click="emit('refresh-research')">没等到更新？立即重新检查</button>
      </p>
    </section>

    <div v-else class="report-body">
      <div class="report-meta reveal" style="--i: 0">
        <div class="rm-main">
          <span class="rm-label">产品</span>
          <input
            class="rm-name"
            :value="meta?.productName || ''"
            maxlength="40"
            placeholder="未命名产品"
            aria-label="产品名"
            @change="emit('rename', $event.target.value)"
          />
          <span class="rm-id">编号 {{ meta?.id || '—' }}</span>
          <span v-if="meta?.createdAt" class="rm-time">{{ formatDateTime(meta.createdAt) }}</span>
          <span v-if="meta?.revisedAt" class="rm-time">重算于 {{ formatDateTime(meta.revisedAt) }}</span>
        </div>
        <span class="rm-rules">规则库 {{ meta?.ruleVersion || version?.rulesVersion || '2026.10' }}</span>
      </div>

      <section id="verdict" class="verdict reveal" :class="view.cls" style="--i: 0">
        <div class="vtop">
          <span class="vtag">{{ view.label }}</span>
          <span class="vmeta">{{ profileText }}<i>·</i>{{ totalText }}</span>
        </div>
        <h1 class="vhead serif">{{ view.headline }}</h1>
        <StreamText
          v-if="!unrecognized && view.level !== 'insufficient'"
          :key="explainKey"
          class="vsum"
          url="/api/explain/stream"
          :payload="{ report: data, profile: profile }"
          :fallback="view.summary"
        />
        <p v-else class="vsum">{{ view.summary }}</p>

        <p v-if="view.lowCoverage && view.level !== 'insufficient'" class="vnote">
          共 {{ view.total }} 个成分，只认出 {{ view.matched }} 个，结论可能不完整。
        </p>

        <p v-if="researched.length" class="vnote fill">
          AI 已补全 {{ researched.length }} 个成分并加入待研究库，报告已按新数据重算。
        </p>

        <div v-if="researchQueued || researchReview || researchFailed" class="research-status">
          <p v-if="researchQueued" class="vnote research">
            {{ researchQueued }} 个成分已进入 AI 研究队列{{ researchEnabled ? '，完成后报告会自动刷新' : '，等待研究 API 配置' }}。
            <button type="button" class="vnote-link" @click="emit('refresh-research')">立即刷新</button>
          </p>
          <p v-if="researchReview" class="vnote review">
            {{ researchReview }} 个成分已有研究草稿，还没写进成分库 —— 到管理台确认后会参与下一次分析。
          </p>
          <p v-if="researchFailed" class="vnote research-fail">
            {{ researchFailed }} 个成分这次没研究成功<template v-if="failedReason">（{{ failedReason }}）</template>。
            点「立即刷新」会重新排队；连续失败的可以稍后再来。
          </p>
        </div>

        <div v-if="delta" class="delta" :class="'delta-' + delta.tone" role="status">
          <div class="delta-copy">
            <b>{{ delta.title }}</b>
            <span>{{ delta.detail }}</span>
          </div>
          <button
            type="button"
            class="delta-x"
            aria-label="关闭这处变化提示"
            @click="emit('dismiss-delta')"
          >
            ×
          </button>
        </div>
      </section>

      <section v-if="!unrecognized" class="stats reveal" style="--i: 1" aria-label="报告概览">
        <div v-for="s in stats" :key="s.label" class="stat" :class="s.tone" :title="s.tip">
          <div class="num"><AnimatedNumber :value="s.value" /></div>
          <div class="slabel">{{ s.label }}</div>
        </div>
      </section>

      <section v-if="!unrecognized && sequence.length" id="ladder" class="card reveal" style="--i: 2">
        <div class="sec-head">
          <div class="sec-copy">
            <span class="eyebrow">位置即含量</span>
            <h2 class="h2 serif">成分对比</h2>
          </div>
          <div class="view-switch no-print" role="tablist" aria-label="切换视图">
            <button
              type="button"
              role="tab"
              :aria-selected="chartMode === 'bars'"
              :class="{ on: chartMode === 'bars' }"
              @click="chartMode = 'bars'"
            >对比图</button>
            <button
              type="button"
              role="tab"
              :aria-selected="chartMode === 'ladder'"
              :class="{ on: chartMode === 'ladder' }"
              @click="chartMode = 'ladder'"
            >梯队</button>
          </div>
        </div>
        <p class="hint">
          <template v-if="chartMode === 'bars'">
            每条的长度是相对排位，颜色是成分角色，红黄标记是你档案里的冲突项 —— 一眼能看出这瓶是什么构成、该避什么。
          </template>
          <template v-else>
            法规要求成分按含量从高到低排列，所以「排在第几位」本身就是信息。虚线框是待补全的成分，排位仍按原顺序保留。
          </template>
        </p>
        <p v-if="data.truncated" class="hint warn">
          成分表超过 150 个，这次只分析前 150 个。
        </p>
        <CompareBars
          v-if="chartMode === 'bars'"
          :items="sequence"
          :risks="riskByName"
          :total="Number(data.total) || sequence.length"
          @select="openIngredient"
        />
        <LadderChart v-else :items="sequence" @select="openIngredient" />
      </section>

      <section v-if="showRisks" id="risks" class="card reveal" style="--i: 3">
        <div class="sec-head">
          <div class="sec-copy">
            <span class="eyebrow">按你的档案</span>
            <h2 class="h2 serif">需要你注意的</h2>
          </div>
          <span v-if="riskGroups" class="sec-meta">{{ riskGroups }} 条提示</span>
        </div>

        <template v-if="riskGroups">
          <div v-if="highFlags.length" class="fgroup">
            <div class="gtitle danger">建议避开</div>
            <article v-for="(f, i) in highFlags" :key="'h' + i" class="flag high">
              <header class="flag-head">
                <span v-if="showType(f)" class="flag-type">{{ f.type }}</span>
                <h3 class="fmsg">{{ f.message }}</h3>
              </header>
              <div v-if="(f.ingredients || []).length > 1" class="names">
                <span
                  v-for="n in f.ingredients"
                  :key="n"
                  class="nm ing-link"
                  role="button"
                  tabindex="0"
                  title="查看成分详情"
                  @click.stop="openIngredient(n)"
                  @keydown.enter.stop="openIngredient(n)"
                  @keydown.space.prevent.stop="openIngredient(n)"
                >{{ n }}</span>
              </div>
              <p class="fbasis">{{ f.basis }}</p>
              <ul v-if="f.details && f.details.length" class="notes">
                <li v-for="d in f.details" :key="d.name">
                  <b
                    class="ing-link"
                    role="button"
                    tabindex="0"
                    title="查看成分详情"
                    @click.stop="openIngredient(d.name)"
                    @keydown.enter.stop="openIngredient(d.name)"
                    @keydown.space.prevent.stop="openIngredient(d.name)"
                  >{{ d.name }}</b>{{ d.note }}
                </li>
              </ul>
            </article>
          </div>

          <div v-if="medFlags.length" class="fgroup">
            <div class="gtitle warn">需要留意</div>
            <article v-for="(f, i) in medFlags" :key="'m' + i" class="flag medium">
              <header class="flag-head">
                <span v-if="showType(f)" class="flag-type">{{ f.type }}</span>
                <h3 class="fmsg">{{ f.message }}</h3>
              </header>
              <div v-if="(f.ingredients || []).length > 1" class="names">
                <span
                  v-for="n in f.ingredients"
                  :key="n"
                  class="nm ing-link"
                  role="button"
                  tabindex="0"
                  title="查看成分详情"
                  @click.stop="openIngredient(n)"
                  @keydown.enter.stop="openIngredient(n)"
                  @keydown.space.prevent.stop="openIngredient(n)"
                >{{ n }}</span>
              </div>
              <p class="fbasis">{{ f.basis }}</p>
              <ul v-if="f.details && f.details.length" class="notes">
                <li v-for="d in f.details" :key="d.name">
                  <b
                    class="ing-link"
                    role="button"
                    tabindex="0"
                    title="查看成分详情"
                    @click.stop="openIngredient(d.name)"
                    @keydown.enter.stop="openIngredient(d.name)"
                    @keydown.space.prevent.stop="openIngredient(d.name)"
                  >{{ d.name }}</b>{{ d.note }}
                </li>
              </ul>
            </article>
          </div>
        </template>

        <div v-else class="clear">
          <span class="clear-mark" aria-hidden="true">✓</span>
          <div>
            <b>没有需要你回避或留意的成分</b>
            <p>按当前档案，这份配方没有触发高风险或需留意的规则。这不等于适合你，试用仍是唯一可靠的判断。</p>
          </div>
        </div>
      </section>

      <section v-if="!unrecognized && actives.length" id="actives" class="card reveal" style="--i: 4">
        <div class="sec-head">
          <div class="sec-copy">
            <span class="eyebrow">谁在起作用</span>
            <h2 class="h2 serif">功效成分的位置</h2>
          </div>
          <span class="sec-meta">{{ actives.length }} 个</span>
        </div>
        <p class="hint">位置越靠前，相对含量越高——这是顺序信息，不是具体数值。排在第 11 位之后的功效成分，通常不是主要功效来源。</p>

        <ul class="actives">
          <li
            v-for="a in actives"
            :key="a.name + a.index"
            class="active clickable"
            :class="{ concept: a.tier === 'tail' }"
            role="button"
            tabindex="0"
            title="查看成分详情"
            @click="openIngredient(a.name, a)"
            @keydown.enter="openIngredient(a.name, a)"
            @keydown.space.prevent="openIngredient(a.name, a)"
          >
            <div class="atop">
              <span class="aname">{{ a.name }}</span>
              <span class="acat">{{ a.cat }}</span>
              <span v-if="a.tier === 'tail'" class="abadge">概念添加</span>
              <span class="apos tnum">第 {{ a.index + 1 }} 位</span>
            </div>
            <div class="atrack" aria-hidden="true">
              <i :style="{ left: marker(a) + '%' }"></i>
            </div>
            <p class="acomment">{{ a.comment }}</p>
          </li>
        </ul>
      </section>

      <section v-if="!unrecognized && insights.length" id="insights" class="card reveal" style="--i: 5">
        <h2 class="h2 serif sec-title">几个发现</h2>
        <ol class="insights">
          <li v-for="(t, i) in insights" :key="i">
            <i class="ino serif tnum">{{ String(i + 1).padStart(2, '0') }}</i>
            <p>{{ t }}</p>
          </li>
        </ol>
      </section>

      <section v-if="!unrecognized && infoFlags.length" id="regs" class="card reveal" style="--i: 6">
        <h2 class="h2 serif sec-title">法规提示</h2>
        <p class="hint">
          以下为《化妆品安全技术规范》对限用组分的要求，按你的档案不一定触发风险，但值得知道。
        </p>
        <div class="regs">
          <article v-for="f in infoFlags" :key="f.message" class="reg">
            <span class="reg-tag">法规</span>
            <div>
              <b>{{ f.message }}</b>
              <p>{{ f.basis }}</p>
            </div>
          </article>
        </div>
      </section>

      <div class="report-foot no-print">
        <button type="button" class="btn" @click="emit('back')">调整成分表</button>
        <button type="button" class="btn" @click="emit('restart')">换一瓶</button>
        <button type="button" class="btn" @click="emit('print')">打印</button>
        <button type="button" class="btn primary" @click="emit('copy')">复制结论</button>
      </div>
    </div>

    <IngredientDetail v-if="detail" :detail="detail" :profile="profile" @close="detail = null" />
  </article>
</template>

<style scoped>
.report { display: flex; flex-direction: column; gap: 14px; }

/* 程序化聚焦到报告容器，只用于把读屏光标带到新内容上，不画焦点框 */
.report:focus { outline: none; }

/* ----------------------------- 吸顶工具条 ----------------------------- */
.bar {
  position: sticky;
  top: 0;
  z-index: 30;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: rgba(241, 236, 223, .94);
  backdrop-filter: blur(8px);
  box-shadow: 0 12px 26px -30px rgba(38, 30, 18, .9);
  overflow: hidden;
}

.bar-inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 9px 13px;
  flex-wrap: wrap;
}

.bar-left { display: flex; align-items: center; gap: 9px; min-width: 0; }

.badge {
  flex-shrink: 0;
  padding: 2px 8px;
  border-radius: 3px;
  border: 1px solid currentColor;
  font-family: var(--mono);
  font-size: 9.5px;
  font-weight: 600;
  letter-spacing: .14em;
  text-transform: uppercase;
  background: var(--card);
}

.badge.lv-avoid { color: var(--danger); }
.badge.lv-caution { color: var(--warn); }
.badge.lv-good { color: var(--good); }
.badge.lv-insufficient { color: var(--warn); }
.badge.lv-unknown { color: var(--ink2); }

.bar-profile {
  font-size: 12.5px;
  color: var(--ink2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.recalc {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex-shrink: 0;
  font-size: 11.5px;
  color: var(--accent);
}

.recalc .spinner { width: 11px; height: 11px; border-width: 1.5px; }

.bar-actions { display: flex; align-items: center; gap: 2px; margin-left: auto; }

.bar-btn {
  padding: 5px 9px;
  border-radius: 7px;
  font-size: 12.5px;
  color: var(--ink2);
  white-space: nowrap;
  transition: background .16s, color .16s;
}

.bar-btn:hover { background: rgba(31, 107, 90, .08); color: var(--accent-ink); }

.drawer {
  padding: 4px 14px 12px;
  border-top: 1px dashed var(--line);
  background: rgba(255, 255, 255, .55);
  animation: fade .2s ease both;
}

.drawer-note { margin-top: 6px; font-size: 11px; color: var(--ink3); }

.anchors {
  display: flex;
  gap: 2px;
  padding: 0 9px 8px;
  overflow-x: auto;
  scrollbar-width: none;
}

.anchors::-webkit-scrollbar { display: none; }

.anchors button {
  flex-shrink: 0;
  padding: 3px 9px;
  border-radius: 6px;
  font-size: 11.5px;
  color: var(--ink3);
  transition: background .16s, color .16s;
}

.anchors button:hover { background: rgba(0, 0, 0, .045); color: var(--ink); }

.bar-line {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 2px;
  background: linear-gradient(90deg, transparent, var(--accent), transparent);
  background-size: 40% 100%;
  animation: sweep 1.1s ease-in-out infinite;
}

@keyframes sweep {
  from { background-position: -40% 0; }
  to { background-position: 140% 0; }
}

.bar-error {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 13px;
  border: 1px solid var(--danger-line);
  border-radius: 10px;
  background: var(--danger-soft);
  font-size: 12.5px;
  color: #7C3A2E;
}

.bar-error span { flex: 1; }

.bar-error .btn { background: #fff; border-color: #EBC9C0; color: var(--danger); }

/* ----------------------------- 正文 ----------------------------- */
.report-body { display: flex; flex-direction: column; gap: 18px; }

.report-meta {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 2px 2px 12px;
  border-bottom: 1px solid var(--line2);
  position: relative;
}

.report-meta::after {
  content: "";
  position: absolute;
  left: 0;
  right: 0;
  bottom: -4px;
  height: 1px;
  background: var(--line);
}

.rm-main { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; min-width: 0; }

.rm-label {
  font-family: var(--mono);
  font-size: 9.5px;
  letter-spacing: .18em;
  text-transform: uppercase;
  color: var(--brass);
}

.rm-name {
  width: 240px;
  max-width: 100%;
  padding: 0 2px;
  border: none;
  border-bottom: 1px dashed transparent;
  background: transparent;
  color: var(--ink);
  font-family: var(--serif);
  font-size: 23px;
  font-weight: 600;
  letter-spacing: -.01em;
  line-height: 1.35;
  outline: none;
}

.rm-name::placeholder { color: var(--ink3); font-weight: 400; }

.rm-name:hover { border-bottom-color: var(--line2); }

.rm-name:focus { border-bottom-color: var(--accent); }

.rm-id, .rm-time, .rm-rules {
  white-space: nowrap;
  font-family: var(--mono);
  font-size: 9.5px;
  letter-spacing: .1em;
  color: var(--ink3);
}

.rm-id { margin-left: auto; }

/* 标本卡：左侧打孔纸条 + 右上角印章 */
.verdict {
  position: relative;
  padding: 26px 30px 24px 88px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--card);
  box-shadow: var(--shadow-sm);
}

.verdict::before {
  content: "Specimen";
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 54px;
  display: grid;
  place-items: center;
  writing-mode: vertical-rl;
  text-orientation: upright;
  border-right: 1px dashed var(--line2);
  border-radius: 16px 0 0 16px;
  background: linear-gradient(180deg, #F7F1E2, #EFE6D2);
  color: var(--brass);
  font-family: var(--mono);
  font-size: 9px;
  letter-spacing: .3em;
  text-transform: uppercase;
}

/* 印章 = 结论的确定性，全页只有一个 */
.verdict::after {
  content: "避";
  position: absolute;
  top: -14px;
  right: 30px;
  width: 68px;
  height: 68px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  border: 2px solid currentColor;
  font-family: var(--serif);
  font-size: 21px;
  font-weight: 600;
  line-height: 1;
  color: var(--danger);
  background: radial-gradient(circle at 50% 50%, #FFFDF7 46%, #FAEAE4 47%);
  box-shadow: 0 0 0 3px var(--card), 0 0 0 4px rgba(162, 58, 40, .22), 0 10px 24px -16px rgba(162, 58, 40, .55);
  transform: rotate(-9deg);
  pointer-events: none;
}

.verdict.lv-caution::after { content: "慎"; color: var(--warn); background: radial-gradient(circle at 50% 50%, #FFFDF7 46%, #F8EFD8 47%); box-shadow: 0 0 0 3px var(--card), 0 0 0 4px rgba(138, 90, 18, .22), 0 10px 24px -16px rgba(138, 90, 18, .5); }
.verdict.lv-good::after { content: "宜"; color: var(--good); background: radial-gradient(circle at 50% 50%, #FFFDF7 46%, #E6F0E9 47%); box-shadow: 0 0 0 3px var(--card), 0 0 0 4px rgba(44, 115, 85, .22), 0 10px 24px -16px rgba(44, 115, 85, .5); }
.verdict.lv-insufficient::after { content: "待补"; font-size: 13px; letter-spacing: .06em; color: var(--ink3); border-style: dashed; background: radial-gradient(circle at 50% 50%, #FFFDF7 46%, #F1ECDF 47%); box-shadow: 0 0 0 3px var(--card), 0 10px 24px -16px rgba(38, 30, 18, .4); }
.verdict.lv-unknown::after { content: "存疑"; font-size: 13px; letter-spacing: .06em; color: var(--ink3); border-style: dashed; background: radial-gradient(circle at 50% 50%, #FFFDF7 46%, #F1ECDF 47%); box-shadow: 0 0 0 3px var(--card), 0 10px 24px -16px rgba(38, 30, 18, .4); }

.lv-avoid, .lv-caution, .lv-good, .lv-insufficient, .lv-unknown { border-color: var(--line); }

.vtop { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 14px; }

.vtag {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 3px 11px;
  border-radius: 999px;
  background: var(--paper-2);
  border: 1px solid var(--line);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: .04em;
}

.lv-avoid .vtag { color: var(--danger); background: var(--danger-soft); border-color: var(--danger-line); }
.lv-caution .vtag { color: var(--warn); background: var(--warn-soft); border-color: var(--warn-line); }
.lv-good .vtag { color: var(--good); background: var(--good-soft); border-color: var(--good-line); }
.lv-insufficient .vtag { color: var(--warn); background: var(--warn-soft); border-color: var(--warn-line); }
.lv-unknown .vtag { color: var(--ink2); }

.vmeta {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--ink3);
}

.vmeta i { font-style: normal; opacity: .5; }

.vhead {
  position: relative;
  z-index: 1;
  max-width: 22em;
  font-size: 30px;
  font-weight: 600;
  line-height: 1.4;
  letter-spacing: -.01em;
  margin-bottom: 12px;
}

.vsum {
  position: relative;
  z-index: 1;
  max-width: 36em;
  font-size: 14.5px;
  line-height: 1.92;
  color: var(--ink2);
}

.vnote {
  position: relative;
  z-index: 1;
  margin-top: 11px;
  padding: 8px 11px;
  border: 1px solid var(--warn-line);
  border-radius: 8px;
  background: rgba(255, 255, 255, .7);
  font-size: 12px;
  line-height: 1.7;
  color: var(--warn);
}

.vnote.fill {
  border-color: var(--good-line);
  background: rgba(255, 255, 255, .8);
  color: var(--good);
}

.research-status { display: grid; gap: 6px; }
.vnote.research { color: #3C6788; border-color: #D8E3EC; background: #F7FAFC; }
.vnote.review { color: var(--amber, #8A5A12); border-color: var(--warn-line, #E8D8B4); background: #FCF8EE; }
.vnote.research-fail { color: #8A4B3F; border-color: #EED9D2; background: #FCF7F4; }
.vnote-link {
  border: 0;
  background: transparent;
  padding: 0;
  margin-left: 4px;
  color: inherit;
  font: inherit;
  text-decoration: underline;
  text-underline-offset: 2px;
  cursor: pointer;
}

.delta {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-top: 16px;
  padding: 10px 12px;
  border: 1px solid rgba(26, 23, 19, .06);
  border-radius: 10px;
  background: rgba(255, 255, 255, .72);
  animation: fade .26s ease both;
}

.delta-up { border-color: var(--danger-line); }
.delta-down { border-color: var(--good-line); }

.delta-copy { flex: 1; display: flex; flex-direction: column; gap: 1px; }

.delta-copy b { font-size: 12.5px; font-weight: 600; color: var(--ink); }

.delta-copy span { font-size: 12px; line-height: 1.7; color: var(--ink2); }

.delta-x {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  border-radius: 5px;
  color: var(--ink3);
  line-height: 1;
  font-size: 15px;
}

.delta-x:hover { background: rgba(0, 0, 0, .05); color: var(--ink); }

/* 研究中的进度页：只给进度，不给结论 */
.bar-researching {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  color: var(--accent);
}

.pulse {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  animation: pulse 1.2s ease-in-out infinite;
}

.researching { max-width: 760px; padding: 40px 6px 16px; }

.researching .rh {
  font-size: 28px;
  line-height: 1.42;
  letter-spacing: -.01em;
  margin: 10px 0 12px;
}

.researching .rsub {
  max-width: 34em;
  font-size: 14px;
  line-height: 1.95;
  color: var(--ink2);
}

.rbar {
  position: relative;
  height: 6px;
  margin: 28px 0 12px;
  border-radius: 999px;
  background: #EFEAE0;
  overflow: hidden;
}

.rbar i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, var(--accent), #4C9B87);
  transition: width .45s cubic-bezier(.22, .61, .36, 1);
}

/* 进度条上跑一道光：即使宽度不变，也能看出页面是活的 */
.rbar::after {
  content: "";
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, .55), transparent);
  transform: translateX(-100%);
  animation: shimmer 1.9s ease-in-out infinite;
}

@keyframes shimmer {
  to { transform: translateX(100%); }
}

/* 旋转的小圈：比静止的圆点更像「在干活」 */
.ring {
  display: inline-block;
  width: 12px;
  height: 12px;
  flex-shrink: 0;
  border-radius: 50%;
  border: 2px solid rgba(23, 89, 74, .22);
  border-top-color: var(--accent);
  animation: spin .8s linear infinite;
}

.ring.small { width: 11px; height: 11px; border-width: 1.5px; }

.rmeta .elapsed { font-variant-numeric: tabular-nums; color: var(--ink3); }

.rslow {
  margin: 14px 0 0;
  padding: 9px 12px;
  border: 1px dashed var(--warn-line);
  border-radius: 8px;
  background: #FCF8EE;
  font-size: 12px;
  line-height: 1.8;
  color: var(--warn);
}

.rmeta {
  display: flex;
  align-items: baseline;
  gap: 18px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--ink3);
}

.rmeta b {
  font-family: var(--serif);
  font-size: 17px;
  color: var(--ink);
  margin-right: 2px;
}

.rmeta .pending { display: inline-flex; align-items: center; gap: 6px; color: var(--accent); }
.rmeta .auto { margin-left: auto; }

.rlist {
  list-style: none;
  margin: 24px 0 0;
  padding: 0;
  border-top: 1px solid var(--line);
}

.rlist li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 2px;
  border-bottom: 1px solid var(--line);
  font-size: 13.5px;
  color: var(--ink3);
}

.rlist .rname { color: var(--ink); }
.rlist .rtag { margin-left: auto; font-size: 11px; color: var(--ink3); }
.rlist .rmore { font-size: 12px; }

.rfoot { margin-top: 18px; font-size: 12px; color: var(--ink3); }

@media (max-width: 640px) {
  .researching { padding: 26px 2px 12px; }
  .researching .rh { font-size: 23px; }
  .rmeta .auto { margin-left: 0; flex-basis: 100%; }
}

/* 视图切换：对比图 / 梯队 */
.view-switch {
  display: inline-flex;
  margin-left: auto;
  border: 1px solid var(--line2);
  border-radius: 6px;
  overflow: hidden;
}
.view-switch button {
  padding: 4px 11px;
  border: 0;
  background: var(--card);
  color: var(--ink3);
  font-size: 11.5px;
}
.view-switch button + button { border-left: 1px solid var(--line); }
.view-switch button.on {
  background: var(--accent-soft);
  color: var(--accent-ink);
  font-weight: 600;
}

/* 数据条：四个数字，一条横线，不做四张卡 */
.stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 0;
  padding: 0 2px;
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}

.stat {
  padding: 14px 0 12px 18px;
  border-left: 1px solid var(--line);
  background: none;
  box-shadow: none;
}

.stat:first-child { border-left: none; padding-left: 2px; }

.num {
  font-family: var(--serif);
  font-size: 31px;
  font-weight: 600;
  line-height: 1.1;
  letter-spacing: -.02em;
  color: var(--ink);
}

.slabel { margin-top: 3px; font-size: 11.5px; color: var(--ink3); }

.stat.hot { border-color: var(--line); }
.stat.hot .num { color: var(--warn); }

.stat.danger { border-color: var(--line); }
.stat.danger .num { color: var(--danger); }

/* 通用卡片 */
.card { padding: 22px 24px; }

.sec-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 10px;
  margin-bottom: 14px;
  border-bottom: 1px solid var(--line2);
}

.sec-copy { display: flex; flex-direction: column; gap: 3px; }

.sec-meta { flex-shrink: 0; font-size: 11.5px; color: var(--ink3); }

.sec-title { margin-bottom: 6px; }

.hint { margin: 4px 0 14px; }

.hint.warn { color: var(--warn); }

/* 风险 */
.fgroup + .fgroup { margin-top: 18px; }

.gtitle {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 8px;
  font-size: 11.5px;
  font-weight: 700;
  letter-spacing: .08em;
}

.gtitle::before { content: ''; width: 14px; height: 1px; background: currentColor; }

.gtitle.danger { color: var(--danger); }
.gtitle.warn { color: var(--warn); }

/* 风险条目：行式 + 左侧色条，不做彩块卡片 */
.flag {
  padding: 13px 2px 14px 16px;
  border: none;
  border-left: 2px solid var(--line);
  border-bottom: 1px solid var(--line);
  border-radius: 0;
  margin-bottom: 0;
  background: none;
}

.flag:last-child { margin-bottom: 0; border-bottom: none; }

.flag.high { border-left-color: var(--danger); }
.flag.medium { border-left-color: var(--warn); }

.flag-head { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }

.flag-type {
  flex-shrink: 0;
  padding: 1px 7px;
  border: 1px solid var(--line);
  border-radius: 3px;
  background: var(--paper-2);
  font-family: var(--mono);
  font-size: 9px;
  font-weight: 600;
  letter-spacing: .12em;
  text-transform: uppercase;
}

.flag.high .flag-type { color: var(--danger); border-color: var(--danger-line); }
.flag.medium .flag-type { color: var(--warn); border-color: var(--warn-line); }

.fmsg { font-size: 14.5px; font-weight: 600; line-height: 1.65; }

.names { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 7px; }

.nm {
  padding: 1px 8px;
  border: 1px solid var(--line);
  border-radius: 3px;
  background: var(--paper-2);
  color: var(--ink2);
  font-size: 11.5px;
}

.fbasis { margin-top: 7px; font-size: 12.5px; line-height: 1.8; color: var(--ink2); }

.notes {
  list-style: none;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dotted var(--line2);
}

.notes li {
  position: relative;
  padding-left: 11px;
  font-size: 12px;
  line-height: 1.85;
  color: var(--ink2);
}

.notes li::before { content: '·'; position: absolute; left: 1px; color: var(--ink3); }

.notes b { font-weight: 600; margin-right: 3px; }

/* 无风险 */
.clear {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 15px 16px;
  border: 1px dashed var(--good-line);
  border-radius: 12px;
  background: var(--good-soft);
}

.clear-mark {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--good);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
}

.clear b { font-size: 13.5px; font-weight: 600; }

.clear p { margin-top: 2px; font-size: 12.5px; line-height: 1.8; color: var(--ink2); }

/* 功效成分 */
.actives { list-style: none; }

.active { padding: 12px 8px 13px; margin: 0 -8px; border-top: 1px solid #F2EEE4; border-radius: 6px; }
.active:first-child { border-top: none; padding-top: 2px; }
.active.clickable { cursor: pointer; }
.active.clickable:hover { background: #FAF7EF; }
.active.clickable:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }

.ing-link { cursor: pointer; text-decoration: underline; text-decoration-color: #D8CFBE; text-underline-offset: 3px; }
.ing-link:hover { color: var(--accent); text-decoration-color: var(--accent); }
.ing-link:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }

.atop { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }

.aname { font-size: 14px; font-weight: 600; }

.acat {
  padding: 1px 7px;
  border-radius: 4px;
  background: var(--accent-soft);
  color: var(--accent-ink);
  font-size: 10.5px;
}

.abadge {
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--warn-soft);
  color: var(--warn);
  font-size: 10.5px;
  font-weight: 600;
}

.apos { margin-left: auto; font-size: 11.5px; color: var(--ink3); }

.atrack {
  position: relative;
  height: 3px;
  margin: 9px 0 7px;
  border-radius: 999px;
  background: #EFEAE0;
}

.atrack i {
  position: absolute;
  top: 50%;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft);
  transform: translate(-50%, -50%);
  transition: left .5s cubic-bezier(.22, .61, .36, 1);
}

.active.concept .atrack i { background: var(--warn); box-shadow: 0 0 0 3px var(--warn-soft); }

.acomment { font-size: 12.5px; line-height: 1.75; color: var(--ink2); }

.active.concept .apos { color: var(--warn); font-weight: 600; }
.active.concept .acomment { color: var(--warn); }

/* 发现 */
.insights { list-style: none; }

.insights li {
  display: flex;
  gap: 12px;
  padding: 9px 0;
  border-top: 1px dashed var(--line);
}

.insights li:first-child { border-top: none; padding-top: 2px; }

.ino {
  flex-shrink: 0;
  width: 22px;
  font-size: 12px;
  font-style: normal;
  color: var(--accent);
  opacity: .75;
  padding-top: 2px;
}

.insights p { font-size: 13px; line-height: 1.9; color: var(--ink2); }

/* 法规 */
.regs { display: flex; flex-direction: column; gap: 8px; }

.reg {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 11px 13px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #FBF9F4;
}

.reg-tag {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 4px;
  background: #fff;
  color: var(--ink3);
  font-size: 10.5px;
  margin-top: 1px;
}

.reg b { font-size: 13px; font-weight: 600; }

.reg p { margin-top: 2px; font-size: 12px; line-height: 1.75; color: var(--ink3); }

/* 未收录 */
.unknowns { display: flex; flex-wrap: wrap; gap: 6px; }

.unk {
  padding: 3px 10px;
  border-radius: 6px;
  background: #F3F0E8;
  color: var(--ink3);
  font-size: 12px;
}

/* 底部动作 */
.report-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 4px;
  flex-wrap: wrap;
}

.report-foot .primary { margin-left: auto; }

/* 移动端 */
@media (max-width: 979px) {
  .bar { border-radius: 10px; }
  .bar-inner { padding: 8px 10px; }
  .hide-sm { display: none; }
  .drawer { padding: 2px 10px 10px; }
  .card { padding: 18px 16px; }
  .verdict { padding: 20px 16px 18px 68px; border-radius: 14px; }
  .verdict::before { width: 40px; font-size: 8px; letter-spacing: .22em; border-radius: 14px 0 0 14px; }
  .verdict::after { width: 56px; height: 56px; font-size: 17px; right: 18px; top: -12px; }
  .lv-insufficient::after, .lv-unknown::after { font-size: 11px; }
  .vhead { font-size: 25px; }
  .stats { grid-template-columns: repeat(2, 1fr); }
  .stat { padding: 12px 0 10px 16px; }
  .stat:nth-child(odd) { border-left: none; padding-left: 2px; }
  .stat:nth-child(n + 3) { border-top: 1px solid var(--line); }
  .report-foot { justify-content: stretch; }
  .report-foot .btn { flex: 1; }
  .report-foot .primary { margin-left: 0; flex-basis: 100%; order: -1; }
}

@media (max-width: 640px) {
  .bar-profile { max-width: 130px; }
  .bar-inner { padding: 8px 10px 7px; gap: 7px; }
  .bar-actions { width: 100%; margin-left: 0; justify-content: space-between; gap: 4px; }
  .bar-btn { flex: 1; padding: 5px 4px; text-align: center; }
  .anchors { display: none; }
  .vtop { padding-right: 58px; }
  .vhead { font-size: 23px; }
  .report-meta { gap: 4px; }
  .rm-name { width: 100%; font-size: 21px; }
  .rm-time { font-size: 10.5px; }
}
</style>
