<script setup>
defineProps({ version: { type: Object, default: () => ({}) } })
</script>

<template>
  <article class="page-view">
    <header class="page-head">
      <p class="eyebrow">方法论</p>
      <h1 class="page-title serif">我们怎么读一份成分表</h1>
      <p class="lead">
        成分镜不评价商品好坏，只做一件事：利用法规强制的排序规则，把「排在第几位」翻译成与你有关的结论。
        这一页把判断依据、边界和数据版本全部摊开，方便你核对。
      </p>
      <div class="head-meta">
        <span>规则库 {{ version.rulesVersion || '2026.10' }}</span>
        <span>成分库 {{ version.librarySize || 60 }} 条</span>
        <span>更新于 {{ version.libraryVersion || '2026.10' }}</span>
      </div>
    </header>

    <section class="card prose">
      <h2 class="h2 serif">1. 一个前提：成分表是按含量降序排列的</h2>
      <p>
        《化妆品标签管理办法》要求，化妆品成分表按成分含量从高到低排列；含量不超过 0.1% 的成分，
        可以列入「其他微量成分」，不参与降序排列。也就是说，<b>位置本身就是信息</b>。
      </p>
      <p>
        一瓶主打「烟酰胺美白」的精华，如果烟酰胺排在第 12 位，它前面已经有 11 个成分，含量都不低于它。
        位次推不出具体浓度，但足以说明它<b>不是这瓶的主要功效成分</b>——「主打」两个字需要打折。
        我们把这个位次判断叫 <b>概念添加</b>。
      </p>

      <h2 class="h2 serif">2. 三档梯队</h2>
      <div class="table-wrap">
        <table>
          <thead>
            <tr><th>位置</th><th>含义</th><th>我们会怎么说</th></tr>
          </thead>
          <tbody>
            <tr><td>第 1–5 位</td><td>含量较高，通常是水、保湿剂、乳化体系</td><td>配方主体</td></tr>
            <tr><td>第 6–10 位</td><td>含量中等，常见功效成分与防腐体系</td><td>含量中等</td></tr>
            <tr><td>第 11 位之后</td><td>属配方后段</td><td>功效成分排在这里，通常不是主要功效来源</td></tr>
          </tbody>
        </table>
      </div>

      <h2 class="h2 serif">3. 个体化：同一瓶，不同的人结论不同</h2>
      <p>规则引擎会按你填的三组条件重新排序风险，而不是给一个人均分数：</p>
      <div class="table-wrap">
        <table>
          <thead>
            <tr><th>条件</th><th>参与判断的规则</th></tr>
          </thead>
          <tbody>
            <tr><td>肤质</td><td>油性 / 混合性皮肤叠加致痘性 ≥3 的成分提示</td></tr>
            <tr><td>敏感肌</td><td>致敏、强刺激成分升级为「建议避开」；其余需留意项合并</td></tr>
            <tr><td>孕期 / 备孕</td><td>维 A 类等禁忌成分升级为「建议避开」；慎用项提示咨询医师</td></tr>
            <tr><td>法规限量</td><td>《化妆品安全技术规范》限用组分单独列出，不与个体化风险混在一起</td></tr>
          </tbody>
        </table>
      </div>
      <p>
        同类风险会合并展示。五个「对敏感肌可能引起不适」并列出现，本质上是在制造它本该消除的焦虑；
        我们按类型合并成一条，再列出具体成分。
      </p>

      <h2 class="h2 serif">4. 成分匹配：宁可先待补全，也不张冠李戴</h2>
      <p>
        成分匹配<b>不使用向量检索</b>。「羟苯甲酯」与「羟苯丙酯」、「1,2-丙二醇」与「1,2-戊二醇」
        在向量空间里非常接近，但一个是常规防腐剂、一个孕期需谨慎，混淆就是安全事故。
      </p>
      <p>
        匹配顺序是：标准名精确匹配 → 别名表 → 包含匹配。编辑距离只对纯拉丁名称启用，
        用来容忍 OCR 对英文的错字；中文名一律不做模糊匹配。匹配不上先标为「待补全」，
        不猜一个近似名，也不在报告里列错名；它的真实排位仍然保留。
      </p>

      <h2 class="h2 serif">5. AI 研究：模型只生成候选，不直接下结论</h2>
      <p>
        扫描到待补全成分时，如果配置了研究 API，会自动把成分名提交给模型，要求返回结构化 JSON
        （角色、分类、致痘性、孕期/敏感肌提示、限量等）。结果先进入「待研究」库，参与匹配并在报告注明 AI 补全；
        由人在管理控制台确认后转为「已确认」，拒绝则不再参与匹配。
      </p>
      <p>
        API 配置有一个「总 API」：视觉 / 研究留空的字段会逐项回退到总 API。
        研究只发送成分名与所在成分表片段，不发送肤质、孕期或分享报告内容；研究结果会带置信度与依据，方便人工复核。
      </p>

      <h2 class="h2 serif">6. 不确定性：认不出来，不等于安全</h2>
      <p>
        如果整份成分表都没能匹配上，页面会显示「未能解读」，而不是绿色的「可以放心」；
        识别覆盖率不足一半时，会降级为「信息不足」并提示结论可能不完整。复制到聊天里的文本也使用同一口径。
      </p>

      <h2 class="h2 serif">7. 数据与版本</h2>
      <div class="table-wrap">
        <table>
          <thead>
            <tr><th>数据</th><th>当前版本</th><th>说明</th></tr>
          </thead>
          <tbody>
            <tr><td>规则库</td><td>{{ version.rulesVersion || '2026.10' }}</td><td>梯队阈值、风险分级、合并逻辑</td></tr>
            <tr><td>成分库</td><td>{{ version.libraryVersion || '2026.10' }}</td><td>{{ version.librarySize || 60 }} 条高频成分；不是完整法规库</td></tr>
            <tr><td>限量依据</td><td>《化妆品安全技术规范》</td><td>限用组分、防腐剂与防晒剂限量</td></tr>
            <tr><td>名称依据</td><td>常见 INCI 名称与别名整理</td><td>同名异写、英文别名会逐步补充</td></tr>
          </tbody>
        </table>
      </div>
      <p class="small">
        成分库只覆盖高频成分，待补全不代表没有风险；限量值与标签实际标注不一致时，以法规原文与产品标签为准。
      </p>

      <h2 class="h2 serif">8. 我们不做什么</h2>
      <ul class="plain-list">
        <li>不评价商品好坏，不做「成分评分」。</li>
        <li>不推荐购买哪一瓶，也不做替代品推荐。</li>
        <li>不构成医疗、用药或皮肤科诊断建议；孕期与皮肤疾病请咨询医师。</li>
        <li>不把模型输出当作事实：规则判定不交给模型；AI 研究结果只作为「待研究」候选，人工确认后才进入正式成分库。</li>
      </ul>

      <div class="next">
        <a class="btn" href="#/ingredients">查看成分库</a>
        <a class="btn primary" href="#/">去解读一瓶</a>
      </div>
    </section>
  </article>
