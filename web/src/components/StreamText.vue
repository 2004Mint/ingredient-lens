<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { streamJSON } from '../lib/sse.js'

/**
 * 流式解释文本。
 *
 * - 先显示 fallback（规则模板），第一个 delta 到达时清空并接着写，
 *   避免「模板 + 模型」两段文字叠在一起。
 * - 步骤条只显示后端真实推送的事件（读取结论 / 生成 / 校验），不做前端假动画。
 * - 中途失败保留已写出的文字，不清空，也不重试轰炸接口。
 */
const props = defineProps({
  url: { type: String, required: true },
  payload: { type: Object, required: true },
  fallback: { type: String, default: '' },
  steps: { type: Boolean, default: true }
})

const text = ref(props.fallback || '')
const trace = ref([])
const streaming = ref(false)
const source = ref('')
const notice = ref('')

let controller = null
let hasDelta = false

function start() {
  if (streaming.value) return
  text.value = props.fallback || ''
  trace.value = []
  source.value = ''
  notice.value = ''
  hasDelta = false
  streaming.value = true

  controller = new AbortController()
  streamJSON(props.url, props.payload, { signal: controller.signal, onEvent: handle })
    .catch((err) => {
      notice.value = err.message || '解释中断'
    })
    .finally(() => {
      streaming.value = false
    })
}

function handle(evt) {
  if (evt.type === 'step') {
    const index = trace.value.findIndex((s) => s.id === evt.id)
    const next = { id: evt.id, label: evt.label, status: evt.status }
    if (index >= 0) trace.value.splice(index, 1, next)
    else trace.value.push(next)
    return
  }
  if (evt.type === 'delta') {
    if (!hasDelta) {
      hasDelta = true
      text.value = ''
    }
    text.value += evt.text || ''
    return
  }
  if (evt.type === 'replace') {
    hasDelta = true
    text.value = evt.text || props.fallback || ''
    notice.value = evt.reason ? `已回退本地解释：${evt.reason}` : '已回退本地解释'
    return
  }
  if (evt.type === 'done') {
    source.value = evt.source || ''
    if (evt.reason && !notice.value) notice.value = evt.reason
    streaming.value = false
  }
}

function stop() {
  controller?.abort()
  controller = null
  streaming.value = false
}

onMounted(start)
onBeforeUnmount(stop)
</script>

<template>
  <div class="explain">
    <p v-if="steps && trace.length" class="explain-trace">
      <span
        v-for="item in trace"
        :key="item.id"
        class="trace-chip"
        :class="'is-' + item.status"
      >
        <i class="trace-dot" aria-hidden="true"></i>{{ item.label }}
      </span>
    </p>

    <p class="explain-text" aria-live="polite">
      {{ text }}<span v-if="streaming" class="explain-caret" aria-hidden="true"></span>
    </p>

    <p v-if="notice || source === 'template'" class="explain-foot">
      <span v-if="source === 'template'">本地解释</span>
      <span v-else-if="notice">解释未完成</span>
      <span v-if="notice && source === 'template'"> · </span>
      <span v-if="notice">{{ notice }}</span>
    </p>
  </div>
</template>

<style scoped>
.explain {
  margin: 0;
}

.explain-trace {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: 0 0 6px;
  font-size: 11.5px;
  color: var(--ink3, #8a8378);
}

.trace-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  line-height: 1.2;
}

.trace-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.35;
}

.is-running {
  color: var(--ink2, #5d574c);
}

.is-running .trace-dot {
  opacity: 1;
  animation: trace-pulse 1s ease-in-out infinite;
}

.is-done .trace-dot {
  opacity: 0.9;
  background: var(--ok, #4c7a58);
}

.explain-text {
  margin: 0;
  white-space: pre-wrap;
}

.explain-caret {
  display: inline-block;
  width: 0.55em;
  height: 1em;
  margin-left: 1px;
  vertical-align: -0.12em;
  background: currentColor;
  opacity: 0.55;
  animation: trace-blink 1s steps(1) infinite;
}

.explain-foot {
  margin: 6px 0 0;
  font-size: 11.5px;
  color: var(--ink3, #8a8378);
}

@keyframes trace-blink {
  50% {
    opacity: 0;
  }
}

@keyframes trace-pulse {
  50% {
    opacity: 0.35;
  }
}

@media (prefers-reduced-motion: reduce) {
  .explain-caret,
  .is-running .trace-dot {
    animation: none;
  }
}
</style>
