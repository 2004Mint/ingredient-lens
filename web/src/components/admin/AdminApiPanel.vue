<script setup>
import { computed, inject, onMounted, reactive, ref } from 'vue'

const api = inject('adminApi')
const toast = inject('adminToast')
const emit = defineEmits(['saved'])

const loading = ref(true)
const loadError = ref('')
const snapshot = ref(null)
const passwordSource = ref('')

const saving = ref(false)
const saveMessage = ref('')
const saveError = ref('')

const form = reactive({
  defaultApi: { baseUrl: '', apiKey: '', model: '' },
  vision: { useDefault: false, baseUrl: '', apiKey: '', model: '' },
  research: { useDefault: false, baseUrl: '', apiKey: '', model: '' }
})
const showKey = reactive({ defaultApi: false, vision: false, research: false })

const models = ref([])
const modelsLoading = ref(false)
const modelsError = ref('')
const modelFilter = ref('')

const visionTesting = ref(false)
const visionReply = ref('')
const visionTestError = ref('')

const researchName = ref('烟酰胺')
const researchTesting = ref(false)
const researchResult = ref(null)
const researchTestError = ref('')

const visionEffective = computed(() => snapshot.value?.vision?.effective || {})
const researchEffective = computed(() => snapshot.value?.research?.effective || {})
const defaultMasked = computed(() => snapshot.value?.defaultApi?.keyMasked || '')
const visionMasked = computed(() => snapshot.value?.vision?.keyMasked || '')
const researchMasked = computed(() => snapshot.value?.research?.keyMasked || '')

const currentVisionLooksImage = computed(() => {
  const m = (visionEffective.value.model || '').toLowerCase()
  return /image|dall-e|imagen|flux|stable-diffusion/.test(m)
})

const filteredModels = computed(() => {
  const q = modelFilter.value.trim().toLowerCase()
  if (!q) return models.value
  return models.value.filter((m) => String(m.id).toLowerCase().includes(q) || String(m.owner || '').toLowerCase().includes(q))
})

async function loadAi() {
  loading.value = true
  loadError.value = ''
  try {
    const data = await api('/ai')
    snapshot.value = data
    passwordSource.value = data.passwordSource || ''
    applySnapshot(data)
  } catch (err) {
    loadError.value = err.message
  } finally {
    loading.value = false
  }
}

function applySnapshot(data) {
  const def = data.defaultApi || {}
  const vis = data.vision || {}
  const res = data.research || {}

  form.defaultApi.baseUrl = def.baseUrl || ''
  form.defaultApi.model = def.model || ''
  form.defaultApi.apiKey = ''

  form.vision.useDefault = !!vis.usingDefault
  form.vision.baseUrl = vis.baseUrl || ''
  form.vision.model = vis.model || ''
  form.vision.apiKey = ''

  form.research.useDefault = !!res.usingDefault
  form.research.baseUrl = res.baseUrl || ''
  form.research.model = res.model || ''
  form.research.apiKey = ''
}

async function saveAll() {
  saving.value = true
  saveError.value = ''
  saveMessage.value = ''
  try {
    const data = await api('/ai', {
      method: 'PUT',
      body: {
        defaultApi: {
          baseUrl: form.defaultApi.baseUrl,
          apiKey: form.defaultApi.apiKey,
          model: form.defaultApi.model
        },
        vision: {
          useDefault: form.vision.useDefault,
          baseUrl: form.vision.useDefault ? '' : form.vision.baseUrl,
          apiKey: form.vision.useDefault ? '' : form.vision.apiKey,
          model: form.vision.useDefault ? '' : form.vision.model
        },
        research: {
          useDefault: form.research.useDefault,
          baseUrl: form.research.useDefault ? '' : form.research.baseUrl,
          apiKey: form.research.useDefault ? '' : form.research.apiKey,
          model: form.research.useDefault ? '' : form.research.model
        }
      }
    })
    snapshot.value = data
    applySnapshot(data)
    saveMessage.value = '已保存并立即生效。留空的字段会回退到总 API。'
    emit('saved')
  } catch (err) {
    saveError.value = err.message
  } finally {
    saving.value = false
  }
}

async function fetchModels() {
  modelsLoading.value = true
  modelsError.value = ''
  modelFilter.value = ''
  try {
    const data = await api('/ai/models', {
      method: 'POST',
      body: { baseUrl: form.defaultApi.baseUrl, apiKey: form.defaultApi.apiKey }
    })
    models.value = Array.isArray(data.models) ? data.models : []
    if (!models.value.length) modelsError.value = '服务返回了空列表，可以手动填写模型名。'
  } catch (err) {
    modelsError.value = err.message
  } finally {
    modelsLoading.value = false
  }
}

