// 从 durations.json 生成：shots.yaml / timeline.yaml / 字幕文本 / drawtext 滤镜 / 音频计划
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'E:/AIWEB/video';
const GAP = 0.30;   // 句间停顿
const TAIL = 0.40;  // 每段尾部留白
const CARD_END = 2.4;

const durs = JSON.parse(fs.readFileSync(path.join(ROOT, 'durations.json'), 'utf8').replace(/^\uFEFF/, ''));
const byId = Object.fromEntries(durs.map((s) => [s.id, s]));

const round30 = (t) => Math.round(t * 30) / 30;

// 每段：音频总长 A、目标镜头长 T = A + TAIL（帧对齐）
const segs = durs.map((s) => {
  const A = s.lines.reduce((a, l) => a + l.dur, 0) + GAP * Math.max(0, s.lines.length - 1);
  return { ...s, A, T: round30(A + TAIL) };
});
const segById = Object.fromEntries(segs.map((s) => [s.id, s]));

// ---------- 动作表（时间以镜头内秒计） ----------
const ACTIONS = {
  s1: [
    { wait: 700 },
    { scroll: { by: 300, duration: 1600 } },
    { wait: 1000 },
    { scroll: { by: -260, duration: 1400 } },
  ],
  s2: [
    { wait: 600 },
    { camera: '.editor', zoom: 1.1, duration: 1200, hold: 1500 },
    { move: '.count', duration: 700 },
    { wait: 900 },
    { click: '.preset:has-text("孕期")', zoom: 1.18, hold: 1500 },
  ],
  s3: [
    { wait: 600 },
    { scroll: { to: '#ladder', block: 'start', duration: 1400 } },
    { wait: 800 },
    { camera: '.pill:has-text("烟酰胺")', zoom: 1.25, duration: 1200, hold: 2600 },
    { wait: 15000 },
    { click: '.pill:has-text("烟酰胺")', zoom: 1.28, hold: 900 },
  ],
  s4: [
    { wait: 600 },
    { click: '.bar-btn:has-text("调整档案")', zoom: 1.15, hold: 600 },
    { wait: 700 },
    { click: '.switch-row:has-text("敏感肌")', zoom: 1.2, hold: 2400 },
    { click: '.switch-row:has-text("敏感肌")', zoom: 1.2, hold: 1200 },
  ],
  s5: [
    { wait: 500 },
    { scroll: { to: '#report-top', block: 'start', duration: 1200 } },
    { wait: 700 },
    { click: '.bar-btn:has-text("分享")', zoom: 1.15, hold: 2200 },
  ],
  s6: [
    { wait: 600 },
    { type: { target: '.ta', text: '水 甘油 丙二醇 丁二醇 戊二醇 透明质酸钠 泛醇 苯氧乙醇 羟苯甲酯 烟酰胺', delay: 35 } },
    { wait: 900 },
    { click: 'text=按空格切分', zoom: 1.18, hold: 2600 },
  ],
  s7: [
    { wait: 600 },
    { type: { target: '.ta', text: '甲乙丙、丁戊己、庚辛壬' } },
    { wait: 500 },
    { click: '.cta', zoom: 1.12, hold: 1200 },
    { wait: 3500 },
  ],
  s8: [
    { wait: 900 },
    { click: 'text=研究任务', zoom: 1.12, hold: 2600 },
    { wait: 800 },
    { click: 'text=成分数据', zoom: 1.12, hold: 2600 },
  ],
  s9: [
    { wait: 900 },
    { wait: 5200 },
    { wait: 1600 },
  ],
  s10: [
    { wait: 900 },
    { wait: 6000 },
    { wait: 1600 },
  ],
  s11: [
    { wait: 800 },
    { scroll: { to: '#report-top', block: 'start', duration: 1200 } },
    { wait: 5200 },
  ],
};

const URLS = {
  s1: 'http://127.0.0.1:5173/',
  s2: 'http://127.0.0.1:5173/',
  s3: 'http://127.0.0.1:5173/?demo=1',
  s4: 'http://127.0.0.1:5173/?demo=1',
  s5: 'http://127.0.0.1:5173/?demo=1',
  s6: 'http://127.0.0.1:5173/',
  s7: 'http://127.0.0.1:5173/',
  s8: 'http://127.0.0.1:5173/#/admin',
  s9: 'http://127.0.0.1:8098/vec.html',
  s10: 'http://127.0.0.1:8098/code.html',
  s11: 'http://127.0.0.1:5173/?demo=1',
};

