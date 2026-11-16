#!/usr/bin/env node
/**
 * 成分镜 · 产品级评测
 *
 * 评的是「产品行为」而不是某个函数：给一段真实成分表，看它识别得对不对、
 * 排位有没有漂、判定有没有按档案变化、模型输出有没有越过护栏、同一输入两次结果是否一致。
 *
 * 结论用的是前端同一份 reportView()：低覆盖率会把「good」降级成「信息不足」，
 * 所以这里测的是用户真正看到的结论，不是接口里的原始字段。
 *
 * 用法：
 *   node docs/eval/run-eval.mjs                       # 只打印结果
 *   node docs/eval/run-eval.mjs --write               # 同时写 评测记录-v1.md
 *   node docs/eval/run-eval.mjs --base http://127.0.0.1:8083 --no-guard
 *
 * 说明：不统计 token 与费用——后端接口没有回传 usage，估出来的数字不可核对，
 * 所以这里只记录耗时。要算钱请看研究任务管理台里的调用记录。
 */
import { readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { reportView } from '../../web/src/lib/report.js'

const here = dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2)
const argOf = (name, fallback) => {
  const i = args.indexOf(name)
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback
}
const BASE = argOf('--base', 'http://127.0.0.1:8083').replace(/\/+$/, '')
const WRITE = args.includes('--write')
const WITH_GUARD = !args.includes('--no-guard')

const BANNED = ['治疗', '治愈', '诊断', '处方', '根治', '疗效', '药效', '药用', '医美']
const DIGITS = /[0-9０-９]|百分之|[一二三四五六七八九十]+%/

const spec = JSON.parse(await readFile(join(here, 'cases.json'), 'utf8'))

function expand(c) {
  if (!c.generate) return c.text
  if (c.generate.kind === 'repeat') {
    const unit = c.generate.unit.split('、').filter(Boolean)
    const out = []
    for (let i = 0; i < c.generate.times; i++) out.push(...unit)
    return out.join('、')
  }
  throw new Error('未知的 generate.kind: ' + c.generate.kind)
}

async function post(path, body, timeoutMs = 30000) {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(timeoutMs)
  })
  const text = await res.text()
  if (!res.ok) throw new Error(`${path} HTTP ${res.status}: ${text.slice(0, 200)}`)
  return JSON.parse(text)
}

/** 读 SSE，返回最终展示文本与来源（llm / template） */
async function stream(path, body, timeoutMs = 90000) {
  const started = Date.now()
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(timeoutMs)
  })
  if (!res.ok || !res.body) throw new Error(`${path} HTTP ${res.status}`)

  const decoder = new TextDecoder()
  let buf = ''
  let delta = ''
  let final = null
  let source = 'llm'
  let reason = ''

  for await (const chunk of res.body) {
    buf += decoder.decode(chunk, { stream: true })
    let cut
    while ((cut = buf.indexOf('\n\n')) >= 0) {
      const block = buf.slice(0, cut)
      buf = buf.slice(cut + 2)
      for (const line of block.split('\n')) {
        if (!line.startsWith('data:')) continue
        const raw = line.slice(5).trim()
        if (!raw) continue
        let ev
        try {
          ev = JSON.parse(raw)
        } catch {
          continue
        }
        if (ev.type === 'delta') delta += ev.text || ''
        else if (ev.type === 'replace') {
          final = ev.text || ''
          source = ev.source || source
          reason = ev.reason || ''
        } else if (ev.type === 'done') {
          if (ev.source) source = ev.source
          if (ev.reason) reason = ev.reason
        }
      }
    }
  }
  return { text: final != null ? final : delta, source, reason, ms: Date.now() - started }
}

function violations(text) {
  const out = []
  const m = String(text || '').match(DIGITS)
  if (m) out.push('数字/百分比：' + m[0])
  for (const w of BANNED) if (String(text || '').includes(w)) out.push('医疗用语：' + w)
  return out
}

