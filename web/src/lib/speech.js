/**
 * 浏览器语音能力：语音输入（Web Speech API）与朗读（SpeechSynthesis）。
 *
 * 为什么放在前端：
 * - 不增加任何服务端成本，也不把用户的声音上传到我们的后端；
 * - 浏览器原生支持中文识别与中文朗读，失败时只是少一个入口，不影响主流程。
 *
 * 兼容性：Chrome / Edge 支持 SpeechRecognition；Safari 需 webkit 前缀且行为不同；
 * Firefox 目前不支持。所有调用方都必须先判断 supported()，不支持就隐藏入口。
 */

export function recognitionSupported() {
  if (typeof window === 'undefined') return false
  return !!(window.SpeechRecognition || window.webkitSpeechRecognition)
}

export function synthesisSupported() {
  if (typeof window === 'undefined') return false
  return typeof window.speechSynthesis !== 'undefined' && typeof window.SpeechSynthesisUtterance !== 'undefined'
}

/**
 * 创建一次语音识别。
 *
 * 只取最终结果（interimResults=false）：成分名不需要边说边改，
 * 中间结果反复跳动反而会让人以为识别错了。
 */
export function createRecognition({ lang = 'zh-CN', onResult, onError, onEnd } = {}) {
  if (!recognitionSupported()) return null
  const Ctor = window.SpeechRecognition || window.webkitSpeechRecognition
  const recognition = new Ctor()
  recognition.lang = lang
  recognition.continuous = false
  recognition.interimResults = false
  recognition.maxAlternatives = 1
  let handled = false

  recognition.onresult = (event) => {
    // 有的浏览器在 continuous=false 时也会多次派发 onresult，
    // 每次都把全文当新内容追加就会重复，这里只认第一次最终结果。
    if (handled) return
    const results = event.results || []
    const result = results[results.length - 1] || results[0]
    const transcript = result && result[0] ? String(result[0].transcript || '').trim() : ''
    if (transcript) {
      handled = true
      if (onResult) onResult(transcript)
    }
  }
  recognition.onerror = (event) => {
    if (onError) onError(event && event.error ? event.error : 'unknown')
  }
  recognition.onend = () => {
    if (onEnd) onEnd()
  }
  return recognition
}

/** 识别错误码 → 给用户看的一句话（不出现内部术语） */
export function recognitionErrorText(code) {
  switch (code) {
    case 'not-allowed':
    case 'service-not-allowed':
      return '浏览器没有麦克风权限；局域网 http 地址可能不允许语音，改用粘贴即可'
    case 'no-speech':
      return '没有听到声音，再说一次或直接粘贴'
    case 'audio-capture':
      return '没有找到麦克风，请检查设备后重试'
    case 'network':
      return '语音识别服务连不上，改用粘贴即可'
    case 'aborted':
      return ''
    default:
      return '语音识别没成功，改用粘贴即可'
  }
}

/** 朗读一段文字；返回 stop 函数。不支持时返回 null。 */
export function speak(text, { lang = 'zh-CN', rate = 1, onEnd } = {}) {
  if (!synthesisSupported()) return null
  const content = String(text || '').trim()
  if (!content) return null

  window.speechSynthesis.cancel()
  let utterance
  try {
    utterance = new SpeechSynthesisUtterance(content)
  } catch {
    return null
  }
  utterance.lang = lang
  utterance.rate = rate
  if (onEnd) {
    utterance.onend = () => onEnd()
    utterance.onerror = () => onEnd()
  }
  try {
    window.speechSynthesis.speak(utterance)
  } catch {
    // 某些环境（无音频设备、被策略拦截）会在 speak 时抛错，退化成「没有朗读」
    return null
  }

  return () => {
    try {
      window.speechSynthesis.cancel()
    } catch {
      /* 忽略 */
    }
  }
}

export function stopSpeaking() {
  if (!synthesisSupported()) return
  try {
    window.speechSynthesis.cancel()
  } catch {
    /* 忽略 */
  }
}