const SETUP = {
  s2: [{ click: ".sample:has-text('美白精华液')" }, { wait: 400 }],
  s3: [{ waitFor: '#ladder' }, { wait: 700 }],
  s4: [{ waitFor: '#report-top' }, { wait: 700 }],
  s5: [{ waitFor: '#report-top' }, { wait: 700 }],
  s8: [
    { eval: `sessionStorage.setItem('cosme:admin-token','${process.env.ADMIN_TOKEN || ''}');location.reload()` },
    { wait: 1200 },
    { waitFor: 'text=研究任务' },
  ],
  s11: [{ waitFor: '#report-top' }, { wait: 800 }],
};

// ---------- shots.yaml ----------
const yaml = [];
yaml.push('out: out', '', 'defaults:');
yaml.push('  viewport: { width: 1440, height: 810 }');
yaml.push('  scale: 1.3333333');
yaml.push('  output: 1920x1080');
yaml.push('  fps: 30');
yaml.push('  crf: 18');
yaml.push('  tail: 200');
yaml.push('  zoom: { level: 1.18, max: 1.5, ramp: 0.65, rampOut: 0.75, hold: 0.9, padding: 0.16 }');
yaml.push('', 'shots:');

for (const s of segs) {
  if (s.kind !== 'shot') continue;
  yaml.push(`  - id: ${s.id}`);
  yaml.push(`    url: ${URLS[s.id]}`);
  if (s.id === 's9' || s.id === 's10') yaml.push('    cursor: false');
  if (SETUP[s.id]) {
    yaml.push('    setup:');
    for (const st of SETUP[s.id]) yaml.push('      - ' + inline(st));
  }
  yaml.push('    actions:');
  for (const a of ACTIONS[s.id] || []) {
    const entries = Object.entries(a);
    if (entries.length === 1) {
      yaml.push('      - ' + entries[0][0] + ': ' + fmt(entries[0][1]));
    } else {
      yaml.push('      - ' + entries[0][0] + ': ' + fmt(entries[0][1]));
      for (const [k, v] of entries.slice(1)) yaml.push(`        ${k}: ${fmt(v)}`);
    }
  }
  // 收尾等待：确保镜头长于目标，交给 timeline 裁切
  yaml.push(`      - wait: ${Math.round((s.T + 2.0) * 1000)}`);
}
fs.writeFileSync(path.join(ROOT, 'shots', 'full.yaml'), yaml.join('\n') + '\n');