function flagOf(report, name) {
  for (const f of report.flags || []) {
    if ((f.ingredients || []).includes(name)) return f
  }
  return null
}

const rows = []
const knownRows = []
let passed = 0
let knownPassed = 0

const health = await (await fetch(BASE + '/api/health')).json()

for (const c of spec.cases) {
  const text = expand(c)
  const profile = c.profile || { skin: 'normal', sensitive: false, pregnant: false }
  const t0 = Date.now()
  let report

  try {
    report = await post('/api/analyze', { text, profile, research: false })
  } catch (err) {
    const status = String(err.message).match(/HTTP (\d+)/)?.[1]
    const expected = c.expect && c.expect.httpError
    const ok = expected && String(expected) === status
    rows.push({
      id: c.id,
      title: c.title,
      total: '—',
      matched: '—',
      unknown: '—',
      level: '—',
      raw: '—',
      ms: Date.now() - t0,
      ok,
      note: ok ? `按预期拒绝（HTTP ${status}）` : String(err.message).slice(0, 90)
    })
    if (ok) passed += 1
    continue
  }

  const ms = Date.now() - t0
  const e = c.expect || {}
  const view = reportView(report)
  const ladderNames = (report.ladder || []).map((r) => r.name)
  const unknown = report.unknown || []

  const fails = []
  if (e.total != null && report.total !== e.total) fails.push(`成分数 ${report.total}≠${e.total}`)
  if (e.truncated != null && !!report.truncated !== e.truncated) fails.push(`截断 ${report.truncated}≠${e.truncated}`)
  if (e.level != null && view.level !== e.level) fails.push(`结论 ${view.level}≠${e.level}`)
  for (const name of e.mustMatch || []) {
    if (!ladderNames.includes(name)) fails.push(`未命中「${name}」`)
  }
  for (const name of e.mustUnknown || []) {
    if (!unknown.includes(name)) fails.push(`未标待补全「${name}」`)
  }
  for (const f of e.mustFlag || []) {
    const hit = flagOf(report, f.ingredient)
    if (!hit) fails.push(`未标记「${f.ingredient}」`)
    else if (f.level && hit.level !== f.level) fails.push(`「${f.ingredient}」级别 ${hit.level}≠${f.level}`)
  }

  // 可复现：同一输入再跑一次，关键字段必须一致
  let stable = true
  try {
    const again = await post('/api/analyze', { text, profile, research: false })
    const sig = (r) => JSON.stringify({
      total: r.total,
      matched: r.matched,
      unknownCount: r.unknownCount,
      level: r.verdict?.level,
      seq: (r.sequence || []).map((s) => s.name)
    })
    stable = sig(report) === sig(again)
  } catch {
    stable = false
  }
  if (!stable) fails.push('两次结果不一致')

  const row = {
    id: c.id,
    title: c.title,
    total: report.total,
    matched: report.matched,
    unknown: report.unknownCount,
    level: view.level,
    raw: report.verdict?.level || '',
    ms,
    ok: fails.length === 0,
    note: fails.join('；')
  }
  if (c.knownIssue) {
    knownRows.push({ ...row, issue: c.knownIssue })
    if (row.ok) knownPassed += 1
  } else {
    rows.push(row)
    if (row.ok) passed += 1
  }
}

