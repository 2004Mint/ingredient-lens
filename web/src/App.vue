<script setup>
import { ref, onMounted, computed } from 'vue'
import ReportView from './components/ReportView.vue'

const text = ref('')
const loading = ref(false)
const error = ref('')
const report = ref(null)
const samples = ref([])
const visionEnabled = ref(false)
const step = ref(0)

const profile = ref({ skin: 'oily', sensitive: true, pregnant: false })
const SKINS = [
  { k: 'dry', label: '干性' },
  { k: 'oily', label: '油性' },
  { k: 'combo', label: '混合' },
  { k: 'normal', label: '中性' }
]

const STEPS = ['读取成分表', '匹配成分知识库', '运行法规与个体化规则', '生成报告']

const charCount = computed(() => text.value.replace(/[、，,\s]/g, '').length)

onMounted(async () => {
  try {
    const [cfg, sm] = await Promise.all([
      fetch('/api/config').then(r => r.json()),
      fetch('/api/samples').then(r => r.json())
    ])
    visionEnabled.value = !!cfg.visionEnabled
    samples.value = sm
    // ?demo=1 直接跑通一条示例，方便录屏与演示
    if (new URLSearchParams(location.search).has('demo') && sm.length) {
      text.value = sm[0].text
      await analyze()
    }
  } catch (e) {
    /* 后端未启动时保持静默，用户点按钮会看到明确错误 */
  }
})

async function analyze() {
  if (!text.value.trim() || loading.value) return
  loading.value = true
  error.value = ''
  report.value = null
  step.value = 0
  const t0 = Date.now()
  const timer = setInterval(() => {
    if (step.value < STEPS.length - 1) step.value += 1
  }, 260)

  try {
    const res = await fetch('/api/analyze', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: text.value, profile: profile.value })
    })
    const json = await res.json()
    if (!res.ok) throw new Error(json.error || '分析失败')
    // 本地计算很快，保留一点最短展示时间，避免画面闪一下就看不清
    const wait = Math.max(0, 620 - (Date.now() - t0))
    await new Promise(r => setTimeout(r, wait))
    report.value = json
  } catch (e) {
    error.value = e.message || '无法连接后端，请确认服务已启动'
  } finally {
    clearInterval(timer)
    loading.value = false
  }
}

function useSample(s) {
  text.value = s.text
  report.value = null
  error.value = ''
}

function reset() {
  report.value = null
  text.value = ''
}

async function onFile(e) {
  const file = e.target.files?.[0]
  if (!file) return
  loading.value = true
  error.value = ''
  try {
    const dataUrl = await new Promise((res, rej) => {
      const fr = new FileReader()
      fr.onload = () => res(fr.result)
      fr.onerror = rej
      fr.readAsDataURL(file)
    })
    const res = await fetch('/api/vision', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ image: dataUrl, mime: file.type })
    })
    const json = await res.json()
    if (!res.ok) throw new Error(json.error || '识别失败')
    text.value = json.text
  } catch (err) {
    error.value = err.message
  } finally {
    loading.value = false
    e.target.value = ''
  }
}
</script>

<template>
  <div class="page">
    <header class="hd">
      <div class="brand">
        <span class="logo serif">成分镜</span>
        <span class="tag">拍一下，看懂这瓶到底适不适合你</span>
      </div>
    </header>

    <template v-if="!report">
      <section class="card intake">
        <div class="row-between">
          <h2 class="h2 serif">这一瓶的成分表</h2>
          <label v-if="visionEnabled" class="ghost-btn">
            拍照识别
            <input type="file" accept="image/*" hidden @change="onFile" />
          </label>
        </div>

        <textarea
          v-model="text"
          class="ta"
          rows="5"
          placeholder="把包装背面的成分表抄或粘进来，例如：水、甘油、烟酰胺、苯氧乙醇…"
        ></textarea>

        <div class="meta">
          <span>{{ charCount }} 字</span>
          <span v-if="!visionEnabled" class="dim">未配置视觉模型时，可先复制成分表文本</span>
        </div>

        <div class="samples" v-if="samples.length">
          <span class="dim">没有实物？试试：</span>
          <button v-for="s in samples" :key="s.name" class="chip" @click="useSample(s)">
            {{ s.name }}
          </button>
        </div>
      </section>

      <section class="card profile">
        <h2 class="h2 serif">先告诉我你的情况</h2>
        <p class="hint">同一瓶东西，油皮和干皮的结论可能完全相反。</p>

        <div class="field">
          <span class="flabel">肤质</span>
          <div class="seg">
            <button
              v-for="s in SKINS" :key="s.k"
              class="seg-btn" :class="{ on: profile.skin === s.k }"
              @click="profile.skin = s.k"
            >{{ s.label }}</button>
          </div>
        </div>

        <div class="field">
          <span class="flabel">敏感肌</span>
          <button class="toggle" :class="{ on: profile.sensitive }" @click="profile.sensitive = !profile.sensitive">
            <span class="knob"></span>
            <span class="tlabel">{{ profile.sensitive ? '是' : '否' }}</span>
          </button>
        </div>

        <div class="field">
          <span class="flabel">孕期 / 备孕</span>
          <button class="toggle" :class="{ on: profile.pregnant }" @click="profile.pregnant = !profile.pregnant">
            <span class="knob"></span>
            <span class="tlabel">{{ profile.pregnant ? '是' : '否' }}</span>
          </button>
        </div>
      </section>

      <p v-if="error" class="err">{{ error }}</p>

      <button class="cta" :disabled="loading || !text.trim()" @click="analyze">
        <span v-if="!loading">开始解读</span>
        <span v-else class="loading">{{ STEPS[step] }}…</span>
      </button>

      <ul v-if="loading" class="steps">
        <li v-for="(s, i) in STEPS" :key="s" :class="{ done: i < step, now: i === step }">{{ s }}</li>
      </ul>
    </template>

    <ReportView v-else :data="report" @reset="reset" />

    <footer class="foot">
      本工具只做标签信息解读，不评价商品优劣，不构成医疗或用药建议。
    </footer>
  </div>
