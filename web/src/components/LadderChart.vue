<script setup>
import { computed } from 'vue'

const props = defineProps({
  items: { type: Array, default: () => [] }
})

const emit = defineEmits(['select'])

const bands = computed(() => {
  const core = []
  const middle = []
  const tail = []

  for (const row of props.items || []) {
    const i = Number(row.index) || 0
    if (i < 5) core.push(row)
    else if (i < 10) middle.push(row)
    else tail.push(row)
  }

  return [
    { key: 'core', label: '前 5 位', note: '含量较高，是配方的主体', items: core },
    { key: 'middle', label: '第 6–10 位', note: '含量中等', items: middle },
    { key: 'tail', label: '第 11 位之后', note: '含量通常已很低，功效成分排在这里多为概念添加', items: tail }
  ].filter((band) => band.items.length)
})

const hasUnknown = computed(() => (props.items || []).some((r) => r.known === false))

const ROLE_MAP = {
  功效: 'r-active',
  防腐: 'r-preserve',
  香精: 'r-fragrance',
  防晒: 'r-uv',
  表活: 'r-surf',
  基质: 'r-base'
}

function pillClass(row) {
  if (row.known === false) return 'r-unknown'
  return ROLE_MAP[row.role] || 'r-base'
}

function isConcept(row) {
  return row.known !== false && row.role === '功效' && (Number(row.index) || 0) >= 10
}

function displayName(row) {
  return row.known === false ? '待补全' : row.name
}

function tip(row) {
  const pos = `第 ${(Number(row.index) || 0) + 1} 位`
  if (row.known === false) return `${pos} · 待补全，补全后会参与结论`
  const base = isConcept(row)
    ? `${row.name} · ${pos} · 功效成分排在此处，含量通常已很低`
    : `${row.name} · ${pos}${row.cat ? ' · ' + row.cat : ''}`
  return `${base} · 点击查看成分详情`
}

function canPick(row) {
  return row.known !== false
}

function pick(row) {
  if (!canPick(row)) return
  const index = Number(row.index) || 0
  emit('select', {
    name: row.name,
    index,
    role: row.role || '',
    cat: row.cat || '',
    fn: row.fn || '',
    ai: !!row.ai,
    tier: index < 5 ? 'core' : index < 10 ? 'middle' : 'tail'
  })
}
</script>

<template>
  <div class="ladder">
    <div class="axis" aria-hidden="true">
      <span>高</span>
      <i></i>
      <span>低</span>
    </div>

    <div class="bands">
      <div v-for="b in bands" :key="b.key" class="band">
        <div class="bhead">
          <span class="blabel">{{ b.label }}</span>
          <span class="bnote">{{ b.note }}</span>
        </div>
        <div class="pills">
          <span
            v-for="r in b.items"
            :key="r.index + ':' + r.name"
            class="pill"
            :class="[pillClass(r), { clickable: canPick(r) }]"
            :title="tip(r)"
            :role="canPick(r) ? 'button' : undefined"
            :tabindex="canPick(r) ? 0 : -1"
            :aria-disabled="canPick(r) ? undefined : 'true'"
            @click="pick(r)"
            @keydown.enter="pick(r)"
            @keydown.space.prevent="pick(r)"
          >
            <i class="pos">{{ (Number(r.index) || 0) + 1 }}</i>
            <span class="pname">{{ displayName(r) }}</span>
            <span v-if="isConcept(r)" class="tag tag-concept">概念</span>
            <span v-if="r.ai" class="tag tag-ai" title="AI 研究补全，待人工确认">AI</span>
            <span v-if="r.known === false" class="tag tag-unknown">待补全</span>
          </span>
        </div>
      </div>
    </div>
  </div>

  <div class="legend">
    <span><i class="dot r-active"></i>功效</span>
    <span><i class="dot r-preserve"></i>防腐</span>
    <span><i class="dot r-fragrance"></i>香精</span>
    <span><i class="dot r-uv"></i>防晒</span>
    <span><i class="dot r-surf"></i>表活</span>
    <span><i class="dot r-base"></i>基质</span>
    <span v-if="hasUnknown"><i class="dot r-unknown"></i>待补全</span>
  </div>
</template>

<style scoped>
.ladder { display: flex; gap: 16px; }

.axis {
  display: flex;
  flex-direction: column;
  align-items: center;
  flex-shrink: 0;
  width: 18px;
  padding: 2px 0 0;
  font-size: 10px;
  color: var(--ink3);
}

.axis i {
  flex: 1;
  width: 1px;
  margin: 6px 0;
  background: linear-gradient(180deg, var(--accent-line), var(--line));
}

.bands { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 15px; }

.bhead { display: flex; align-items: baseline; gap: 9px; margin-bottom: 7px; flex-wrap: wrap; }

.blabel {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink2);
  letter-spacing: .04em;
}

.bnote { font-size: 11.5px; color: var(--ink3); }

.pills { display: flex; flex-wrap: wrap; gap: 6px; }

.pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  max-width: 100%;
  padding: 4px 9px 4px 5px;
  border: 1px solid transparent;
  border-radius: 7px;
  font-size: 12.5px;
  line-height: 1.7;
  transition: transform .14s, box-shadow .14s;
}

.pill:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 10px -6px rgba(26, 23, 19, .35);
}

.pill.clickable { cursor: pointer; }
.pill.clickable:hover { border-color: rgba(255, 255, 255, .9); }
.pill.clickable:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}

.pos {
  font-style: normal;
  font-size: 10px;
  font-weight: 700;
  width: 16px;
  height: 16px;
  line-height: 16px;
  text-align: center;
  border-radius: 4px;
  background: rgba(255, 255, 255, .72);
  color: inherit;
  flex-shrink: 0;
}

.pname { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.tag {
  flex-shrink: 0;
  font-size: 9.5px;
  line-height: 1.6;
  padding: 0 4px;
  border-radius: 3px;
  background: rgba(255, 255, 255, .8);
  font-weight: 600;
}

.tag-concept { color: var(--warn); }
.tag-ai { color: #3C6788; background: #EEF3F8; }
.tag-unknown { color: var(--ink3); }

.r-active { background: #E5F1EA; color: #22624F; border-color: #CBE4D7; }
.r-preserve { background: #FBF2DE; color: #84590F; border-color: #EEDDBB; }
.r-fragrance { background: #FBEAE5; color: #9E3B2D; border-color: #F1D3CA; }
.r-uv { background: #EAF0F8; color: #375A86; border-color: #D5E0EF; }
.r-surf { background: #F2ECF7; color: #65418A; border-color: #E2D5EE; }
.r-base { background: #F3F0E8; color: #6B665C; border-color: #E5DFD2; }

.r-unknown {
  background: transparent;
  color: var(--ink3);
  border: 1px dashed var(--line2);
}

.pill.r-unknown .pos { background: #F3F0E8; }

.legend {
  display: flex;
  flex-wrap: wrap;
  gap: 13px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--line);
  font-size: 11.5px;
  color: var(--ink3);
}

.legend span { display: inline-flex; align-items: center; gap: 5px; }

.dot {
  width: 9px;
  height: 9px;
  border-radius: 3px;
  border: 1px solid transparent;
}

.dot.r-unknown { border: 1px dashed var(--line2); background: transparent; }

@media (max-width: 640px) {
  .axis { display: none; }
  .pill { font-size: 12px; }
  .bnote { display: none; }
}
</style>