/* ------------------------------ 护栏 ------------------------------ */
const guardRows = []
if (WITH_GUARD && spec.guard?.length) {
  const byCase = new Map()
  for (const c of spec.cases) byCase.set(c.id, c)
  for (const g of spec.guard) {
    let payload
    let path
    if (g.kind === 'report') {
      const c = byCase.get(g.fromCase)
      if (!c) continue
      const report = await post('/api/analyze', {
        text: expand(c),
        profile: c.profile || { skin: 'normal', sensitive: false, pregnant: false },
        research: false
      })
      payload = { report, profile: c.profile || { skin: 'normal', sensitive: false, pregnant: false } }
      path = '/api/explain/stream'
    } else {
      payload = g.payload
      path = '/api/explain/ingredient/stream'
    }
    let result
    try {
      result = await stream(path, payload)
    } catch (err) {
      guardRows.push({ id: g.id, title: g.title, source: '请求失败', blocked: '—', bad: String(err.message).slice(0, 60), ms: 0, ok: false, text: '' })
      continue
    }
    const bad = violations(result.text)
    guardRows.push({
      id: g.id,
      title: g.title,
      source: result.source,
      blocked: result.source === 'template' ? result.reason || '回退模板' : '未触发',
      bad: bad.length ? bad.join('，') : '无',
      ms: result.ms,
      ok: bad.length === 0 && String(result.text).trim().length > 0,
      text: result.text
    })
  }
}

/* ------------------------------ 输出 ------------------------------ */
const guardPassed = guardRows.filter((r) => r.ok).length
const guardBlocked = guardRows.filter((r) => r.source === 'template').length
const avg = (list) => (list.length ? Math.round(list.reduce((s, x) => s + x, 0) / list.length) : 0)
const analyzeMs = avg(rows.filter((r) => typeof r.ms === 'number').map((r) => r.ms))
const guardMs = avg(guardRows.filter((r) => r.ms).map((r) => r.ms))
const totalChecks = rows.length + knownRows.length + guardRows.length
const totalPassed = passed + knownPassed + guardPassed

console.log(`base=${BASE}  library=${health.librarySize}  vision=${health.visionConfigured}`)
console.log('')
console.log('用例  场景                                   成分  命中  待补全  页面结论      接口原始  耗时   结果')
for (const r of [...rows, ...knownRows]) {
  console.log(
    [
      r.id.padEnd(4),
      String(r.title).padEnd(36).slice(0, 36),
      String(r.total).padStart(4),
      String(r.matched).padStart(4),
      String(r.unknown).padStart(5),
      String(r.level).padEnd(12),
      String(r.raw).padEnd(8),
      (r.ms + 'ms').padStart(7),
      r.ok ? 'PASS' : 'FAIL ' + r.note
    ].join(' ')
  )
}
if (knownRows.length) {
  console.log('')
  for (const k of knownRows) console.log(`已知问题 ${k.id}：${k.issue}`)
}
if (guardRows.length) {
  console.log('')
  console.log('护栏  场景                            来源      闸门            违规  耗时')
  for (const g of guardRows) {
    console.log([g.id.padEnd(4), String(g.title).padEnd(30).slice(0, 30), g.source.padEnd(8), String(g.blocked).padEnd(14), g.bad.padEnd(6), g.ms + 'ms'].join(' '))
  }
}
console.log('')
console.log(`场景通过 ${passed}/${rows.length}；已知问题 ${knownPassed}/${knownRows.length}；护栏通过 ${guardPassed}/${guardRows.length}（被闸门拦回模板 ${guardBlocked} 次）`)
console.log(`平均耗时：分析 ${analyzeMs}ms，模型解释 ${guardMs}ms`)

