import { VERDICT_LABEL, profileLabel } from './text.js'
import { formatDateTime } from './format.js'

const LEVEL_CLASS = {
  avoid: 'lv-avoid',
  caution: 'lv-caution',
  good: 'lv-good'
}

/** 把「风险提示」摊平成可用于比较的集合：同一成分只算一次 */
export function riskFacts(report) {
  const flags = (report?.flags || []).filter((f) => f.level !== 'info')
  const names = new Set()
  for (const f of flags) {
    for (const n of f.ingredients || []) names.add(n)
  }
  return { count: names.size, names, flags }
}

/**
 * 展示用的结论视图。
 *
 * 后端只按规则给 avoid/caution/good，它不知道「认出来的成分够不够」。
 * 一个成分都没认出来时如果照搬 good，页面会变成绿色的「可以放心」——
 * 这是最危险的一种好看。所以覆盖率不足时，这里改成「未能解读 / 信息不足」。
 */
export function reportView(report) {
  const verdict = report?.verdict || {}
  const total = Number(report?.total || 0)
  const matched = Number(report?.matched || 0)

  if (matched === 0) {
    return {
      level: 'unknown',
      label: '未能解读',
      cls: 'lv-unknown',
      headline: '这份成分表没能识别出来',
      summary: '可能是外文标签、写法不同，或这些成分还不在知识库里。检查一下分隔符，或换一份中文成分表再试。',
      unrecognized: true,
      lowCoverage: false,
      total,
      matched
    }
  }

  const lowCoverage = total >= 2 && matched / total < 0.5

  if (lowCoverage && verdict.level === 'good') {
    return {
      level: 'insufficient',
      label: '信息不足',
      cls: 'lv-insufficient',
      headline: '认出的成分太少，还不能下结论',
      summary: `共 ${total} 个成分，只认出 ${matched} 个。把没认出的成分名补全，或换一张更清楚的标签照片，结论才可靠。`,
      unrecognized: false,
      lowCoverage: true,
      total,
      matched
    }
  }

  return {
    level: verdict.level || 'good',
    label: VERDICT_LABEL[verdict.level] || '',
    cls: LEVEL_CLASS[verdict.level] || 'lv-good',
    headline: verdict.headline || '',
    summary: verdict.summary || '',
    unrecognized: false,
    lowCoverage,
    total,
    matched
  }
}

function reasonKeys(report) {
  const keys = new Set()
  for (const f of (report?.flags || []).filter((f) => f.level !== 'info')) {
    for (const n of f.ingredients || []) keys.add(`${f.level}|${f.type}|${n}`)
  }
  return keys
}

function describeReasons(keys) {
  const byLabel = new Map()
  for (const key of keys) {
    const [level, type, name] = key.split('|')
    const label = `${type} · ${level === 'high' ? '避开' : '留意'}`
    if (!byLabel.has(label)) byLabel.set(label, [])
    byLabel.get(label).push(name)
  }
  return [...byLabel.entries()]
    .slice(0, 2)
    .map(([label, names]) => `「${label}」${names.slice(0, 3).join('、')}${names.length > 3 ? ' 等' : ''}`)
    .join('；')
}

/**
 * 换档案后重算的变化说明。
 * 这是「个体化」最直接的证据：不是让用户自己对比报告，而是直接告诉他差别在哪。
 */