function pickModel(m) {
  form.defaultApi.model = m.id
  toast('已填入总 API 模型：' + m.id)
}

function makeTestImage() {
  const canvas = document.createElement('canvas')
  canvas.width = 680
  canvas.height = 240
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('当前浏览器无法生成测试图片')

  ctx.fillStyle = '#ffffff'
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.fillStyle = '#111111'
  ctx.font = '600 30px "Microsoft YaHei", "PingFang SC", sans-serif'
  ctx.fillText('成分表：水、甘油、烟酰胺、', 36, 92)
  ctx.fillText('苯氧乙醇、香精、柠檬烯', 36, 150)
  ctx.fillStyle = '#777777'
  ctx.font = '20px sans-serif'
  ctx.fillText('COSME LABEL TEST', 36, 205)
  return canvas.toDataURL('image/jpeg', 0.9)
}

async function testVision() {
  visionTesting.value = true
  visionReply.value = ''
  visionTestError.value = ''
  try {
    const data = await api('/vision/test', {
      method: 'POST',
      body: {
        baseUrl: form.vision.useDefault ? '' : form.vision.baseUrl,
        apiKey: form.vision.useDefault ? '' : form.vision.apiKey,
        model: form.vision.useDefault ? '' : form.vision.model,
        image: makeTestImage(),
        mime: 'image/jpeg'
      }
    })
    visionReply.value = data.reply || '(模型返回了空内容)'
    toast('视觉测试已返回，请检查识别结果')
  } catch (err) {
    visionTestError.value = err.message
  } finally {
    visionTesting.value = false
  }
}

async function testResearch() {
  if (!researchName.value.trim()) {
    researchTestError.value = '请填写要测试的成分名'
    return
  }
  researchTesting.value = true
  researchResult.value = null
  researchTestError.value = ''
  try {
    researchResult.value = await api('/ai/research/test', {
      method: 'POST',
      body: { name: researchName.value.trim() }
    })
    toast('研究测试已返回结构化结果')
  } catch (err) {
    researchTestError.value = err.message
  } finally {
    researchTesting.value = false
  }
}

function researchDraft() {
  return researchResult.value?.draft || {}
}

onMounted(loadAi)
</script>

