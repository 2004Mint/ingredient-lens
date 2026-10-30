/**
 * 成分表文本处理：与后端 AnalyzeService 的切分规则保持一致。
 * 前端先用同一套规则算出「会被切成几个成分」，用户粘贴完立刻能看到反馈，
 * 不用等一次请求才知道自己粘错了格式。
 */

const PROTECT = '\u0001'
const LABEL_PREFIX = /^\s*(?:全成分|主要成分|成分表|其他微量成分)\s*[：:]?\s*/

export const SKIN_NAMES = { dry: '干性', oily: '油性', combo: '混合', normal: '中性' }

/** 先保护「1,2-戊二醇」这类数字间的逗号，再按顿号/逗号/换行切分 */
export function splitIngredients(raw) {
  if (!raw) return []
  const text = String(raw)
    .replace(LABEL_PREFIX, '')
    .replace(/成分表/g, '')
    .replace(/成分：/g, '')
    .replace(/成分:/g, '')
    .replace(/(\d)[,，](\d)/g, `$1${PROTECT}$2`)

  return text
    .split(/[、，,；;｜|\n\r]+/)
    .map((part) => part.split(PROTECT).join(',').trim())
    .filter(Boolean)
}

/** 去掉行首序号、「其他微量成分：」与残留的标点 */
export function cleanToken(token) {
  return String(token || '')
    .trim()
    .replace(/^[\s：:·•・\-—*[\]【】]+/, '')
    .replace(/^[0-9０-９]{1,3}[.．、)）\s]+/, '')
    .replace(/^其他微量成分[:：]?/, '')
    .trim()
}

export function ingredientTokens(raw) {
  return splitIngredients(raw).map(cleanToken).filter(Boolean)
}

/**
 * 输入体检：只返回当前最值得说的一条，避免把用户淹没在提示里。
 * action='space' 时前端会给出「按空格重新切分」按钮（OCR 结果常见空格分隔）。
 */
export function inspectInput(raw, tokens) {
  const text = String(raw || '')
  const trimmed = text.trim()
  if (!trimmed) return null

  const hasDelimiter = /[、，,；;｜|\n\r]/.test(text)
  const hasSpace = /\s/.test(trimmed)
  const hasCjk = /[\u4e00-\u9fa5]/.test(trimmed)

  if (!hasDelimiter && tokens.length <= 1 && trimmed.length >= 10) {
    if (hasSpace && hasCjk) {
      return {
        level: 'warn',
        text: '看起来成分是用空格隔开的，会被当成一整个成分。',
        action: 'space',
        actionLabel: '按空格切分'
      }
    }
    return {
      level: 'warn',
      text: '没有找到顿号或换行，整段只会被当成一个成分。',
      action: null
    }
  }

  if (tokens.length > 0 && tokens.length < 3) {
    return {
      level: 'info',
      text: `只切出 ${tokens.length} 个成分。成分表一般不止这些，确认没有漏粘。`,
      action: null
    }
  }

  if (tokens.length > 80) {
    return {
      level: 'warn',
      text: `切出 ${tokens.length} 个成分，可能混进了宣传语。建议只保留成分表那一段。`,
      action: null
    }
  }

  if (trimmed.length > 3000) {
    return {
      level: 'info',
      text: '文本较长，单次分析最多处理前 150 个成分。',
      action: null
    }
  }

  return null
}

/** 把连续空白换成顿号：只用于用户主动点击修复的场景，不自动执行 */
export function splitByWhitespace(raw) {
  return String(raw || '')
    .replace(LABEL_PREFIX, '')
    .replace(/\s+/g, '、')
    .replace(/、{2,}/g, '、')
    .replace(/^、|、$/g, '')
}

export function profileLabel(profile) {
  const p = profile || {}
  const parts = [SKIN_NAMES[p.skin] || '未填肤质']
  if (p.sensitive) parts.push('敏感肌')
  if (p.pregnant) parts.push('孕期/备孕')
  return parts.join(' · ')
}

export const VERDICT_LABEL = {
  avoid: '建议避开',
  caution: '需要留意',
  good: '可以放心'
}
