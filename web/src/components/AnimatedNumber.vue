<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  value: { type: Number, default: 0 },
  duration: { type: Number, default: 620 }
})

const display = ref(0)
let raf = 0

const reduced =
  typeof window.matchMedia === 'function' &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches

function run(from, to) {
  cancelAnimationFrame(raf)
  if (reduced || from === to) {
    display.value = to
    return
  }
  const start = performance.now()
  const step = (now) => {
    const t = Math.min(1, Math.max(0, (now - start) / props.duration))
    const eased = 1 - Math.pow(1 - t, 3)
    display.value = Math.round(from + (to - from) * eased)
    if (t < 1) raf = requestAnimationFrame(step)
  }
  raf = requestAnimationFrame(step)
}

onMounted(() => {
  // 首屏从 0 数上去，换档案重算时从旧值滑到新值
  run(0, props.value)
})

watch(
  () => props.value,
  (next, prev) => run(prev ?? 0, next)
)

onBeforeUnmount(() => cancelAnimationFrame(raf))
</script>

<template>
  <span class="tnum">{{ display }}</span>
</template>
