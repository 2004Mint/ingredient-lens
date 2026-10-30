<script setup>
import { computed } from 'vue'

const props = defineProps({ ladder: { type: Array, default: () => [] } })

const bands = computed(() => {
  const core = [], middle = [], tail = []
  props.ladder.forEach((r) => {
    if (r.index < 5) core.push(r)
    else if (r.index < 10) middle.push(r)
    else tail.push(r)
  })
  return [
    { key: 'core', label: '前 5 位', note: '含量较高，是配方的主体', items: core },
    { key: 'middle', label: '第 6–10 位', note: '含量中等', items: middle },
    { key: 'tail', label: '第 11 位之后', note: '含量通常已很低，功效成分排在这里多为概念添加', items: tail }
  ]
})

const ROLE_MAP = {
  '功效': 'r-active',
  '防腐': 'r-preserve',
  '香精': 'r-fragrance',
  '防晒': 'r-uv',
  '表活': 'r-surf',
  '基质': 'r-base'
}
function roleClass(role) { return ROLE_MAP[role] || 'r-base' }
</script>

<template>
  <div class="ladder">
    <div v-for="b in bands" :key="b.key" class="band">
      <div class="bhead">
        <span class="blabel">{{ b.label }}</span>
        <span class="bnote">{{ b.note }}</span>
      </div>
      <div class="pills">
        <span
          v-for="r in b.items" :key="r.index + r.name"
          class="pill" :class="roleClass(r.role)"
          :title="r.name + '（第 ' + (r.index + 1) + ' 位）'"
        >
          <i class="pos">{{ r.index + 1 }}</i>{{ r.name }}
        </span>
        <span v-if="!b.items.length" class="empty">—</span>
      </div>
    </div>

    <div class="legend">
      <span><i class="dot r-active"></i>功效</span>
      <span><i class="dot r-preserve"></i>防腐</span>
      <span><i class="dot r-fragrance"></i>香精</span>
      <span><i class="dot r-uv"></i>防晒</span>
      <span><i class="dot r-surf"></i>表活</span>
      <span><i class="dot r-base"></i>基质</span>
    </div>
  </div>
</template>

<style scoped>
.ladder { display: flex; flex-direction: column; gap: 16px; }

.band { position: relative; padding-left: 0; }
.bhead { display: flex; align-items: baseline; gap: 10px; margin-bottom: 8px; }
.blabel { font-size: 12px; font-weight: 600; color: var(--ink2); letter-spacing: .04em; }
.bnote { font-size: 11.5px; color: var(--ink3); }

.pills { display: flex; flex-wrap: wrap; gap: 7px; }

.pill {
  display: inline-flex; align-items: center; gap: 5px;
  font-size: 12.5px; padding: 5px 11px 5px 6px;
  border-radius: 7px; border: 1px solid transparent;
  transition: transform .12s;
}
.pill:hover { transform: translateY(-1px); }

.pos {
  font-style: normal; font-size: 10px; font-weight: 700;
  width: 16px; height: 16px; line-height: 16px; text-align: center;
  border-radius: 4px; background: rgba(255,255,255,.7); color: inherit;
}

.r-active { background: #E7F1ED; color: #256B5B; border-color: #CFE4DC; }
.r-preserve { background: #FBF3E2; color: #8F6416; border-color: #F0E3C4; }
.r-fragrance { background: #FBEBE8; color: #A33A2C; border-color: #F2D5CF; }
.r-uv { background: #EAF0F8; color: #3A5E8C; border-color: #D6E1F0; }
.r-surf { background: #F2ECF7; color: #6B468C; border-color: #E3D6EE; }
.r-base { background: #F4F1EB; color: #6E6A62; border-color: #E7E1D6; }

.empty { font-size: 12px; color: var(--ink3); }

.legend {
  display: flex; flex-wrap: wrap; gap: 14px;
  font-size: 11.5px; color: var(--ink3);
  padding-top: 12px; border-top: 1px dashed var(--line);
}
.legend span { display: inline-flex; align-items: center; gap: 5px; }
.dot { width: 9px; height: 9px; border-radius: 3px; display: inline-block; border: 1px solid transparent; }
</style>