<template>
  <section>
    <header class="ac-page-head">
      <div>
        <p class="ac-kicker">INTEGRATIONS</p>
        <h1 class="ac-page-title">API 与模型</h1>
        <p class="ac-page-sub">
          总 API 是所有功能的默认值；视觉 / 研究留空的字段逐项回退到总 API。保存后立即生效，不用重启。
        </p>
      </div>
      <div class="ac-actions">
        <button type="button" class="ac-btn primary" :disabled="saving || loading" @click="saveAll">
          <i v-if="saving" class="ac-spinner"></i>
          {{ saving ? '保存中…' : '保存全部配置' }}
        </button>
      </div>
    </header>

    <div v-if="loading" class="ac-card ac-card-pad">
      <div class="ac-skeleton" style="height: 18px; width: 40%"></div>
      <div class="ac-skeleton" style="height: 12px; width: 70%; margin-top: 10px"></div>
      <div class="ac-skeleton" style="height: 120px; margin-top: 16px"></div>
    </div>

    <div v-else-if="loadError" class="ac-card ac-card-pad">
      <p class="ac-alert error">{{ loadError }}</p>
      <button type="button" class="ac-btn" style="margin-top: 10px" @click="loadAi">重试</button>
    </div>

    <template v-else>
      <p v-if="saveMessage" class="ac-alert ok" style="margin-bottom: 12px">{{ saveMessage }}</p>
      <p v-if="saveError" class="ac-alert error" style="margin-bottom: 12px">{{ saveError }}</p>

      <p class="ac-alert" style="margin-bottom: 12px">
        测试按钮使用<strong>已保存</strong>的生效配置；改了地址或模型后先点「保存全部配置」再测试。
        配置文件：<span class="ac-mono">{{ snapshot?.configFile || 'server/data/ai-settings.json' }}</span>
      </p>

      <!-- 总 API -->
      <section class="ac-card ac-card-pad api-card">
        <div class="ac-section-title">
          <h2>总 API <span class="ac-badge neutral">默认</span></h2>
          <span>所有功能的兜底配置</span>
        </div>
        <div class="ac-form-grid">
          <label class="ac-field full">
            <span>Base URL</span>
            <input v-model="form.defaultApi.baseUrl" class="ac-input" spellcheck="false" placeholder="https://api.openai.com/v1" />
            <em>OpenAI 兼容地址；带不带 /v1 都行。</em>
          </label>

          <label class="ac-field full">
            <span>总 API Key</span>
            <div class="key-row">
              <input
                v-model="form.defaultApi.apiKey"
                class="ac-input"
                :type="showKey.defaultApi ? 'text' : 'password'"
                :placeholder="defaultMasked ? `留空不修改（当前 ${defaultMasked}）` : '粘贴总 API 的 Key'"
                autocomplete="off"
                spellcheck="false"
              />
              <button type="button" class="ac-btn" @click="showKey.defaultApi = !showKey.defaultApi">
                {{ showKey.defaultApi ? '隐藏' : '显示' }}
              </button>
            </div>
          </label>

          <label class="ac-field">
            <span>默认模型</span>
            <input v-model="form.defaultApi.model" class="ac-input" spellcheck="false" placeholder="例如 deepseek-chat" />
          </label>

          <div class="ac-field">
            <span>模型列表</span>
            <button type="button" class="ac-btn" :disabled="modelsLoading" @click="fetchModels">
              <i v-if="modelsLoading" class="ac-spinner"></i>
              {{ modelsLoading ? '拉取中…' : '从总 API 拉取' }}
            </button>
          </div>
        </div>

        <p v-if="modelsError" class="ac-alert warn" style="margin-top: 10px">{{ modelsError }}</p>

        <div v-if="models.length" class="model-panel">
          <div class="model-panel-head">
            <span>返回 {{ models.length }} 个模型</span>
            <input v-model="modelFilter" class="ac-input" placeholder="筛选模型名" spellcheck="false" />
          </div>
          <div class="model-list">
            <button
              v-for="m in filteredModels.slice(0, 80)"
              :key="m.id"
              type="button"
              class="model-row"
              :class="{ on: form.defaultApi.model === m.id }"
              @click="pickModel(m)"
            >
              <span class="ac-mono model-id">{{ m.id }}</span>
              <span class="model-owner">{{ m.owner || '—' }}</span>
              <span v-if="m.imageGeneration" class="ac-badge warn">画图</span>
            </button>
            <p v-if="!filteredModels.length" class="ac-empty" style="padding: 18px">没有匹配的模型</p>
          </div>
        </div>
      </section>

      <div class="ac-grid-2 api-grid">
        <!-- 视觉 API -->
        <section class="ac-card ac-card-pad api-card">
          <div class="ac-section-title">
            <h2>视觉 API</h2>
            <span class="ac-badge" :class="visionEffective.enabled ? 'good' : 'neutral'">
              {{ visionEffective.enabled ? '已配置' : '未配置' }}
            </span>
          </div>
          <p class="api-note">
            拍照识别需要多模态模型。留空的字段回退总 API；如果总 API 是文本模型，请在这里单独填视觉模型。
          </p>

          <label class="ac-check" style="margin: 8px 0">
            <input v-model="form.vision.useDefault" type="checkbox" />
            全部使用总 API（清空视觉覆盖）
          </label>

          <div class="ac-form-grid" :class="{ disabled: form.vision.useDefault }">
            <label class="ac-field full">
              <span>Base URL</span>
              <input v-model="form.vision.baseUrl" class="ac-input" :disabled="form.vision.useDefault" placeholder="留空 = 总 API" />
            </label>
            <label class="ac-field full">
              <span>视觉 API Key</span>
              <div class="key-row">
                <input
                  v-model="form.vision.apiKey"
                  class="ac-input"
                  :type="showKey.vision ? 'text' : 'password'"
                  :disabled="form.vision.useDefault"
                  :placeholder="visionMasked ? `留空保留（当前 ${visionMasked}）` : '留空 = 总 API Key'"
                  autocomplete="off"
                  spellcheck="false"
                />
                <button type="button" class="ac-btn" :disabled="form.vision.useDefault" @click="showKey.vision = !showKey.vision">
                  {{ showKey.vision ? '隐藏' : '显示' }}
                </button>
              </div>
            </label>
            <label class="ac-field full">
              <span>视觉模型</span>
              <input v-model="form.vision.model" class="ac-input" :disabled="form.vision.useDefault" placeholder="留空 = 总 API 模型；必须是看图模型" />
            </label>
          </div>

          <div class="effective-box">
            <span>当前生效</span>
            <b>{{ visionEffective.model || '未配置模型' }}</b>
            <em>{{ visionEffective.enabled ? '可用' : '不可用' }} · {{ visionEffective.baseUrl || '无地址' }}</em>
            <em v-if="visionEffective.keyMasked">Key {{ visionEffective.keyMasked }}</em>
          </div>

          <p v-if="currentVisionLooksImage" class="ac-alert warn" style="margin-top: 10px">
            当前视觉模型名看起来像画图模型。画图模型不能识别成分表，请换成 `vl` / `vision` / `4o` 这类多模态模型。
          </p>

          <div class="ac-actions" style="margin-top: 10px">
            <button type="button" class="ac-btn" :disabled="visionTesting" @click="testVision">
              <i v-if="visionTesting" class="ac-spinner"></i>
              {{ visionTesting ? '测试中…' : '测试视觉' }}
            </button>
            <span class="api-note">用内置假标签真实调用；测试会产生一次模型费用。</span>
          </div>

          <div v-if="visionReply || visionTestError" class="test-result" :class="{ failed: visionTestError }">
            <b>{{ visionTestError ? '测试失败' : '识别返回' }}</b>
            <pre>{{ visionTestError || visionReply }}</pre>
          </div>
        </section>

        <!-- 研究 API -->
        <section class="ac-card ac-card-pad api-card">
          <div class="ac-section-title">
            <h2>研究 API</h2>
            <span class="ac-badge" :class="researchEffective.enabled ? 'good' : 'neutral'">
              {{ researchEffective.enabled ? '已配置' : '未配置' }}
            </span>
          </div>
          <p class="api-note">
            成分 AI 研究使用文本模型，要求能稳定输出 JSON。留空字段回退总 API；扫描未知成分会自动走这里。
          </p>

          <label class="ac-check" style="margin: 8px 0">
            <input v-model="form.research.useDefault" type="checkbox" />
            全部使用总 API（清空研究覆盖）
          </label>

          <div class="ac-form-grid" :class="{ disabled: form.research.useDefault }">
            <label class="ac-field full">
              <span>Base URL</span>
              <input v-model="form.research.baseUrl" class="ac-input" :disabled="form.research.useDefault" placeholder="留空 = 总 API" />
            </label>
            <label class="ac-field full">
              <span>研究 API Key</span>
              <div class="key-row">
                <input
                  v-model="form.research.apiKey"
                  class="ac-input"
                  :type="showKey.research ? 'text' : 'password'"
                  :disabled="form.research.useDefault"
                  :placeholder="researchMasked ? `留空保留（当前 ${researchMasked}）` : '留空 = 总 API Key'"
                  autocomplete="off"
                  spellcheck="false"
                />
                <button type="button" class="ac-btn" :disabled="form.research.useDefault" @click="showKey.research = !showKey.research">
                  {{ showKey.research ? '隐藏' : '显示' }}
                </button>
              </div>
            </label>
            <label class="ac-field full">
              <span>研究模型</span>
              <input v-model="form.research.model" class="ac-input" :disabled="form.research.useDefault" placeholder="留空 = 总 API 模型；建议用支持 JSON 的文本模型" />
            </label>
          </div>

          <div class="effective-box">
            <span>当前生效</span>
            <b>{{ researchEffective.model || '未配置模型' }}</b>
            <em>{{ researchEffective.enabled ? '可用' : '不可用' }} · {{ researchEffective.baseUrl || '无地址' }}</em>
            <em v-if="researchEffective.keyMasked">Key {{ researchEffective.keyMasked }}</em>
          </div>

          <div class="research-test">
            <label class="ac-field">
              <span>测试成分名</span>
              <input v-model="researchName" class="ac-input" placeholder="例如 烟酰胺" />
            </label>
            <button type="button" class="ac-btn" :disabled="researchTesting" @click="testResearch">
              <i v-if="researchTesting" class="ac-spinner"></i>
              {{ researchTesting ? '研究中…' : '测试研究' }}
            </button>
          </div>

          <div v-if="researchTestError" class="test-result failed" style="margin-top: 10px">
            <b>测试失败</b>
            <pre>{{ researchTestError }}</pre>
          </div>

          <div v-if="researchResult" class="research-result">
            <div class="rr-head">
              <b>{{ researchDraft().name || '—' }}</b>
              <span class="ac-badge override">AI {{ Math.round(Number(researchResult.confidence || 0) * 100) }}%</span>
            </div>
            <dl class="ac-kv">
              <dt>角色 / 分类</dt><dd>{{ researchDraft().role || '—' }} · {{ researchDraft().cat || '—' }}</dd>
              <dt>作用</dt><dd>{{ researchDraft().function || '—' }}</dd>
              <dt>深度描述</dt><dd class="rr-desc">{{ researchDraft().description || '—' }}</dd>
              <dt>孕期 / 敏感</dt><dd>{{ researchDraft().preg || '—' }} / {{ researchDraft().sensitive || '—' }}</dd>
              <dt>致痘 / 刺激</dt><dd>{{ researchDraft().comedo ?? '—' }} / {{ researchDraft().irrit || '—' }}</dd>
              <dt>依据</dt><dd>{{ researchResult.evidence || '—' }}</dd>
            </dl>
          </div>
        </section>
      </div>

      <p class="api-foot">
        管理密码来源：{{ passwordSource || '—' }} · 配置更新：{{ snapshot?.updatedAt || '—' }}
      </p>
    </template>
  </section>
