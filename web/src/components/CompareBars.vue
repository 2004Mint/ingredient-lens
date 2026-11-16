<script setup>
import { computed, ref } from 'vue'

/**
 * 成分对比图：把「排位」画成条，把「角色」画成颜色，把「冲突」画成标记。
 *
 * 长度用的是相对排位（第 1 位 100%，越靠后越短），不是真实浓度 ——
 * 标签上本来就没有浓度，画成百分比柱就是在编数据。这一点在图例里写清楚。
 */
const props = defineProps({
  items: { type: Array, default: () => [] },
  risks: { type: Map, default: () => new Map() },
  total: { type: Number, default: 0 }
})

const emit = defineEmits(['select'])
// 成分不多时直接全展开：默认把「该避开的那条」藏起来是反人性的
const showAll = ref((props.items || []).length <= 20)

const rows = computed(() =>
  (props.items || []).map((row) => {
    // 后端 index 从 0 开始，展示与计算都用 1-based 的排位
    const pos = (Number(row.index) || 0) + 1
    const total = Math.max(1, Number(props.total) || (props.items || []).length || 1)
    const ratio = Math.max(0.03, (total - pos + 1) / total)
    const risk = props.risks.get(row.name) || ''
    return { ...row, pos, ratio, risk, known: row.known !== false }
  })
)

const visible = computed(() => (showAll.value ? rows.value : rows.value.slice(0, 12)))
const hidden = computed(() => Math.max(0, rows.value.length - visible.value.length))

const roleClass = (role) => {
  if (role === '功效') return 'c-active'
  if (role === '防腐') return 'c-preserve'
  if (role === '香精') return 'c-fragrance'
  if (role === '防晒') return 'c-uv'
  if (role === '表活') return 'c-surf'
  return 'c-base'
}

/** 角色构成：按数量做一条 100% 堆叠条 */
const composition = computed(() => {
  const order = ['基质', '功效', '防腐', '香精', '表活', '防晒']
  const count = new Map()
  let unknown = 0
  for (const row of rows.value) {
    if (!row.known) { unknown += 1; continue }
    const role = row.role || '基质'
    count.set(role, (count.get(role) || 0) + 1)
  }
  const parts = order
    .filter((role) => count.get(role))
    .map((role) => ({ role, n: count.get(role), cls: roleClass(role) }))
  if (unknown) parts.push({ role: '未收录', n: unknown, cls: 'c-unknown' })
  const sum = parts.reduce((acc, p) => acc + p.n, 0) || 1
  return parts.map((p) => ({ ...p, pct: (p.n / sum) * 100 }))
})

const riskLabel = (risk) => (risk === 'high' ? '避' : risk === 'medium' ? '慎' : '')
</script>

<template>
  <div class="cmp">
    <div class="cmp-top">
      <div class="cmp-legend">
        <span v-for="p in composition" :key="p.role" class="lg">
          <i :class="p.cls"></i>{{ p.role }} {{ p.n }}
        </span>
      </div>
      <button type="button" class="cmp-toggle" @click="showAll = !showAll">
        {{ showAll ? '只看前 12 位' : `查看全部 ${rows.length} 个` }}
      </button>
    </div>

    <div class="cmp-stack" role="img" aria-label="成分角色构成">
      <span
        v-for="p in composition"
        :key="p.role"
        :class="p.cls"
        :style="{ width: p.pct + '%' }"
        :title="`${p.role} ${p.n} 个`"
      ></span>
    </div>

    <ul class="cmp-bars">
      <li v-for="row in visible" :key="row.pos">
        <button
          type="button"
          class="cmp-name"
          :class="{ unknown: !row.known }"
          @click="emit('select', row.name)"
        >{{ row.name }}</button>
        <span class="cmp-track">
          <span
            class="cmp-fill"
            :class="[roleClass(row.role), { unknown: !row.known }]"
            :style="{ width: row.ratio * 100 + '%' }"
          ></span>
        </span>
        <span class="cmp-pos">{{ row.pos }}</span>
        <span v-if="row.risk" class="cmp-risk" :class="row.risk">{{ riskLabel(row.risk) }}</span>
        <span v-else-if="!row.known" class="cmp-risk unknown">?</span>
      </li>
    </ul>

    <p v-if="hidden" class="cmp-more">还有 {{ hidden }} 个成分（点右上角展开）</p>
    <p class="cmp-note">
      条长 = 相对排位（第 1 位最长），不是真实浓度；颜色 = 成分角色；<b class="r">避</b>/<b class="a">慎</b>= 与你的档案冲突。
    </p>
  </div>