</template>

<style scoped>
.page-view { max-width: 860px; margin: 0 auto; }

.page-head { margin-bottom: 20px; }

.page-title { margin: 8px 0 10px; font-size: 34px; font-weight: 600; line-height: 1.35; }

.lead { max-width: 700px; font-size: 14px; line-height: 1.95; color: var(--ink2); }

.head-meta { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 14px; font-size: 11.5px; color: var(--ink3); }

.head-meta span { padding: 2px 8px; border-radius: 5px; background: #F1EDE4; }

.prose { padding: 26px 28px; }

.prose .h2 { margin: 24px 0 10px; font-size: 19px; }

.prose .h2:first-child { margin-top: 0; }

.prose p { font-size: 13.5px; line-height: 1.95; color: var(--ink2); margin-bottom: 10px; }

.prose p b { color: var(--ink); font-weight: 600; }

.table-wrap { margin: 12px 0 16px; overflow-x: auto; border: 1px solid var(--line); border-radius: 10px; }

table { width: 100%; border-collapse: collapse; font-size: 12.5px; }

th {
  padding: 9px 12px;
  background: #F7F4ED;
  color: var(--ink3);
  font-weight: 600;
  text-align: left;
  white-space: nowrap;
}

td { padding: 9px 12px; border-top: 1px solid #F2EEE4; color: var(--ink2); line-height: 1.7; }

.plain-list { margin: 8px 0 0; padding-left: 18px; }

.plain-list li { font-size: 13px; line-height: 1.9; color: var(--ink2); margin-bottom: 4px; }

.small { font-size: 11.5px !important; color: var(--ink3) !important; }

.next { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 22px; padding-top: 16px; border-top: 1px dashed var(--line); }

.next .btn { text-decoration: none; }

@media (max-width: 640px) {
  .page-title { font-size: 27px; }
  .prose { padding: 20px 16px; }
  .prose .h2 { font-size: 17px; }
}
</style>
