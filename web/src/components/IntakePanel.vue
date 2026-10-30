<script setup>
import { computed } from 'vue'

const props = defineProps({
  modelValue: { type: String, default: '' },
  productName: { type: String, default: '' },
  tokens: { type: Array, default: () => [] },
  issue: { type: Object, default: null },
  samples: { type: Array, default: () => [] },
  visionEnabled: { type: Boolean, default: false },
  loading: { type: Boolean, default: false },
  recognizing: { type: Boolean, default: false },
  photo: { type: Object, default: null },
  photoNote: { type: String, default: '' },
  photoError: { type: Boolean, default: false }
})

defineEmits([
  'update:modelValue',
  'update:productName',
  'clear',
  'paste',
  'pick-file',
  'fix-space',
  'sample',
  'remove-photo',
  'use-text'
])

const charCount = computed(() => props.modelValue.replace(/\s/g, '').length)
const preview = computed(() => props.tokens.slice(0, 8))
const rest = computed(() => Math.max(0, props.tokens.length - 8))

function sampleTitle(name) {
  return String(name || '').replace(/^示例\s*·\s*/, '')
}
</script>

<template>
  <section class="card intake">
    <div class="head">
      <div class="head-copy">
        <span class="eyebrow">第一步</span>
        <h2 id="intake-title" class="h2 serif">这一瓶的成分表</h2>
      </div>

      <div class="tools">
        <button type="button" class="tool" :disabled="recognizing || loading" @click="$emit('paste')">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <rect x="8" y="4" width="11" height="15" rx="2" />
            <path d="M8 7H6.5A1.5 1.5 0 0 0 5 8.5v11A1.5 1.5 0 0 0 6.5 21H13" />
            <path d="M11 4.5h4" />
          </svg>
          粘贴
        </button>

        <button
          v-if="visionEnabled"
          type="button"
          class="tool accent"
          :disabled="recognizing || loading"
          @click="$emit('pick-file')"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M4 8.5A2.5 2.5 0 0 1 6.5 6h1.2l1.1-1.6A1.5 1.5 0 0 1 10 3.7h4a1.5 1.5 0 0 1 1.2.7L16.3 6h1.2A2.5 2.5 0 0 1 20 8.5v8A2.5 2.5 0 0 1 17.5 19h-11A2.5 2.5 0 0 1 4 16.5z" />
            <circle cx="12" cy="12.5" r="3.4" />
          </svg>
          {{ recognizing ? '识别中…' : '拍照识别' }}
        </button>

        <button
          v-if="modelValue"
          type="button"
          class="tool quiet"
          aria-label="清空成分表"
          :disabled="recognizing || loading"
          @click="$emit('clear')"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
          清空
        </button>
      </div>
    </div>

    <div class="product-row">
      <label class="product-label" for="product-name">产品名 <em>可选</em></label>
      <input
        id="product-name"
        class="product-input"
        :value="productName"
        :disabled="loading"
        maxlength="40"
        placeholder="例如：XX 烟酰胺精华 30ml"
        @input="$emit('update:productName', $event.target.value)"
      />
    </div>

    <div class="editor">
      <textarea
        class="ta"
        :value="modelValue"
        :disabled="loading"
        rows="6"
        maxlength="4000"
        spellcheck="false"
        aria-labelledby="intake-title"
        placeholder="把包装背面的成分表抄或粘进来，例如：水、甘油、烟酰胺、苯氧乙醇…"
        @input="$emit('update:modelValue', $event.target.value)"
      ></textarea>
      <div class="editor-foot">
        <span class="count tnum">{{ charCount }} 字 · {{ tokens.length }} 个成分</span>
        <span class="dim">顿号 / 逗号 / 换行都可以</span>
      </div>
    </div>

    <div v-if="tokens.length" class="parse">
      <span class="parse-label">将分析</span>
      <div class="parse-list">
        <span v-for="(t, i) in preview" :key="i" class="mini" :title="t">{{ t }}</span>
        <span v-if="rest" class="mini more">+{{ rest }}</span>
      </div>
    </div>

    <div v-if="issue" class="issue" :class="issue.level" role="status">
      <svg class="issue-icon" viewBox="0 0 20 20" aria-hidden="true">
        <circle cx="10" cy="10" r="8.2" />
        <path d="M10 6.2v5.1M10 13.9v.1" />
      </svg>
      <p>{{ issue.text }}</p>
      <button
        v-if="issue.action === 'space'"
        type="button"
        class="issue-btn"
        @click="$emit('fix-space')"
      >
        {{ issue.actionLabel }}
      </button>
    </div>

    <div v-if="photo" class="photo" :class="{ failed: photoError }">
      <div class="thumb">
        <img :src="photo.url" alt="刚拍摄或选择的成分表照片" />
        <span v-if="recognizing" class="thumb-veil">
          <i class="spinner"></i>
          识别中
        </span>
      </div>
      <div class="photo-copy">
        <b :class="{ danger: photoError }">
          {{ recognizing ? '正在读取照片里的成分表…' : (photoNote || '照片已就绪') }}
        </b>
        <span class="hint">照片会发给视觉模型服务；文字分析只在本机规则引擎里跑。</span>
      </div>
      <div class="photo-actions">
        <button
          v-if="photoError"
          type="button"
          class="btn"
          :disabled="recognizing"
          @click="$emit('pick-file')"
        >
          重试
        </button>
        <button
          v-if="photoError"
          type="button"
          class="btn"
          :disabled="recognizing"
          @click="$emit('use-text')"
        >
          改用粘贴
        </button>
        <button
          v-if="!photoError"
          type="button"
          class="btn ghost"
          :disabled="recognizing"
          @click="$emit('remove-photo')"
        >
          移除
        </button>
      </div>
    </div>

    <div v-if="samples.length" class="samples">
      <div class="samples-head">
        <span class="eyebrow">没有实物</span>
        <p class="hint">三个典型配方，分别演示概念添加、孕期禁忌与刺激性表活。</p>
      </div>
      <div class="sample-grid">
        <button
          v-for="s in samples"
          :key="s.name"
          type="button"
          class="sample"
          :disabled="loading"
          @click="$emit('sample', s)"
        >
          <span class="sample-name">{{ sampleTitle(s.name) }}</span>
          <span class="sample-hint">{{ s.hint }}</span>
          <span v-if="s.tags && s.tags.length" class="sample-tags">
            <i v-for="t in s.tags" :key="t">{{ t }}</i>
          </span>
          <span class="sample-go" aria-hidden="true">试试 →</span>
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.intake { padding: 24px 24px 20px; }

