<script setup>
import { ref } from 'vue'
import { postJSON } from '../lib/http.js'

const props = defineProps({ version: { type: Object, default: () => ({}) } })

const message = ref('')
const contact = ref('')
const busy = ref(false)
const error = ref('')
const sent = ref(false)

async function submitFeedback() {
  if (message.value.trim().length < 5) {
    error.value = '请至少写 5 个字，方便我们定位问题'
    return
  }
  busy.value = true
  error.value = ''
  try {
    await postJSON('/api/feedback', {
      message: message.value.trim().slice(0, 1000),
      contact: contact.value.trim().slice(0, 80),
      page: 'about'
    }, 15000)
    sent.value = true
    message.value = ''
    contact.value = ''
  } catch (err) {
    error.value = err.message || '提交失败，请稍后再试'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <article class="page-view">
    <header class="page-head">
      <p class="eyebrow">关于</p>
      <h1 class="page-title serif">成分镜</h1>
      <p class="lead">
        把化妆品包装背面那片没人看得完的小字，翻译成「对你」的结论。
        这是一个只读标签、不评价商品、也不做医疗建议的工具。
      </p>
      <div class="head-meta">
        <span>v{{ version.version || '1.0.0' }}</span>
        <span>规则库 {{ version.rulesVersion || '2026.10' }}</span>
        <span>成分库 {{ version.librarySize || 60 }} 条</span>
        <span>API {{ version.apiVersion || 'v1' }}</span>
      </div>
    </header>

    <section class="card prose">
      <h2 class="h2 serif">它做什么，不做什么</h2>
      <div class="two-col">
        <div>
          <h3>做</h3>
          <ul>
            <li>按法规排序推断配方梯队，识别概念添加</li>
            <li>按肤质、敏感史、孕期状态重排风险</li>
            <li>把法规限量与禁忌翻译成能行动的话</li>
            <li>明确标出未收录与识别不足，不假装知道</li>
          </ul>
        </div>
        <div>
          <h3>不做</h3>
          <ul>
            <li>不给商品打分，不推荐买哪一瓶</li>
            <li>不替代皮肤科诊断或用药建议</li>
            <li>不让模型判断限量与禁忌</li>
            <li>不把「没认出来」包装成「没问题」</li>
          </ul>
        </div>
      </div>

      <h2 class="h2 serif">隐私与数据</h2>
      <p>
        成分镜没有账号体系，也不会把数据卖给第三方。你在页面里填写的肤质、敏感史、孕期状态、
        成分表、产品名与历史报告，默认只保存在<b>当前浏览器</b>的 localStorage 里。
      </p>
      <ul class="plain-list">
        <li><b>拍照识别</b>：只有你主动选择照片时，图片才会发给你在管理控制台配置的视觉模型服务；文字分析不经过第三方模型。</li>
        <li><b>成分 AI 研究</b>：只有配置了研究 API 时，扫描到的未收录成分名与它所在的成分表片段才会发给你配置的模型去补全。未收录 token 会先保存为本机研究任务（<code>data/research-tasks.json</code>），失败原因也留在本机，方便后台重试；结果先进入「待研究」，参与匹配并在报告注明 AI 补全。不发送肤质、孕期或分享报告内容。</li>
        <li><b>分享链接</b>：点击「分享」会把当前报告与档案保存在本机服务的 <code>server/data/reports/</code>，生成一个不可猜测的短链接。只有能访问这台电脑/服务的设备才能打开（局域网用 IP，公网需先部署）；链接 30 天后自动清理。</li>
        <li><b>管理控制台</b>：登录后可改 AI 模型、维护成分数据、查看研究任务、分享报告与反馈。成分改动写 <code>server/data/ingredients.json</code>，总 API / 视觉 / 研究配置写 <code>server/data/ai-settings.json</code>，研究任务写 <code>data/research-tasks.json</code>（环境变量方式则在系统环境变量里）；管理令牌只存在内存，目录已加入 .gitignore。</li>
        <li><b>不做的事</b>：没有广告、没有行为追踪 SDK、没有第三方数据分析平台。</li>
      </ul>

      <h2 class="h2 serif">数据来源与版本</h2>
      <div class="table-wrap">
        <table>
          <thead><tr><th>项</th><th>值</th><th>说明</th></tr></thead>
          <tbody>
            <tr><td>产品版本</td><td>v{{ version.version || '1.0.0' }}</td><td>前端 + 规则引擎</td></tr>
            <tr><td>规则库</td><td>{{ version.rulesVersion || '2026.10' }}</td><td>梯队阈值、风险分级、合并逻辑</td></tr>
            <tr><td>成分库</td><td>{{ version.libraryVersion || '2026.10' }} / {{ version.librarySize || 60 }} 条</td><td>高频成分摘录，不是完整法规库</td></tr>
            <tr><td>法规依据</td><td>《化妆品安全技术规范》</td><td>限用组分、防腐剂与防晒剂限量</td></tr>
          </tbody>
        </table>
      </div>

      <h2 class="h2 serif">免责声明</h2>
      <p class="disclaimer">
        本工具只做标签信息解读，不评价商品优劣，不构成医疗、用药或皮肤科诊断建议。
        成分库与规则库可能存在遗漏或滞后；限量与标签标注不一致时，以法规原文和产品标签为准。
        孕期、哺乳期、皮肤疾病或正在用药的情况，请先咨询医师。
      </p>

      <h2 class="h2 serif">更新日志</h2>
      <ol class="changelog">
        <li><b>v1.0.0</b><span>产品化：成分库页面、方法页、报告编号与历史、分享链接、版本与数据来源、管理控制台（模型 / 成分 / 研究任务 / 分享 / 反馈 / 系统）、成分 AI 研究、研究任务队列与报告自动刷新、总 API 回退、拍照识别产品名自动填入、报告成分详情卡、成分添加时间与排序、括号变体自动合并别名。</span></li>
        <li><b>v0.9.0</b><span>结论可信度降级（未能解读 / 信息不足）、报告内档案即时重算、移动端与无障碍打磨。</span></li>
        <li><b>v0.1.0</b><span>核心链路：成分切分、匹配、梯队推断、个体化规则与报告页。</span></li>
      </ol>

      <h2 class="h2 serif">反馈与纠错</h2>
      <p>
        发现成分名写错、限量过期、结论不合理，或者只是想说一句「这里看不懂」，都可以留在这里。
        反馈会保存在本机服务的 <code>server/data/feedback.jsonl</code>，不会发往第三方。
      </p>
      <form class="feedback" @submit.prevent="submitFeedback">
        <textarea
          v-model="message"
          rows="4"
          maxlength="1000"
          placeholder="例如：羟苯丙酯的限量写错了 / 这个成分的别名没有收录 / 报告里的这句话看不懂…"
        ></textarea>
        <div class="feedback-row">
          <input v-model="contact" maxlength="80" placeholder="联系方式（可选，方便回复你）" />
          <button type="submit" class="btn primary" :disabled="busy">
            <i v-if="busy" class="spinner"></i>
            {{ busy ? '提交中…' : '提交反馈' }}
          </button>
        </div>
        <p v-if="error" class="notice error" role="alert">{{ error }}</p>
        <p v-if="sent" class="notice ok" role="status">已收到，谢谢。我们会按问题和数据两类分别处理。</p>
      </form>

      <div class="next">
        <a class="btn" href="#/method">看解读方法</a>
        <a class="btn" href="#/ingredients">看成分库</a>
        <a class="btn primary" href="#/">去解读一瓶</a>
      </div>
    </section>
  </article>
</template>

<style scoped>
.page-view { max-width: 860px; margin: 0 auto; }

.page-head { margin-bottom: 20px; }

.page-title { margin: 8px 0 10px; font-size: 34px; font-weight: 600; }

.lead { max-width: 680px; font-size: 14px; line-height: 1.95; color: var(--ink2); }

.head-meta { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 14px; font-size: 11.5px; color: var(--ink3); }

.head-meta span { padding: 2px 8px; border-radius: 5px; background: #F1EDE4; }

.prose { padding: 26px 28px; }

.prose .h2 { margin: 26px 0 10px; font-size: 19px; }

.prose .h2:first-child { margin-top: 0; }

.prose p, .prose li { font-size: 13.5px; line-height: 1.95; color: var(--ink2); }

.prose p { margin-bottom: 10px; }

.prose b { color: var(--ink); font-weight: 600; }

.two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 22px; }

.two-col h3 { margin-bottom: 6px; font-size: 13px; color: var(--ink); }

.two-col ul { padding-left: 17px; }

.two-col li { margin-bottom: 4px; }

.plain-list { padding-left: 17px; margin: 6px 0 10px; }

.plain-list li { margin-bottom: 6px; }

.table-wrap { margin: 12px 0 16px; overflow-x: auto; border: 1px solid var(--line); border-radius: 10px; }

table { width: 100%; border-collapse: collapse; font-size: 12.5px; }

th { padding: 9px 12px; background: #F7F4ED; color: var(--ink3); font-weight: 600; text-align: left; white-space: nowrap; }

td { padding: 9px 12px; border-top: 1px solid #F2EEE4; color: var(--ink2); line-height: 1.7; }

.disclaimer {
  padding: 12px 14px;
  border: 1px solid var(--warn-line);
  border-radius: 10px;
  background: var(--warn-soft);
  color: #6F4B12 !important;
}

.changelog { list-style: none; margin: 6px 0 0; }

.changelog li { display: flex; gap: 12px; padding: 7px 0; border-top: 1px dashed var(--line); }

.changelog li:first-child { border-top: none; }

.changelog b { flex-shrink: 0; width: 64px; color: var(--accent); }

.feedback { margin-top: 12px; padding: 14px; border: 1px solid var(--line); border-radius: 12px; background: #FBF9F4; }

.feedback textarea {
  width: 100%;
  padding: 11px 12px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: #fff;
  color: var(--ink);
  font-size: 13px;
  line-height: 1.8;
  resize: vertical;
  outline: none;
}

.feedback textarea:focus { border-color: var(--accent); box-shadow: var(--focus); }

.feedback-row { display: flex; gap: 8px; align-items: center; margin-top: 8px; flex-wrap: wrap; }

.feedback-row input {
  flex: 1;
  min-width: 200px;
  padding: 9px 11px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
  font-size: 12.5px;
  outline: none;
}

.feedback-row input:focus { border-color: var(--accent); }

.notice { margin-top: 8px; padding: 8px 11px; border-radius: 8px; font-size: 12.5px; }

.notice.error { background: var(--danger-soft); border: 1px solid var(--danger-line); color: #7C3A2E; }

.notice.ok { background: var(--good-soft); border: 1px solid var(--good-line); color: var(--good); }

.next { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 22px; padding-top: 16px; border-top: 1px dashed var(--line); }

.next .btn { text-decoration: none; }

@media (max-width: 640px) {
  .page-title { font-size: 27px; }
  .prose { padding: 20px 16px; }
  .two-col { grid-template-columns: 1fr; gap: 14px; }
  .prose .h2 { font-size: 17px; }
}
</style>
