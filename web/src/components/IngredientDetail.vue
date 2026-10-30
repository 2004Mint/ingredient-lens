<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({
  detail: { type: Object, required: true }
})
const emit = defineEmits(['close'])

const closeButton = ref(null)
let previousFocus = null

const item = computed(() => props.detail.item || {})
const context = computed(() => props.detail.context || {})
const aliases = computed(() => (item.value.aliases || []).filter(Boolean))
const researched = computed(() => !!(item.value.researched || context.value.ai))

const description = computed(() => {
  const deep = String(item.value.description || '').trim()
  if (deep) return deep
  return String(item.value.function || '').trim() || '暂无更多描述。'
})

const oneLine = computed(() => {
  const fn = String(item.value.function || '').trim()
  return item.value.description && fn ? fn : ''
})

const tierLabel = computed(() => {
  if (context.value.tier === 'core') return '前 5 位，含量较高'
  if (context.value.tier === 'middle') return '第 6–10 位，含量中等'
  if (context.value.tier === 'tail') return '第 11 位之后，含量通常已很低'
  return ''
})

const rankText = computed(() => {
  if (context.value.index == null) return ''
  return `第 ${Number(context.value.index) + 1} 位`
})

const riskTags = computed(() => {
  const out = []
  const safety = item.value.safety
  if (safety === 'high') out.push({ text: '高风险', tone: 'danger' })
  else if (safety === 'medium') out.push({ text: '中等风险', tone: 'warn' })

  const comedo = Number(item.value.comedo)
  if (comedo >= 3) out.push({ text: `致痘性 ${comedo}/5`, tone: comedo >= 4 ? 'danger' : 'warn' })

  if (item.value.irrit === 'high') out.push({ text: '强刺激', tone: 'danger' })
  else if (item.value.irrit === 'medium') out.push({ text: '刺激中等', tone: 'warn' })

  if (item.value.preg === 'avoid') out.push({ text: '孕期避开', tone: 'danger' })
  else if (item.value.preg === 'caution') out.push({ text: '孕期慎用', tone: 'warn' })

  if (item.value.sensitive === 'avoid') out.push({ text: '敏感肌避开', tone: 'danger' })
  else if (item.value.sensitive === 'caution') out.push({ text: '敏感肌留意', tone: 'warn' })

  if (item.value.limit) out.push({ text: '法规限量', tone: 'neutral' })
  return out
})

const comment = computed(() => String(context.value.comment || context.value.fn || '').trim())

function close() {
  emit('close')
}

function onKey(event) {
  if (event.key === 'Escape') close()
}

onMounted(async () => {
  previousFocus = document.activeElement
  document.body.style.overflow = 'hidden'
  window.addEventListener('keydown', onKey)
  await nextTick()
  closeButton.value?.focus()
})

onBeforeUnmount(() => {
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKey)
  if (previousFocus && typeof previousFocus.focus === 'function') previousFocus.focus()
})
</script>

<template>
  <Teleport to="body">
    <div class="id-layer no-print" @click.self="close">
      <article
        class="id-card"
        role="dialog"
        aria-modal="true"
        aria-labelledby="ingredient-detail-title"
        @click.stop
      >
        <button
          ref="closeButton"
          type="button"
          class="id-close"
          aria-label="关闭成分详情"
          @click="close"
        >×</button>

        <header class="id-head">
          <p class="id-kicker">成分详情</p>
          <h2 id="ingredient-detail-title" class="id-title serif">{{ item.name || '—' }}</h2>
          <div class="id-meta">
            <span v-if="item.role" class="id-chip">{{ item.role }}</span>
            <span v-if="item.cat" class="id-chip">{{ item.cat }}</span>
            <span v-if="researched" class="id-ai">AI 深度研究</span>
          </div>
        </header>

        <p class="id-desc">{{ description }}</p>
        <p v-if="oneLine" class="id-oneline">一句话：{{ oneLine }}</p>

        <section v-if="riskTags.length" class="id-section">
          <h3>风险与注意</h3>
          <div class="id-tags">
            <span v-for="tag in riskTags" :key="tag.text" class="id-tag" :class="tag.tone">{{ tag.text }}</span>
          </div>
          <p v-if="item.limit" class="id-limit">限量：{{ item.limit }}</p>
        </section>
        <section v-else class="id-section">
          <h3>风险与注意</h3>
          <p class="id-muted">标签信息里没有特别的风险标注。</p>
        </section>

        <section v-if="rankText || comment" class="id-section id-context">
          <h3>在这瓶配方里</h3>
          <p v-if="rankText" class="id-rank">
            {{ rankText }}<span v-if="tierLabel"> · {{ tierLabel }}</span>
          </p>
          <p v-if="comment" class="id-comment">{{ comment }}</p>
        </section>

        <section v-if="aliases.length" class="id-section">
          <h3>别名</h3>
          <p class="id-aliases">{{ aliases.join('、') }}</p>
        </section>

        <p v-if="item.note" class="id-note">{{ item.note }}</p>

        <footer class="id-foot">
          <p>只读标签与成分库信息，不评价商品优劣，也不构成医疗或用药建议。{{ researched ? 'AI 生成的描述仍需人工复核。' : '' }}</p>
          <button type="button" class="id-done" @click="close">知道了</button>
        </footer>
      </article>
    </div>
  </Teleport>