</template>

<style scoped>
.cmp { display: flex; flex-direction: column; gap: 12px; }

.cmp-top { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }

.cmp-legend { display: flex; gap: 12px; flex-wrap: wrap; font-size: 11.5px; color: var(--ink3); }
.cmp-legend .lg { display: inline-flex; align-items: center; gap: 5px; }
.cmp-legend i { width: 8px; height: 8px; border-radius: 2px; display: inline-block; }

.cmp-toggle {
  margin-left: auto;
  padding: 3px 10px;
  border: 1px solid var(--line2);
  border-radius: 4px;
  background: var(--card);
  color: var(--ink3);
  font-size: 11.5px;
}
.cmp-toggle:hover { color: var(--accent); border-color: var(--accent-line); }

.cmp-stack { display: flex; height: 8px; border-radius: 3px; overflow: hidden; gap: 1px; }
.cmp-stack span { display: block; }

.cmp-bars { list-style: none; margin: 2px 0 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.cmp-bars li { display: grid; grid-template-columns: 132px 1fr 26px 18px; align-items: center; gap: 10px; }

.cmp-name {
  text-align: left;
  font-size: 12.5px;
  color: var(--ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  padding: 0;
  border: 0;
  background: none;
  cursor: pointer;
}
.cmp-name:hover { color: var(--accent); }
.cmp-name.unknown { color: var(--ink3); font-style: italic; }

.cmp-track { position: relative; height: 14px; background: #F4F0E7; border-radius: 3px; overflow: hidden; }

.cmp-fill {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  border-radius: 3px;
  min-width: 4px;
}

.cmp-fill.c-base { background: #C2B49A; }
.cmp-fill.c-active { background: #17594A; }
.cmp-fill.c-preserve { background: #6E8B84; }
.cmp-fill.c-fragrance { background: #9C7534; }
.cmp-fill.c-uv { background: #5C7F96; }
.cmp-fill.c-surf { background: #8C7A62; }
.cmp-fill.unknown {
  background: repeating-linear-gradient(45deg, #D9D0BF 0 3px, transparent 3px 6px);
  border: 1px dashed var(--line2);
}

.cmp-stack .c-base { background: #C2B49A; }
.cmp-stack .c-active { background: #17594A; }
.cmp-stack .c-preserve { background: #6E8B84; }
.cmp-stack .c-fragrance { background: #9C7534; }
.cmp-stack .c-uv { background: #5C7F96; }
.cmp-stack .c-surf { background: #8C7A62; }
.cmp-stack .c-unknown { background: repeating-linear-gradient(45deg, #D9D0BF 0 3px, transparent 3px 6px); }

.cmp-pos {
  font-family: var(--mono);
  font-size: 10.5px;
  color: var(--ink3);
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.cmp-risk {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 9.5px;
  font-weight: 700;
  line-height: 1;
}
.cmp-risk.high { background: var(--danger-soft); color: var(--danger); }
.cmp-risk.medium { background: var(--warn-soft); color: var(--warn); }
.cmp-risk.unknown { background: #EFEAE0; color: var(--ink3); }

.cmp-note, .cmp-more { margin: 0; font-size: 11px; color: var(--ink3); line-height: 1.7; }
.cmp-note b.r { color: var(--danger); }
.cmp-note b.a { color: var(--warn); }

@media (max-width: 640px) {
  .cmp-bars li { grid-template-columns: 96px 1fr 22px 16px; gap: 8px; }
  .cmp-name { font-size: 12px; }
  .cmp-toggle { margin-left: 0; }
}
</style>