export function buildDelta(prev, next) {
  if (!prev || !next) return null
  const before = riskFacts(prev)
  const after = riskFacts(next)
  const added = [...after.names].filter((n) => !before.names.has(n))
  const removed = [...before.names].filter((n) => !after.names.has(n))
  const levelChanged = prev.verdict?.level !== next.verdict?.level

  // 同一个成分可能换了一种理由被提示（比如孕期新增了视黄醇禁忌），
  // 这时成分名集合没变，但用户看到的风险确实变了，也要说出来。
  const beforeReasons = reasonKeys(prev)
  const afterReasons = reasonKeys(next)
  const addedReasons = [...afterReasons].filter((k) => !beforeReasons.has(k))
  const removedReasons = [...beforeReasons].filter((k) => !afterReasons.has(k))

  if (
    !added.length &&
    !removed.length &&
    !levelChanged &&
    before.count === after.count &&
    !addedReasons.length &&
    !removedReasons.length
  ) {
    return null
  }

  const bits = []
  bits.push(
    before.count === after.count
      ? `风险成分仍是 ${after.count} 个`
      : `风险成分 ${before.count} → ${after.count} 个`
  )

  const RANK = { avoid: 2, caution: 1, good: 0 }
  const severity = (RANK[next.verdict?.level] || 0) - (RANK[prev.verdict?.level] || 0)
  if (severity > 0) bits.push('风险等级上升')
  else if (severity < 0) bits.push('风险等级下降')

  if (added.length) {
    bits.push(`新增 ${added.slice(0, 3).join('、')}${added.length > 3 ? ' 等' : ''}`)
  } else if (addedReasons.length) {
    bits.push(`新增${describeReasons(addedReasons)}`)
  }

  if (removed.length) {
    bits.push(`解除 ${removed.slice(0, 3).join('、')}${removed.length > 3 ? ' 等' : ''}`)
  } else if (removedReasons.length) {
    bits.push(`解除${describeReasons(removedReasons)}`)
  }

  return {
    tone: after.count > before.count ? 'up' : after.count < before.count ? 'down' : 'same',
    title: levelChanged
      ? `换成「${profileLabel(next.profile)}」后，结论变为「${VERDICT_LABEL[next.verdict?.level] || ''}」`
      : `按「${profileLabel(next.profile)}」重算`,
    detail: bits.join('；')
  }
}

/** 生成可粘贴到聊天/文档里的纯文本结论 */
export function buildReportText(report, meta = {}) {
  if (!report) return ''
  const view = reportView(report)
  const lines = []

  lines.push(meta.productName ? meta.productName : '成分镜报告')
  const headBits = []
  if (meta.id) headBits.push(`分析编号 ${meta.id}`)
  if (meta.createdAt) headBits.push(formatDateTime(meta.createdAt))
  if (meta.ruleVersion) headBits.push(`规则库 ${meta.ruleVersion}`)
  if (headBits.length) lines.push(headBits.join(' · '))
  lines.push('')

  lines.push(`成分镜｜${view.label}`)
  lines.push(view.headline)
  lines.push(view.summary)

  lines.push('')
  lines.push(`档案：${profileLabel(report.profile)}`)
  lines.push(`成分：共 ${view.total} 个，识别 ${view.matched} 个`)

  if (view.unrecognized) {
    lines.push('')
    lines.push('有成分待补全；补全后会按新数据重算结论。')
    lines.push('')
    lines.push('—— 成分镜 · 只读标签信息，不评价商品优劣，不构成医疗或用药建议。')
    return lines.join('\n')
  }

  if (view.lowCoverage) {
    lines.push(`注意：只识别出 ${view.matched}/${view.total} 个成分，结论可能不完整。`)
  }

  if ((report.researched || []).length) {
    lines.push(`AI 已补全 ${report.researched.length} 个成分并加入待研究库，报告已按新数据重算。`)
  }
  const queuedResearch = Number(report.researchQueued || 0) + Number(report.researchRunning || 0)
  if (queuedResearch > 0) {
    lines.push(`另有 ${queuedResearch} 个成分进入 AI 研究队列，完成后会按新数据更新报告。`)
  }
  if (Number(report.researchFailed || 0) > 0) {
    lines.push(`${report.researchFailed} 个成分研究失败，已保留在管理控制台的研究任务中，可重试。`)
  }

  const risks = (report.flags || []).filter((f) => f.level !== 'info')
  lines.push('')
  if (risks.length) {
    lines.push('风险提示：')
    for (const f of risks) {
      lines.push(`· ${f.message}`)
      if (f.basis) lines.push(`  ${f.basis}`)
    }
  } else {
    lines.push('风险提示：没有需要回避或留意的成分。')
  }

  const actives = report.actives || []
  if (actives.length) {
    lines.push('')
    lines.push('功效成分位置：')
    for (const a of actives) {
      lines.push(`· ${a.name}：第 ${a.index + 1} 位，${a.comment}`)
    }
  }

  const regs = (report.flags || []).filter((f) => f.level === 'info')
  if (regs.length) {
    lines.push('')
    lines.push('法规提示：')
    for (const f of regs) lines.push(`· ${f.message}（${f.basis}）`)
  }

  const pendingResearch = report.unknownCount ?? (report.unknown || []).length
  if (pendingResearch) {
    lines.push('')
    lines.push(`有 ${pendingResearch} 个成分待补全，补全后会更新结论。`)
  }

  lines.push('')
  lines.push('—— 成分镜 · 只读标签信息，不评价商品优劣，不构成医疗或用药建议。')
  return lines.join('\n')
}