function q(s) {
  return "'" + String(s).replace(/'/g, "''") + "'";
}

function fmt(v) {
  if (typeof v === 'string') return q(v);
  if (typeof v === 'number') return String(v);
  if (v && typeof v === 'object') {
    return '{ ' + Object.entries(v).map(([k, vv]) => `${k}: ${fmt(vv)}`).join(', ') + ' }';
  }
  return JSON.stringify(v);
}

function inline(obj) {
  const entries = Object.entries(obj);
  if (entries.length === 1) {
    const [k, v] = entries[0];
    return k === 'wait' ? `wait: ${v}` : `${k}: ${fmt(v)}`;
  }
  return entries.map(([k, v]) => `${k}: ${fmt(v)}`).join(', ');
}

// ---------- timeline.yaml ----------
const tl = [];
tl.push('output: out/full-silent.mp4');
tl.push('size: 1920x1080', 'fps: 30', 'crf: 18', 'transition: false');
tl.push('fade: { in: 0.5, out: 0.7 }');
tl.push('card:', '  background: "#151513"', '  color: "#f2efe9"', '  weight: 500', '  fontFamily: "Microsoft YaHei"');
tl.push('', 'items:');
tl.push('  - card:');
tl.push('      kicker: "全国高校 AI Web 开发挑战赛 · 本科 B 组"');
tl.push('      title: "成分镜"');
tl.push('      subtitle: "拍一下，看懂这瓶适不适合你"');
tl.push(`      duration: ${segById.s0.T}`);
for (const s of segs) {
  if (s.kind !== 'shot') continue;
  tl.push(`  - clip: out/${s.id}/${s.id}.mp4`);
  tl.push(`    trim: [0, ${s.T}]`);
}
tl.push('  - end:');
tl.push('      title: "成分镜"');
tl.push('      subtitle: "只读标签信息，不构成医疗建议"');
tl.push(`      duration: ${CARD_END}`);
fs.writeFileSync(path.join(ROOT, 'shots', 'full-timeline.yaml'), tl.join('\n') + '\n');

// ---------- 字幕 + 音频计划 ----------
const subs = [];   // {start,end,text,file}
let cursor = segById.s0.T;
const audioPlan = [];  // {id, lines:[files], gap, silence}

// 片头卡字幕（S0 的句子落在卡上）
{
  let t = 0;
  segById.s0.lines.forEach((l, i) => {
    subs.push({ start: t, end: t + l.dur, text: l.text, file: `s0-${i + 1}` });
    t += l.dur + GAP;
  });
  audioPlan.push({ id: 's0', clip: segById.s0.T, lines: segById.s0.lines.map((l, i) => `s0-${i + 1}.wav`) });
}

for (const s of segs) {
  if (s.kind !== 'shot') continue;
  let t = cursor;
  const files = [];
  s.lines.forEach((l, i) => {
    subs.push({ start: t, end: t + l.dur, text: l.text, file: `${s.id}-${i + 1}` });
    files.push(`${s.id}-${i + 1}.wav`);
    t += l.dur + GAP;
  });
  audioPlan.push({ id: s.id, clip: s.T, lines: files });
  cursor += s.T;
}
audioPlan.push({ id: 'end', clip: CARD_END, lines: [] });

// 字幕文本文件（过长拆两行）
fs.mkdirSync(path.join(ROOT, 'sub'), { recursive: true });
const draw = [];
for (const s of subs) {
  const file = `sub/${s.file}.txt`;
  fs.writeFileSync(path.join(ROOT, file), wrap(s.text), 'utf8');
  draw.push(
    `drawtext=fontfile=msyh.ttc:textfile=${file}:fontsize=40:fontcolor=white:borderw=1.5:` +
    `bordercolor=black@0.8:box=1:boxcolor=black@0.38:boxborderw=18:line_spacing=14:` +
    `x=(w-text_w)/2:y=h-200:enable=between(t\\,${s.start.toFixed(2)}\\,${s.end.toFixed(2)})`
  );
}
const filter = '[0:v]' + draw.join(',') + '[v]';
fs.writeFileSync(path.join(ROOT, 'sub', 'filter.txt'), filter, 'utf8');

function wrap(text) {
  if (text.length <= 22) return text;
  const marks = ['——', '。', '；', '，', '、'];
  let best = -1;
  const mid = text.length / 2;
  for (const m of marks) {
    let idx = text.lastIndexOf(m, Math.floor(mid + 8));
    if (idx > 6 && (best < 0 || Math.abs(idx - mid) < Math.abs(best - mid))) best = idx + m.length;
  }
  if (best < 0) best = Math.floor(mid);
  return text.slice(0, best).trim() + '\n' + text.slice(best).trim();
}

// 音频计划（每段需要补的静音长度）
const plan = audioPlan.map((p) => {
  const spoken = (byId[p.id]?.lines || []).reduce((a, l) => a + l.dur, 0) + GAP * Math.max(0, (byId[p.id]?.lines.length || 1) - 1);
  const pad = Math.max(0, p.clip - spoken);
  return { ...p, pad: Number(pad.toFixed(3)) };
});
fs.writeFileSync(path.join(ROOT, 'audio', 'plan.json'), JSON.stringify({ gap: GAP, plan }, null, 2), 'utf8');

const total = segById.s0.T + segs.filter((s) => s.kind === 'shot').reduce((a, s) => a + s.T, 0) + CARD_END;
console.log('segments', segs.length, 'clips', segs.filter((s) => s.kind === 'shot').length);
console.log('video total', total.toFixed(2), 's');
console.log('subs', subs.length);
