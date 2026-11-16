<script setup>
import { computed } from 'vue'

/**
 * 位置尺：全站唯一的数据图形。
 * 刻度数 = 成分数，标记点位置 = 真实排位。
 * 首页用它讲「位置即含量」，报告页用标出与你冲突的成分。
 */
const props = defineProps({
  total: { type: Number, default: 14 },
  items: { type: Array, default: () => [] }, // [{ name, pos, tone }]
  zones: {
    type: Array,
    default: () => ['前段 · 溶剂与水', '中段 · 功效', '后段 · 微量']
  },
  showFoot: { type: Boolean, default: true },
  label: { type: String, default: '' }
})

const total = computed(() => Math.max(2, Number(props.total) || 2))

const ticks = computed(() =>
  Array.from({ length: total.value }, (_, i) => {
    const major = i % 5 === 0 || i === total.value - 1
    return { key: i, major, last: i === total.value - 1 }
  })
)

const markers = computed(() =>
  (props.items || [])
    .filter((it) => it && Number(it.pos) >= 1 && Number(it.pos) <= total.value)
    .map((it, i) => ({
      key: `${it.name || 'x'}-${i}`,
      name: it.name || '',
      pos: Number(it.pos),
      tone: it.tone || 'brass',
      left: `${(((Number(it.pos) - 0.5) / total.value) * 100).toFixed(3)}%`
    }))
)

const foot = computed(() => {
  const last = total.value
  if (last >= 10) return [1, 5, 10, last]
  const mid = Math.max(2, Math.round(last / 2))
  return [1, mid < last ? mid : last, last]
})
</script>

<template>
  <div
    class="ruler"
    role="img"
    :aria-label="label || `配方位置尺，共 ${total} 个成分`"
  >
    <div class="zone">
      <span v-for="(z, i) in zones" :key="i">{{ z }}</span>
    </div>

    <div class="scale">
      <i
        v-for="t in ticks"
        :key="t.key"
        class="tk"
        :class="{ major: t.major, last: t.last }"
      ></i>

      <span
        v-for="m in markers"
        :key="m.key"
        class="mk"
        :class="m.tone"
        :style="{ left: m.left }"
      >
        <b>{{ m.name }}</b>
        <i></i>
      </span>
    </div>

    <div v-if="showFoot" class="foot">
      <span v-for="n in foot" :key="n" class="mono">{{ n }}</span>
    </div>
  </div>
</template>

<style scoped>
.ruler {
  position: relative;
  padding: 6px 4px 2px;
  min-width: 0;
}

.zone {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  padding: 0 2px 8px;
  font-family: var(--mono);
  font-size: 9px;
  letter-spacing: .14em;
  color: var(--ink3);
  text-transform: uppercase;
  white-space: nowrap;
}

.zone span { position: relative; padding-left: 9px; }

.zone span::before {
  content: "";
  position: absolute;
  left: 0;
  top: 5px;
  width: 5px;
  height: 1px;
  background: var(--line2);
}

.scale {
  position: relative;
  display: flex;
  align-items: flex-end;
  height: 40px;
  border-bottom: 1px solid var(--line2);
}

.tk {
  flex: 1;
  height: 8px;
  border-left: 1px solid var(--line2);
}

.tk.major { height: 15px; border-left-color: var(--ink3); }

.tk.last { border-right: 1px solid var(--ink3); height: 15px; }

.mk {
  position: absolute;
  bottom: -4px;
  transform: translateX(-50%);
  display: flex;
  flex-direction: column;
  align-items: center;
}

.mk i {
  display: block;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--brass);
  box-shadow: 0 0 0 3px rgba(156, 117, 52, .15);
}

.mk b {
  margin-bottom: 6px;
  padding: 1px 5px;
  border-radius: 3px;
  background: var(--card);
  box-shadow: 0 0 0 1px var(--line);
  color: var(--ink2);
  font-family: var(--sans);
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: .02em;
  white-space: nowrap;
}

.mk.verm i { background: var(--danger); box-shadow: 0 0 0 3px rgba(162, 58, 40, .15); }
.mk.verm b { color: var(--danger); box-shadow: 0 0 0 1px var(--danger-line); }

.mk.amber i { background: var(--warn); box-shadow: 0 0 0 3px rgba(138, 90, 18, .15); }
.mk.amber b { color: var(--warn); box-shadow: 0 0 0 1px var(--warn-line); }

.mk.good i { background: var(--good); box-shadow: 0 0 0 3px rgba(44, 115, 85, .15); }
.mk.good b { color: var(--good); box-shadow: 0 0 0 1px var(--good-line); }

.foot {
  display: flex;
  justify-content: space-between;
  padding: 8px 2px 0;
  color: var(--ink3);
  font-family: var(--mono);
  font-size: 10px;
  letter-spacing: .1em;
}

@media (max-width: 640px) {
  .zone { font-size: 8px; letter-spacing: .1em; }
  .mk b { font-size: 9.5px; }
}
</style>