</template>

<style scoped>
.id-layer {
  position: fixed;
  inset: 0;
  z-index: 120;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(22, 20, 16, .44);
  backdrop-filter: blur(3px);
  animation: id-fade .18s ease both;
}

.id-card {
  position: relative;
  width: min(560px, 100%);
  max-height: min(86vh, 760px);
  overflow: auto;
  padding: 28px 28px 22px;
  border: 1px solid #E6DFD2;
  border-radius: 16px;
  background: #FCFAF4;
  box-shadow: 0 30px 80px -28px rgba(28, 24, 18, .55);
  animation: id-rise .22s cubic-bezier(.2, .8, .2, 1) both;
}

.id-close {
  position: absolute;
  top: 15px;
  right: 15px;
  width: 30px;
  height: 30px;
  border: 1px solid #E8E1D4;
  border-radius: 50%;
  background: #fff;
  color: var(--ink3);
  font-size: 19px;
  line-height: 1;
  cursor: pointer;
}
.id-close:hover { color: var(--ink); border-color: #D8CFBE; }
.id-close:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }

.id-head { padding-right: 26px; }
.id-kicker { margin: 0 0 5px; color: var(--accent); font-size: 10px; letter-spacing: .16em; text-transform: uppercase; }
.id-title { margin: 0; color: var(--ink); font-size: 28px; line-height: 1.25; font-weight: 500; }
.id-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.id-chip { padding: 2px 7px; border: 1px solid #E8E1D4; border-radius: 4px; background: #fff; color: var(--ink2); font-size: 10px; }
.id-ai { padding: 2px 7px; border-radius: 4px; background: #EEF3F8; color: #3C6788; font-size: 10px; }

.id-desc { margin: 16px 0 0; color: var(--ink2); font-size: 13px; line-height: 1.9; white-space: pre-wrap; }
.id-oneline { margin: 7px 0 0; color: var(--ink3); font-size: 11.5px; line-height: 1.75; }

.id-section { margin-top: 18px; padding-top: 14px; border-top: 1px solid #EFEAE0; }
.id-section h3 { margin: 0 0 8px; color: var(--ink3); font-size: 10px; font-weight: 500; letter-spacing: .08em; }

.id-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.id-tag { padding: 3px 8px; border-radius: 4px; font-size: 10.5px; line-height: 1.6; }
.id-tag.danger { background: #FBEDE9; color: #8A3E31; }
.id-tag.warn { background: #FBF3DF; color: #7A5A12; }
.id-tag.neutral { background: #F1EEE6; color: var(--ink2); }
.id-limit { margin: 8px 0 0; color: var(--ink2); font-size: 11px; line-height: 1.7; }
.id-muted { margin: 0; color: var(--ink3); font-size: 11.5px; line-height: 1.7; }

.id-rank { margin: 0; color: var(--ink); font-size: 13px; font-weight: 500; }
.id-comment { margin: 6px 0 0; color: var(--ink2); font-size: 12px; line-height: 1.8; }
.id-context { background: #F8F5EC; margin-left: -10px; margin-right: -10px; padding: 13px 10px 0; border-radius: 8px; border-top: 0; }
.id-context h3 { margin-bottom: 7px; }

.id-aliases { margin: 0; color: var(--ink2); font-size: 11.5px; line-height: 1.8; }
.id-note { margin: 16px 0 0; padding: 10px 12px; border-left: 2px solid #D8CFBE; background: #F8F5EC; color: var(--ink2); font-size: 11.5px; line-height: 1.8; }

.id-foot { display: flex; align-items: flex-end; justify-content: space-between; gap: 14px; margin-top: 22px; }
.id-foot p { margin: 0; max-width: 390px; color: var(--ink3); font-size: 10px; line-height: 1.75; }
.id-done {
  flex-shrink: 0;
  padding: 7px 14px;
  border: 1px solid var(--ink);
  border-radius: 5px;
  background: var(--ink);
  color: #FFFDF7;
  font-size: 11px;
  cursor: pointer;
}
.id-done:hover { background: #2F2A24; }

@keyframes id-fade { from { opacity: 0; } to { opacity: 1; } }
@keyframes id-rise { from { transform: translateY(8px) scale(.985); } to { transform: none; } }

@media (max-width: 640px) {
  .id-layer { align-items: flex-end; padding: 0; }
  .id-card { width: 100%; max-height: 90vh; border-radius: 16px 16px 0 0; padding: 24px 20px 18px; }
  .id-title { font-size: 24px; }
  .id-foot { align-items: flex-start; flex-direction: column; }
  .id-done { width: 100%; padding: 9px 14px; }
}

@media print {
  .id-layer { display: none !important; }
}
</style>