.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}

.head-copy { display: flex; flex-direction: column; gap: 4px; }

.tools { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }

.tool {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 11px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: #FBF9F4;
  color: var(--ink2);
  font-size: 12.5px;
  transition: all .16s;
}

.tool svg {
  width: 14px;
  height: 14px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.tool:hover:not(:disabled) {
  border-color: var(--line2);
  color: var(--ink);
  background: #fff;
}

.tool.accent {
  border-color: var(--accent-line);
  background: var(--accent-soft);
  color: var(--accent-ink);
  font-weight: 500;
}

.tool.accent:hover:not(:disabled) { background: #DBEBE4; border-color: var(--accent); }

.tool.quiet { border-color: transparent; background: transparent; }

.tool:disabled { opacity: .5; cursor: not-allowed; }

.product-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.product-label {
  flex-shrink: 0;
  font-size: 12.5px;
  color: var(--ink2);
}

.product-label em {
  font-style: normal;
  font-size: 10.5px;
  color: var(--ink3);
}

.product-input {
  flex: 1;
  min-width: 0;
  padding: 8px 11px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #FCFBF7;
  color: var(--ink);
  font-size: 13px;
  outline: none;
  transition: border-color .16s, box-shadow .16s;
}

.product-input:focus { border-color: var(--accent); box-shadow: var(--focus); }

.product-input:disabled { background: #FAF8F2; color: var(--ink2); cursor: progress; }

.editor {
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #FCFBF7;
  transition: border-color .18s, box-shadow .18s;
}

.editor:focus-within {
  border-color: var(--accent);
  box-shadow: var(--focus);
}

.ta {
  display: block;
  width: 100%;
  min-height: 178px;
  padding: 14px 16px 6px;
  border: none;
  border-radius: 12px 12px 0 0;
  background: transparent;
  color: var(--ink);
  font-size: 14px;
  line-height: 1.95;
  resize: vertical;
  outline: none;
}

.ta::placeholder { color: #857F74; }

.ta:disabled { color: var(--ink2); background: #FAF8F2; cursor: progress; }

.editor-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 6px 16px 10px;
  font-size: 11.5px;
  color: var(--ink3);
}

.count { font-weight: 600; color: var(--ink2); font-variant-numeric: tabular-nums; }

.dim { color: var(--ink3); font-size: 11.5px; }

.parse {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-top: 12px;
}

.parse-label {
  flex-shrink: 0;
  font-size: 11.5px;
  color: var(--ink3);
  padding-top: 3px;
}

.parse-list { display: flex; flex-wrap: wrap; gap: 5px; min-width: 0; }

.mini {
  max-width: 170px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 2px 8px;
  border-radius: 6px;
  background: #F3F0E8;
  color: var(--ink2);
  font-size: 11.5px;
  line-height: 1.8;
}

.mini.more { color: var(--accent-ink); background: var(--accent-soft); font-weight: 600; }

.issue {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  padding: 9px 12px;
  border-radius: 9px;
  font-size: 12.5px;
  line-height: 1.7;
}

.issue.warn { background: var(--warn-soft); color: #7A5210; }
.issue.info { background: #F5F2EA; color: var(--ink2); }

.issue-icon {
  width: 15px;
  height: 15px;
  flex-shrink: 0;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
}

.issue p { flex: 1; }

.issue-btn {
  flex-shrink: 0;
  padding: 3px 10px;
  border-radius: 6px;
  background: rgba(255, 255, 255, .75);
  color: #7A5210;
  font-size: 12px;
  font-weight: 600;
  transition: background .16s;
}

.issue-btn:hover { background: #fff; }

.photo {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 14px;
  padding: 10px;
  border: 1px dashed var(--line2);
  border-radius: 12px;
  background: #FBF9F4;
}

.photo.failed { border-color: var(--danger-line); background: #FDF6F3; }

.photo-actions { display: flex; gap: 6px; flex-shrink: 0; }

.thumb {
  position: relative;
  width: 62px;
  height: 62px;
  flex-shrink: 0;
  border-radius: 8px;
  overflow: hidden;
  background: #EFEAE0;
}

.thumb img { width: 100%; height: 100%; object-fit: cover; display: block; }

.thumb-veil {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  background: rgba(20, 18, 14, .55);
  color: #fff;
  font-size: 10px;
}

.photo-copy { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }

.photo-copy b { font-size: 12.5px; font-weight: 600; line-height: 1.7; color: var(--ink); }

.photo-copy b.danger { color: var(--danger); }

.samples {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px dashed var(--line);
}

.samples-head { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }

.samples-head .hint { margin: 0; }

.sample-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.sample {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 12px 12px 13px;
  border: 1px solid var(--line);
  border-radius: 11px;
  background: #FCFBF7;
  text-align: left;
  transition: border-color .18s, transform .16s, box-shadow .18s, background .18s;
}

.sample:hover {
  border-color: var(--accent-line);
  background: #fff;
  transform: translateY(-2px);
  box-shadow: var(--shadow-sm);
}

.sample:disabled { opacity: .5; cursor: not-allowed; transform: none; box-shadow: none; }

.sample-name { font-size: 13px; font-weight: 600; color: var(--ink); }

.sample-hint {
  font-size: 11.5px;
  line-height: 1.65;
  color: var(--ink3);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.sample-tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 3px; }

.sample-tags i {
  font-style: normal;
  font-size: 10.5px;
  line-height: 1.7;
  padding: 0 6px;
  border-radius: 4px;
  background: var(--accent-soft);
  color: var(--accent-ink);
}

.sample-go {
  margin-top: 4px;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--accent);
  opacity: .55;
  transform: none;
  transition: opacity .18s, transform .18s;
}

.sample:hover .sample-go,
.sample:focus-visible .sample-go { opacity: 1; transform: translateX(2px); }

@media (max-width: 720px) {
  .intake { padding: 18px 16px 16px; }

  /* 触屏上把工具按钮撑到接近 40px 高，别让人点两次 */
  .tool { padding: 8px 12px; font-size: 13px; }
  .sample-grid { grid-template-columns: 1fr; }
  .sample-hint { -webkit-line-clamp: 3; }
  .sample-go { opacity: 1; transform: none; }

  .ta { min-height: 150px; font-size: 16px; /* iOS 输入框小于 16px 会放大页面 */ }

  .photo { flex-wrap: wrap; }
  .photo-copy { flex-basis: calc(100% - 74px); }
  .photo-actions { margin-left: auto; }
}
</style>