</template>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 36px 20px 64px; }

.hd { margin-bottom: 26px; }
.brand { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; }
.logo { font-size: 25px; font-weight: 700; color: var(--ink); letter-spacing: 0.04em; }
.tag { font-size: 12.5px; color: var(--ink3); }

.card {
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 22px;
  margin-bottom: 14px;
}

.h2 { font-size: 17px; font-weight: 600; margin-bottom: 14px; }
.row-between { display: flex; justify-content: space-between; align-items: center; }
.hint { font-size: 12.5px; color: var(--ink3); margin: -8px 0 16px; }

.ta {
  width: 100%;
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 14px;
  font-size: 14px;
  line-height: 1.9;
  color: var(--ink);
  background: #FCFBF9;
  resize: vertical;
  outline: none;
  transition: border-color .18s;
}
.ta:focus { border-color: var(--accent); }
.ta::placeholder { color: var(--ink3); }

.meta { display: flex; justify-content: space-between; font-size: 12px; color: var(--ink3); margin-top: 8px; }
.dim { color: var(--ink3); font-size: 12.5px; }

.samples { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-top: 14px; padding-top: 14px; border-top: 1px dashed var(--line); }
.chip {
  font-size: 12.5px; color: var(--accent);
  background: var(--accent-soft);
  padding: 5px 12px; border-radius: 20px;
  transition: background .18s;
}
.chip:hover { background: #DBEBE6; }

.ghost-btn {
  font-size: 12.5px; color: var(--accent);
  border: 1px solid #CFE0DB; padding: 6px 14px; border-radius: 8px;
  cursor: pointer;
}

.field { display: flex; align-items: center; gap: 16px; padding: 10px 0; border-bottom: 1px solid #F2EFE9; }
.field:last-child { border-bottom: none; }
.flabel { font-size: 13.5px; color: var(--ink2); width: 92px; flex-shrink: 0; }

.seg { display: flex; gap: 6px; }
.seg-btn {
  font-size: 13px; color: var(--ink2);
  padding: 5px 16px; border-radius: 8px;
  background: #F6F3EE;
  transition: all .16s;
}
.seg-btn.on { background: var(--accent); color: #fff; }

.toggle {
  position: relative; width: 52px; height: 28px; border-radius: 20px;
  background: #E6E1D8; transition: background .2s;
}
.toggle.on { background: var(--accent); }
.knob {
  position: absolute; top: 3px; left: 3px;
  width: 22px; height: 22px; border-radius: 50%;
  background: #fff; transition: transform .2s;
  box-shadow: 0 1px 3px rgba(0,0,0,.18);
}
.toggle.on .knob { transform: translateX(24px); }
.tlabel { position: absolute; right: 8px; top: 3px; font-size: 11px; color: #fff; opacity: 0; }
.toggle.on .tlabel { opacity: 1; right: auto; left: 9px; }

.cta {
  width: 100%; padding: 15px; border-radius: 12px;
  background: var(--ink); color: #fff;
  font-size: 15px; font-weight: 600; letter-spacing: 0.04em;
  transition: opacity .16s, transform .1s;
}
.cta:hover:not(:disabled) { opacity: .88; }
.cta:active:not(:disabled) { transform: scale(.995); }
.cta:disabled { opacity: .32; cursor: not-allowed; }
.loading { opacity: .9; }

.steps { list-style: none; margin-top: 14px; }
.steps li { font-size: 12px; color: var(--ink3); padding: 3px 0; opacity: .4; transition: opacity .25s; }
.steps li.now, .steps li.done { opacity: 1; }
.steps li.done { color: var(--good); }
.steps li.now { color: var(--accent); }

.err {
  background: var(--danger-soft); color: var(--danger);
  border: 1px solid #F0D2CC; border-radius: 10px;
  padding: 12px 16px; font-size: 13px; margin-bottom: 14px;
}

.foot { margin-top: 32px; font-size: 11.5px; color: var(--ink3); text-align: center; line-height: 1.9; }
</style>
