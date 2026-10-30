const HISTORY_KEY = 'cosme:history:v1'
const MAX_ITEMS = 20

function persist(list) {
  const trimmed = list.slice(0, MAX_ITEMS)
  try {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(trimmed))
    return trimmed
  } catch {
    // 报告 JSON 较大，配额不够时先保留最近 8 条
    try {
      const fallback = trimmed.slice(0, 8)
      localStorage.setItem(HISTORY_KEY, JSON.stringify(fallback))
      return fallback
    } catch {
      return trimmed
    }
  }
}

export function loadHistory() {
  try {
    const raw = localStorage.getItem(HISTORY_KEY)
    if (!raw) return []
    const list = JSON.parse(raw)
    if (!Array.isArray(list)) return []
    return list
      .filter((item) => item && typeof item.id === 'string' && item.report && typeof item.report === 'object')
      .slice(0, MAX_ITEMS)
  } catch {
    return []
  }
}

/** 新增或更新一条历史：同一个分析编号只保留最新版本（档案重算后覆盖） */
export function upsertHistory(entry) {
  if (!entry || !entry.id) return loadHistory()
  const list = loadHistory()
  const index = list.findIndex((item) => item.id === entry.id)
  if (index >= 0) list.splice(index, 1)
  list.unshift(entry)
  return persist(list)
}

export function removeHistory(id) {
  const list = loadHistory().filter((item) => item.id !== id)
  return persist(list)
}

export function clearHistory() {
  try {
    localStorage.removeItem(HISTORY_KEY)
  } catch {
    /* 忽略 */
  }
  return []
}
