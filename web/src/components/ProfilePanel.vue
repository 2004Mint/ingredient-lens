<script setup>
import ProfileControls from './ProfileControls.vue'
import { profileLabel } from '../lib/text.js'

const props = defineProps({
  modelValue: { type: Object, required: true },
  loading: { type: Boolean, default: false },
  phase: { type: Number, default: 0 },
  steps: { type: Array, default: () => [] },
  error: { type: String, default: '' },
  canSubmit: { type: Boolean, default: false },
  hasText: { type: Boolean, default: false },
  recognizing: { type: Boolean, default: false }
})

defineEmits(['update:modelValue', 'submit', 'retry'])
</script>

<template>
  <section class="card profile">
    <div class="head">
      <span class="eyebrow">第二步</span>
      <h2 class="h2 serif">先告诉我你的情况</h2>
      <p class="hint">同一瓶东西，油皮和干皮的结论可能完全相反。</p>
    </div>

    <ProfileControls
      :model-value="modelValue"
      @update:model-value="$emit('update:modelValue', $event)"
    />

    <div class="cta-wrap">
      <p class="cta-note">
        将按「<b>{{ profileLabel(modelValue) }}</b>」解读
      </p>

      <button
        type="button"
        class="cta"
        :class="{ loading: loading || recognizing }"
        :disabled="!canSubmit"
        :aria-busy="loading || recognizing"
        @click="$emit('submit')"
      >
        <template v-if="recognizing">
          <i class="spinner"></i>
          正在识别照片…
        </template>
        <template v-else-if="loading">
          <i class="spinner"></i>
          {{ steps[phase] || '正在解读' }}…
        </template>
        <template v-else>
          开始解读
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M5 12h13M13 6l6 6-6 6" />
          </svg>
        </template>
      </button>

      <p v-if="recognizing" class="privacy">识别完成后会自动填到左边的输入框</p>
      <p v-else-if="!hasText && !loading" class="cta-empty">先粘贴成分表，或用上面的示例试试</p>
      <p v-else class="privacy">1 秒内出结果 · 规则引擎判定，不调用生成式模型</p>

      <div v-if="loading" class="progress" aria-live="polite">
        <div class="progress-bar">
          <i :style="{ width: ((phase + 1) / Math.max(steps.length, 1)) * 100 + '%' }"></i>
        </div>
        <ol class="steps">
          <li
            v-for="(s, i) in steps"
            :key="s"
            :class="{ done: i < phase, now: i === phase }"
          >
            <span class="dot" aria-hidden="true"></span>
            {{ s }}
          </li>
        </ol>
      </div>

      <div v-if="error" class="error" role="alert">
        <div class="error-copy">
          <b>没能完成解读</b>
          <p>{{ error }}</p>
        </div>
        <button type="button" class="btn" @click="$emit('retry')">重试</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.profile { padding: 24px; }

.head { display: flex; flex-direction: column; gap: 4px; margin-bottom: 14px; }

.head .hint { margin-top: 2px; }

.cta-wrap { margin-top: 18px; padding-top: 16px; border-top: 1px dashed var(--line); }

.cta-note { font-size: 12px; color: var(--ink3); margin-bottom: 8px; }

.cta-note b { color: var(--accent-ink); font-weight: 600; }

.cta {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  padding: 14px 18px;
  border-radius: 12px;
  background: var(--ink);
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: .04em;
  box-shadow: 0 14px 26px -20px rgba(26, 23, 19, .9);
  transition: background .18s, transform .12s, opacity .18s;
}

.cta svg {
  width: 17px;
  height: 17px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
  transition: transform .18s;
}

.cta:hover:not(:disabled) { background: #2B2722; }
.cta:hover:not(:disabled) svg { transform: translateX(3px); }
.cta:active:not(:disabled) { transform: scale(.995); }
.cta:disabled { opacity: .38; cursor: not-allowed; }

/* 加载中按钮保持实心：它现在是进度指示器，不该看起来像坏掉了 */
.cta.loading:disabled { opacity: 1; cursor: progress; background: #2B2722; }

.privacy,
.cta-empty {
  margin-top: 8px;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink3);
  text-align: center;
}

.cta-empty { color: var(--warn); }

.progress { margin-top: 14px; }

.progress-bar {
  height: 3px;
  border-radius: 999px;
  background: #EFEAE0;
  overflow: hidden;
}

.progress-bar i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, var(--accent), #4C9B87);
  transition: width .32s cubic-bezier(.22, .61, .36, 1);
}

.steps { list-style: none; margin-top: 10px; }

.steps li {
  position: relative;
  padding: 2px 0 2px 16px;
  font-size: 11.5px;
  line-height: 1.8;
  color: var(--ink3);
  opacity: .55;
  transition: opacity .2s, color .2s;
}

.steps li .dot {
  position: absolute;
  left: 0;
  top: 8px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--line2);
}

.steps li.done,
.steps li.now { opacity: 1; }
.steps li.done { color: var(--good); }
.steps li.now { color: var(--accent); font-weight: 600; }
.steps li.done .dot { background: var(--good); }
.steps li.now .dot { background: var(--accent); animation: pulse 1.2s ease-in-out infinite; }

.error {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-top: 12px;
  padding: 11px 12px;
  border: 1px solid var(--danger-line);
  border-radius: 10px;
  background: var(--danger-soft);
}

.error-copy { flex: 1; }

.error-copy b { font-size: 12.5px; font-weight: 600; color: var(--danger); }

.error-copy p { margin-top: 2px; font-size: 12px; line-height: 1.7; color: #7C3A2E; }

.error .btn { flex-shrink: 0; background: #fff; border-color: #EBC9C0; color: var(--danger); }

@media (max-width: 979px) {
  /* 移动端改用底部固定条，卡片里只保留状态和错误 */
  .cta,
  .cta-note,
  .privacy,
  .cta-empty { display: none; }

  .cta-wrap { margin-top: 0; padding-top: 0; border-top: none; }

  .progress,
  .error {
    margin-top: 12px;
    padding-top: 12px;
    border-top: 1px dashed var(--line);
  }

  .error { padding: 11px 12px; border: 1px solid var(--danger-line); }

  .profile { padding: 18px 16px; }
}
</style>