</template>

<style scoped>
.api-card { margin-top: 12px; }
.api-grid { margin-top: 12px; }
.api-grid .api-card { margin-top: 0; }

.key-row { display: flex; align-items: center; gap: 7px; }
.key-row .ac-input { flex: 1; min-width: 0; }
.key-row .ac-btn { flex-shrink: 0; height: 34px; }

.api-note { color: var(--ac-muted); font-size: 10.5px; line-height: 1.7; }

.api-foot {
  margin-top: 14px;
  color: var(--ac-muted-2);
  font-size: 10px;
  word-break: break-all;
}

.ac-form-grid.disabled { opacity: .55; }

.model-panel { margin-top: 12px; border: 1px solid var(--ac-line); border-radius: 8px; overflow: hidden; }

.model-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 11px;
  background: #FAF8F3;
  border-bottom: 1px solid var(--ac-line-soft);
  font-size: 10.5px;
  color: var(--ac-muted);
}

.model-panel-head .ac-input { width: 220px; height: 28px; }

.model-list { max-height: 240px; overflow-y: auto; }

.model-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 11px;
  border-bottom: 1px solid var(--ac-line-soft);
  text-align: left;
}

.model-row:last-child { border-bottom: none; }
.model-row:hover { background: #FBF9F4; }
.model-row.on { background: var(--accent-soft); }
.model-id { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 10.5px; }
.model-owner { max-width: 140px; overflow: hidden; color: var(--ac-muted-2); font-size: 9.5px; text-overflow: ellipsis; white-space: nowrap; }

.effective-box {
  margin-top: 10px;
  padding: 9px 11px;
  border: 1px solid var(--ac-line-soft);
  border-radius: 7px;
  background: #FAF8F2;
  font-size: 10.5px;
  line-height: 1.7;
}

.effective-box span { color: var(--ac-muted-2); margin-right: 7px; }
.effective-box b { color: var(--ink2); font-weight: 600; }
.effective-box em { display: block; color: var(--ac-muted-2); font-style: normal; }

.research-test {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  margin-top: 10px;
}

.research-test .ac-field { flex: 1; }

.test-result {
  margin-top: 10px;
  padding: 10px;
  border: 1px solid var(--ac-line);
  border-radius: 7px;
  background: #FAF8F2;
}

.test-result.failed { border-color: var(--danger-line); background: var(--danger-soft); }
.test-result b { font-size: 10.5px; }
.test-result pre {
  max-height: 160px;
  margin-top: 4px;
  overflow: auto;
  color: var(--ink2);
  font-family: inherit;
  font-size: 10.5px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.research-result {
  margin-top: 10px;
  padding: 11px;
  border: 1px solid var(--accent-line);
  border-radius: 8px;
  background: var(--accent-soft);
}

.rr-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 6px; }
.rr-head b { font-size: 12.5px; color: var(--accent-ink); }

.rr-desc { line-height: 1.75; white-space: pre-wrap; }

@media (max-width: 720px) {
  .model-panel-head { flex-wrap: wrap; }
  .model-panel-head .ac-input { width: 100%; }
  .model-owner { display: none; }
  .research-test { flex-wrap: wrap; }
  .research-test .ac-btn { width: 100%; }
}
</style>
