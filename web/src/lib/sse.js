/**
 * 以 POST 方式读取 SSE 流。
 *
 * 为什么不用 EventSource：它只支持 GET，而报告结构较大、塞不进 query。
 * 这里用 fetch + ReadableStream 自己切 `\n\n` 帧，并用 AbortController 支持中途取消。
 */
export async function streamJSON(url, body, { signal, onEvent } = {}) {
  let res
  try {
    res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify(body),
      signal
    })
  } catch (err) {
    if (err.name === 'AbortError') return
    throw new Error('连不上本机后端（8082），请先启动 start-server.bat')
  }

  if (!res.ok) throw new Error(`解释服务返回 ${res.status}`)
  if (!res.body) throw new Error('当前浏览器不支持流式读取')

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      let index
      while ((index = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, index)
        buffer = buffer.slice(index + 2)
        const payload = parseFrame(frame)
        if (payload && onEvent) onEvent(payload)
      }
    }
  } catch (err) {
    if (err.name !== 'AbortError') throw err
  } finally {
    try {
      reader.releaseLock()
    } catch {
      /* 已经释放 */
    }
  }
}

function parseFrame(frame) {
  const data = frame
    .split('\n')
    .filter((line) => line.startsWith('data:'))
    .map((line) => line.slice(5))
    .join('')
    .trim()
  if (!data) return null
  try {
    return JSON.parse(data)
  } catch {
    return null
  }
}
