export const LEVEL_META = {
  avoid: { label: '建议避开', cls: 'danger' },
  caution: { label: '需要留意', cls: 'warn' },
  good: { label: '可以放心', cls: 'good' },
  insufficient: { label: '信息不足', cls: 'neutral' },
  unknown: { label: '未能解读', cls: 'neutral' }
}

export function levelMeta(level) {
  return LEVEL_META[level] || LEVEL_META.unknown
}

export const ROLE_OPTIONS = ['基质', '功效', '防腐', '香精', '防晒', '表活']

export const STATUS_META = {
  pending: { label: '待研究', cls: 'warn' },
  confirmed: { label: '已确认', cls: 'good' },
  rejected: { label: '已拒绝', cls: 'neutral' }
}

export const STATUS_OPTIONS = [
  { value: 'pending', label: '待研究' },
  { value: 'confirmed', label: '已确认' },
  { value: 'rejected', label: '已拒绝' }
]

export const SOURCE_LABEL = {
  builtin: '内置',
  ai: 'AI 研究',
  manual: '手动'
}

export function statusMeta(status) {
  return STATUS_META[status] || STATUS_META.pending
}

export const SAFETY_OPTIONS = [
  { value: 'low', label: '低关注' },
  { value: 'medium', label: '需关注' },
  { value: 'high', label: '高关注' }
]

export const RISK_OPTIONS = [
  { value: 'low', label: '低' },
  { value: 'medium', label: '中' },
  { value: 'high', label: '高' }
]

export const PROFILE_OPTIONS = [
  { value: 'safe', label: '无特别提示' },
  { value: 'caution', label: '慎用' },
  { value: 'avoid', label: '避免' }
]

export function riskTags(item) {
  if (!item) return []
  const tags = []
  if (item.comedo >= 3) tags.push({ text: `致痘 ${item.comedo}/5`, cls: item.comedo >= 4 ? 'danger' : 'warn' })
  if (item.irrit === 'high') tags.push({ text: '强刺激', cls: 'danger' })
  else if (item.irrit === 'medium') tags.push({ text: '刺激', cls: 'warn' })
  if (item.preg === 'avoid') tags.push({ text: '孕期避免', cls: 'danger' })
  else if (item.preg === 'caution') tags.push({ text: '孕期慎用', cls: 'warn' })
  if (item.sensitive === 'avoid') tags.push({ text: '敏感避免', cls: 'danger' })
  else if (item.sensitive === 'caution') tags.push({ text: '敏感慎用', cls: 'warn' })
  if (item.safety === 'high') tags.push({ text: '高关注', cls: 'danger' })
  else if (item.safety === 'medium') tags.push({ text: '关注', cls: 'warn' })
  return tags
}
