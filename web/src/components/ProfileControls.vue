<script setup>
import { computed } from 'vue'

const props = defineProps({
  modelValue: { type: Object, required: true },
  compact: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue'])

const SKINS = [
  { k: 'dry', label: '干性' },
  { k: 'oily', label: '油性' },
  { k: 'combo', label: '混合' },
  { k: 'normal', label: '中性' }
]

/* 常用组合：把「三个控件都要点一遍」压缩成一次点击 */
const PRESETS = [
  { id: 'oily-sensitive', label: '油皮 · 敏感', patch: { skin: 'oily', sensitive: true } },
  { id: 'dry-tolerate', label: '干皮 · 耐受', patch: { skin: 'dry', sensitive: false } },
  { id: 'pregnancy', label: '孕期 / 备孕', patch: { pregnant: true } }
]

const current = computed(() => props.modelValue || {})

function patch(part) {
  emit('update:modelValue', { ...current.value, ...part })
}

function applyPreset(preset) {
  if (preset.id === 'pregnancy') {
    patch({ pregnant: !current.value.pregnant })
    return
  }
  patch(preset.patch)
}

function isActive(preset) {
  if (preset.id === 'pregnancy') return !!current.value.pregnant
  return Object.entries(preset.patch).every(([k, v]) => current.value[k] === v)
}
</script>

<template>
  <div class="profile-controls" :class="{ compact }">
    <div v-if="!compact" class="presets">
      <span class="presets-label">常用</span>
      <button
        v-for="p in PRESETS"
        :key="p.id"
        type="button"
        class="preset"
        :class="{ on: isActive(p) }"
        :aria-pressed="isActive(p)"
        @click="applyPreset(p)"
      >
        {{ p.label }}
      </button>
    </div>

    <div class="field">
      <span id="skin-label" class="flabel">肤质</span>
      <div class="seg" role="radiogroup" aria-labelledby="skin-label">
        <label
          v-for="s in SKINS"
          :key="s.k"
          class="seg-item"
          :class="{ on: current.skin === s.k }"
        >
          <input
            class="sr-only"
            type="radio"
            name="skin"
            :value="s.k"
            :checked="current.skin === s.k"
            @change="patch({ skin: s.k })"
          />
          <span>{{ s.label }}</span>
        </label>
      </div>
    </div>

    <label class="switch-row">
      <span class="switch-copy">
        <b>敏感肌</b>
        <em>容易泛红、刺痛，或对香精敏感</em>
      </span>
      <span class="switch-state" :class="{ on: current.sensitive }">
        {{ current.sensitive ? '是' : '否' }}
      </span>
      <span class="switch" :class="{ on: current.sensitive }">
        <input
          class="sr-only"
          type="checkbox"
          role="switch"
          :checked="!!current.sensitive"
          aria-label="敏感肌"
          @change="patch({ sensitive: !current.sensitive })"
        />
        <span class="knob"></span>
      </span>
    </label>

    <label class="switch-row">
      <span class="switch-copy">
        <b>孕期 / 备孕</b>
        <em>维 A 类等成分需要规避</em>
      </span>
      <span class="switch-state" :class="{ on: current.pregnant }">
        {{ current.pregnant ? '是' : '否' }}
      </span>
      <span class="switch" :class="{ on: current.pregnant }">
        <input
          class="sr-only"
          type="checkbox"
          role="switch"
          :checked="!!current.pregnant"
          aria-label="孕期或备孕"
          @change="patch({ pregnant: !current.pregnant })"
        />
        <span class="knob"></span>
      </span>
    </label>
  </div>
</template>

<style scoped>
.profile-controls { display: flex; flex-direction: column; gap: 4px; }

.presets {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 10px;
}

.presets-label { font-size: 11.5px; color: var(--ink3); margin-right: 2px; }

.preset {
  padding: 4px 11px;
  border: 1px dashed var(--line2);
  border-radius: 999px;
  background: #FBF9F4;
  color: var(--ink2);
  font-size: 12px;
  line-height: 1.6;
  transition: all .16s;
}

.preset:hover { border-color: var(--accent-line); color: var(--accent-ink); }

.preset.on {
  border-style: solid;
  border-color: var(--accent-line);
  background: var(--accent-soft);
  color: var(--accent-ink);
  font-weight: 600;
}

.field {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px 0;
  border-bottom: 1px dashed var(--line);
}

.flabel {
  width: 64px;
  flex-shrink: 0;
  font-size: 13px;
  color: var(--ink2);
}

.seg {
  display: flex;
  gap: 3px;
  padding: 3px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #F7F4ED;
}

.seg-item {
  position: relative;
  padding: 4px 13px;
  border-radius: 7px;
  color: var(--ink2);
  font-size: 13px;
  line-height: 1.6;
  cursor: pointer;
  transition: background .16s, color .16s, box-shadow .16s;
}

.seg-item:hover { color: var(--ink); }

.seg-item.on {
  background: var(--card);
  color: var(--ink);
  font-weight: 600;
  box-shadow: var(--shadow-sm);
}

.seg-item:focus-within { box-shadow: var(--focus); }

.switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 11px 0;
  border-bottom: 1px dashed var(--line);
  cursor: pointer;
}

.switch-row:last-child { border-bottom: none; }

.switch-copy { display: flex; flex-direction: column; gap: 1px; }

.switch-copy b { font-size: 13.5px; font-weight: 500; color: var(--ink); }

.switch-copy em {
  font-style: normal;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--ink3);
}

.switch-state {
  margin-left: auto;
  font-size: 12px;
  color: var(--ink3);
  transition: color .16s;
}

.switch-state.on { color: var(--accent); font-weight: 600; }

.switch {
  position: relative;
  width: 46px;
  height: 26px;
  flex-shrink: 0;
  border-radius: 999px;
  border: 1px solid rgba(26, 23, 19, .06);
  background: #E2DBCE;
  transition: background .2s;
}

.switch.on { background: var(--accent); }

.switch:focus-within { box-shadow: var(--focus); }

.knob {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, .22);
  transition: transform .22s cubic-bezier(.34, 1.56, .64, 1);
}

.switch.on .knob { transform: translateX(20px); }

/* 报告页的紧凑模式：一行放下肤质和两个开关 */
.compact {
  flex-direction: row;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 20px;
}

.compact .field {
  padding: 0;
  border-bottom: none;
  gap: 10px;
}

.compact .flabel { width: auto; font-size: 12.5px; }

.compact .seg { padding: 2px; }
.compact .seg-item { padding: 3px 10px; font-size: 12px; }

.compact .switch-row {
  padding: 0;
  border-bottom: none;
  gap: 8px;
}

.compact .switch-copy b { font-size: 13px; white-space: nowrap; }

.compact .switch-copy em { display: none; }

.compact .switch-state { margin-left: 0; font-size: 11.5px; }

.compact .switch { width: 40px; height: 23px; }
.compact .knob { top: 2px; left: 2px; width: 17px; height: 17px; }
.compact .switch.on .knob { transform: translateX(17px); }

@media (max-width: 420px) {
  .seg-item { padding: 7px 10px; }
  .field { gap: 10px; }
  .flabel { width: 52px; }
  .compact .flabel { width: auto; }
  .compact .seg-item { padding: 3px 8px; }
}
</style>