if (WRITE) {
  const now = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  const stamp = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}`
  const md = []
  md.push('# 成分镜 · 产品级评测记录 v1')
  md.push('')
  md.push(`> 生成时间：${stamp}　运行方式：\`node docs/eval/run-eval.mjs --write\``)
  md.push(`> 被测实例：\`${BASE}\`　成分库 ${health.librarySize} 条　视觉模型 ${health.visionConfigured ? '已配置' : '未配置'}`)
  md.push('')
  md.push('## 一、这份评测在测什么')
  md.push('')
  md.push('测的是产品行为，不是某个函数：给一段真实成分表，看识别、排位、判定、护栏与可复现性是否符合预期。')
  md.push('结论取自前端同一份 `reportView()`——低覆盖率会把「good」降级成「信息不足」，所以测到的是用户真正看到的结论，而不是接口里的原始字段。')
  md.push('期望值由人工核对真实输出后冻结；改了规则或成分库就要重跑，不能拿旧结果交差。')
  md.push('')
  md.push('## 二、结果')
  md.push('')
  md.push('| 维度 | 通过 | 总数 | 说明 |')
  md.push('|---|---|---|---|')
  md.push(`| 场景用例 | ${passed} | ${rows.length} | 识别、排位、档案联动、截断、边界输入 |`)
  md.push(`| 输出护栏 | ${guardPassed} | ${guardRows.length} | 最终文本不得出现数字 / 百分比 / 医疗用语 |`)
  md.push(`| 已知问题 | ${knownPassed} | ${knownRows.length} | 明知会错的地方，单独记，不混进通过率 |`)
  md.push('')
  md.push(`逐项检查合计 **${totalPassed}/${totalChecks}**。`)
  md.push('')
  md.push('## 三、场景明细')
  md.push('')
  md.push('| 编号 | 场景 | 成分数 | 命中 | 待补全 | 页面结论 | 接口原始 | 耗时 | 结果 |')
  md.push('|---|---|---|---|---|---|---|---|---|')
  for (const r of rows) {
    md.push(`| ${r.id} | ${r.title} | ${r.total} | ${r.matched} | ${r.unknown} | ${r.level} | ${r.raw} | ${r.ms}ms | ${r.ok ? '通过' : '未通过：' + r.note} |`)
  }
  md.push('')
  if (knownRows.length) {
    md.push('## 四、已知问题（不计入通过率）')
    md.push('')
    for (const k of knownRows) {
      md.push(`- **${k.id} · ${k.title}**：${k.issue}（本次实测：成分 ${k.total} 个，命中 ${k.matched} 个，页面结论「${k.level}」）`)
    }
    md.push('')
  }
  if (guardRows.length) {
    md.push('## 五、输出护栏（真实模型调用）')
    md.push('')
    md.push('| 编号 | 场景 | 文本来源 | 闸门 | 违规词 | 耗时 | 结果 |')
    md.push('|---|---|---|---|---|---|---|')
    for (const g of guardRows) {
      md.push(`| ${g.id} | ${g.title} | ${g.source === 'template' ? '模板兜底' : '模型'} | ${g.blocked} | ${g.bad} | ${g.ms}ms | ${g.ok ? '通过' : '未通过'} |`)
    }
    md.push('')
    md.push(`模型解释平均耗时 ${guardMs}ms；本次 ${guardBlocked} 次因越界被闸门拦回模板。`)
    md.push('论文里不会告诉你的事：护栏只有在真实调用里被触发过，才算真的有用；单元测试通过不代表模型不会试图写数字。')
    md.push('')
  }
  md.push('## 六、没统计的东西')
  md.push('')
  md.push('- **token 与费用**：解释接口不回传 usage，估出来的数字不可核对，因此不写；调用记录见管理控制台的研究任务。')
  md.push('- **功效与效果**：本产品不做功效预测，也不声称能验证效果，这项不测。')
  md.push('- **成分库覆盖率**：这里只测「已收录的会不会判错」，不测「未收录的有多少」，后者随研究任务增长，见成分库页面。')
  md.push('')
  md.push('## 七、复现')
  md.push('')
  md.push('```powershell')
  md.push('cd E:\\AIWEB\\server')
  md.push('java -jar target/cosme-backend-1.0.0.jar --server.port=8083')
  md.push('cd E:\\AIWEB')
  md.push('node docs/eval/run-eval.mjs --base http://127.0.0.1:8083 --write')
  md.push('```')
  md.push('')
  await writeFile(join(here, '评测记录-v1.md'), md.join('\n'), 'utf8')
  console.log('written: docs/eval/评测记录-v1.md')
}

process.exitCode = passed === rows.length && guardPassed === guardRows.length ? 0 : 1
