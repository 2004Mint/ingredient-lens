<script setup>
import { computed } from 'vue'
import LadderChart from './LadderChart.vue'

const props = defineProps({ data: { type: Object, required: true } })
defineEmits(['reset'])

const LEVEL = {
  avoid: { label: '建议避开', cls: 'lv-avoid' },
  caution: { label: '需要留意', cls: 'lv-caution' },
  good: { label: '可以放心', cls: 'lv-good' }
}
const lv = computed(() => LEVEL[props.data.verdict?.level] || LEVEL.good)

const highFlags = computed(() => props.data.flags.filter(f => f.level === 'high'))
const medFlags = computed(() => props.data.flags.filter(f => f.level === 'medium'))
const infoFlags = computed(() => props.data.flags.filter(f => f.level === 'info'))

const conceptCount = computed(() =>
  (props.data.actives || []).filter(a => a.tier === 'tail').length
)

const SKIN_NAME = { dry: '干性', oily: '油性', combo: '混合', normal: '中性' }
const profileText = computed(() => {
  const p = props.data.profile || {}
  const parts = [SKIN_NAME[p.skin] || '未填肤质']
  if (p.sensitive) parts.push('敏感肌')
  if (p.pregnant) parts.push('孕期/备孕')
  return parts.join(' · ')
})
</script>

<template>
  <div class="report">
    <!-- 结论 -->
    <section class="verdict" :class="lv.cls">
      <div class="vtag">{{ lv.label }}</div>
      <h1 class="vhead serif">{{ data.verdict.headline }}</h1>
      <p class="vsum">{{ data.verdict.summary }}</p>
      <div class="vmeta">
        <span>{{ profileText }}</span>
        <span>共 {{ data.matched }} 个成分</span>
      </div>
    </section>

    <!-- 数据条 -->
    <section class="stats">
      <div class="stat">
        <div class="num">{{ data.matched }}</div>
        <div class="slabel">识别出的成分</div>
      </div>
      <div class="stat">
        <div class="num">{{ (data.actives || []).length }}</div>
        <div class="slabel">功效成分</div>
      </div>
      <div class="stat" :class="{ hot: conceptCount > 0 }">
        <div class="num">{{ conceptCount }}</div>
        <div class="slabel">疑似概念添加</div>
      </div>
      <div class="stat" :class="{ danger: highFlags.length > 0 }">
        <div class="num">{{ highFlags.length }}</div>
        <div class="slabel">建议避开</div>
      </div>
    </section>

    <!-- 配方梯队 -->
    <section class="card">
      <h2 class="h2 serif">配方梯队</h2>
      <p class="hint">
        法规要求成分按含量从高到低排列，所以「排在第几位」本身就是信息。
      </p>
      <LadderChart :ladder="data.ladder" />
    </section>

    <!-- 风险 -->
    <section class="card" v-if="highFlags.length || medFlags.length">
      <h2 class="h2 serif">需要你注意的</h2>

      <div v-if="highFlags.length" class="fgroup">
        <div class="gtitle danger">建议避开</div>
        <div v-for="(f, i) in highFlags" :key="'h' + i" class="flag danger">
          <div class="ftop">
            <span class="fmsg">{{ f.message }}</span>
            <span class="ftag">{{ f.type }}</span>
          </div>
          <div v-if="f.ingredients.length > 1" class="names">
            <span v-for="n in f.ingredients" :key="n" class="nm">{{ n }}</span>
          </div>
          <div class="fbasis">{{ f.basis }}</div>
          <ul v-if="f.details && f.details.length" class="notes">
            <li v-for="d in f.details" :key="d.name"><b>{{ d.name }}</b>：{{ d.note }}</li>
          </ul>
        </div>
      </div>

      <div v-if="medFlags.length" class="fgroup">
        <div class="gtitle warn">需要留意</div>
        <div v-for="(f, i) in medFlags" :key="'m' + i" class="flag warn">
          <div class="ftop">
            <span class="fmsg">{{ f.message }}</span>
            <span class="ftag">{{ f.type }}</span>
          </div>
          <div v-if="f.ingredients.length > 1" class="names">
            <span v-for="n in f.ingredients" :key="n" class="nm">{{ n }}</span>
          </div>
          <div class="fbasis">{{ f.basis }}</div>
          <ul v-if="f.details && f.details.length" class="notes">
            <li v-for="d in f.details" :key="d.name"><b>{{ d.name }}</b>：{{ d.note }}</li>
          </ul>
        </div>
      </div>
    </section>

    <!-- 功效成分 -->
    <section class="card" v-if="(data.actives || []).length">
      <h2 class="h2 serif">功效成分的位置</h2>
      <div v-for="(a, i) in data.actives" :key="'a' + i" class="active" :class="{ concept: a.tier === 'tail' }">
        <div class="atop">
          <span class="aname">{{ a.name }}</span>
          <span class="acat">{{ a.cat }}</span>
          <span class="apos">第 {{ a.index + 1 }} 位</span>
        </div>
        <div class="acomment">{{ a.comment }}</div>
      </div>
    </section>

    <!-- 发现 -->
    <section class="card" v-if="(data.insights || []).length">
      <h2 class="h2 serif">几个发现</h2>
      <ul class="insights">
        <li v-for="(t, i) in data.insights" :key="'i' + i">{{ t }}</li>
      </ul>
    </section>

    <!-- 法规细节 -->
    <section class="card" v-if="infoFlags.length">
      <h2 class="h2 serif">法规提示</h2>
      <div v-for="(f, i) in infoFlags" :key="'f' + i" class="flag info">
        <div class="ftop">
          <span class="fmsg">{{ f.message }}</span>
          <span class="ftag">法规</span>
        </div>
        <div class="fbasis">{{ f.basis }}</div>
      </div>
    </section>

    <section class="card" v-if="(data.unknown || []).length">
      <h2 class="h2 serif">未收录的成分</h2>
      <p class="hint">以下成分暂不在知识库中，不影响其余结论。</p>
      <div class="unknowns">
        <span v-for="u in data.unknown" :key="u" class="unk">{{ u }}</span>
      </div>
    </section>

    <button class="back" @click="$emit('reset')">← 换一瓶</button>
  </div>
</template>

<style scoped>
.report { display: flex; flex-direction: column; gap: 14px; }

/* 结论卡 */
.verdict {
  border-radius: 16px; padding: 26px 24px;
  border: 1px solid var(--line);
}
.lv-avoid { background: linear-gradient(160deg, #FDF1EE, #FBF7F4); border-color: #F0D8D2; }
.lv-caution { background: linear-gradient(160deg, #FDF7EA, #FCFAF5); border-color: #EEE1C4; }
.lv-good { background: linear-gradient(160deg, #EDF6F1, #F8FBF9); border-color: #D5E8DE; }

.vtag {
  display: inline-block; font-size: 11.5px; font-weight: 700;
  letter-spacing: .08em; padding: 3px 10px; border-radius: 5px;
  margin-bottom: 12px; background: rgba(255,255,255,.75);
}
.lv-avoid .vtag { color: var(--danger); }
.lv-caution .vtag { color: var(--warn); }
.lv-good .vtag { color: var(--good); }

.vhead { font-size: 25px; line-height: 1.4; font-weight: 600; margin-bottom: 10px; }
.vsum { font-size: 14px; color: var(--ink2); line-height: 1.9; }
.vmeta {
  display: flex; gap: 16px; margin-top: 16px; padding-top: 14px;
  border-top: 1px solid rgba(0,0,0,.06);
  font-size: 12px; color: var(--ink3);
}

/* 数据条 */
.stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.stat {
  background: var(--card); border: 1px solid var(--line);
  border-radius: 12px; padding: 16px 14px; text-align: center;
}
.stat.hot { border-color: #EEE1C4; background: #FDFAF2; }
.stat.danger { border-color: #F0D8D2; background: #FDF6F4; }
.num { font-size: 26px; font-weight: 700; line-height: 1.2; font-variant-numeric: tabular-nums; }
.stat.hot .num { color: var(--warn); }
.stat.danger .num { color: var(--danger); }
.slabel { font-size: 11.5px; color: var(--ink3); margin-top: 4px; }

.card { background: var(--card); border: 1px solid var(--line); border-radius: 14px; padding: 22px; }
.h2 { font-size: 16.5px; font-weight: 600; margin-bottom: 10px; }
.hint { font-size: 12.5px; color: var(--ink3); margin: -4px 0 16px; line-height: 1.8; }

/* 风险项 */
.fgroup + .fgroup { margin-top: 20px; }
.gtitle { font-size: 12px; font-weight: 700; letter-spacing: .06em; margin-bottom: 10px; }
.gtitle.danger { color: var(--danger); }
.gtitle.warn { color: var(--warn); }

.flag { padding: 13px 15px; border-radius: 10px; border: 1px solid; margin-bottom: 8px; }
.flag.danger { background: var(--danger-soft); border-color: #F0D2CC; }
.flag.warn { background: var(--warn-soft); border-color: #EFE2C2; }
.flag.info { background: #F8F7F4; border-color: var(--line); margin-bottom: 8px; }

.ftop { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; flex-wrap: wrap; }
.fname { font-size: 13.5px; font-weight: 600; }
.ftag { font-size: 10.5px; padding: 1px 7px; border-radius: 4px; background: rgba(255,255,255,.8); color: var(--ink2); }
.fmsg { font-size: 13.5px; font-weight: 600; color: var(--ink); }
.fbasis { font-size: 12.5px; color: var(--ink3); margin-top: 6px; line-height: 1.8; }

.names { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.nm {
  font-size: 12px; padding: 2px 9px; border-radius: 5px;
  background: rgba(255,255,255,.85); color: var(--ink2);
}

.notes { list-style: none; margin-top: 8px; padding-top: 8px; border-top: 1px dashed rgba(0,0,0,.07); }
.notes li { font-size: 12px; color: var(--ink2); line-height: 1.85; padding-left: 10px; position: relative; }
.notes li::before { content: '·'; position: absolute; left: 0; color: var(--ink3); }
.notes b { font-weight: 600; }

/* 功效成分 */
.active { padding: 12px 0; border-bottom: 1px solid #F2EFE9; }
.active:last-child { border-bottom: none; }
.atop { display: flex; align-items: baseline; gap: 9px; }
.aname { font-size: 13.5px; font-weight: 600; }
.acat { font-size: 11px; color: var(--accent); background: var(--accent-soft); padding: 1px 7px; border-radius: 4px; }
.apos { margin-left: auto; font-size: 11.5px; color: var(--ink3); }
.acomment { font-size: 12.5px; color: var(--ink2); margin-top: 3px; }
.active.concept .apos { color: var(--warn); font-weight: 600; }
.active.concept .acomment { color: var(--warn); }

/* 洞察 */
.insights { list-style: none; }
.insights li {
  font-size: 13px; color: var(--ink2); line-height: 1.95;
  padding-left: 16px; position: relative; margin-bottom: 8px;
}
.insights li::before {
  content: ''; position: absolute; left: 0; top: 10px;
  width: 5px; height: 5px; border-radius: 50%; background: var(--accent);
}

.unknowns { display: flex; flex-wrap: wrap; gap: 7px; }
.unk {
  font-size: 12px; color: var(--ink3);
  background: #F4F1EB; padding: 3px 10px; border-radius: 6px;
}

.back {
  align-self: flex-start; margin-top: 6px;
  font-size: 13px; color: var(--ink2); padding: 9px 18px;
  border: 1px solid var(--line); border-radius: 9px; background: var(--card);
  transition: border-color .16s;
}
.back:hover { border-color: var(--accent); color: var(--accent); }

@media (max-width: 640px) {
  .stats { grid-template-columns: repeat(2, 1fr); }
  .vhead { font-size: 21px; }
}
</style>
