function networkMessage() {
  return '连不上本机后端（8082），请先启动 start-server.bat，再点重试'
}

export async function requestJSON(url, { method = 'GET', body, timeout = 15000 } = {}) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeout)

  try {
    const res = await fetch(url, {
      method,
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      body: body ? JSON.stringify(body) : undefined,
      signal: controller.signal
    })

    const raw = await res.text()
    let data = null
    if (raw) {
      try {
        data = JSON.parse(raw)
      } catch {
        data = null
      }
    }

    if (!res.ok) {
      throw new Error((data && data.error) || `服务返回 ${res.status}，请稍后重试`)
    }
    if (data === null) {
      throw new Error('服务返回的内容无法解析')
    }
    return data
  } catch (err) {
    if (err.name === 'AbortError') {
      throw new Error('请求超时了，请确认后端仍在运行')
    }
    if (err instanceof TypeError) {
      throw new Error(networkMessage())
    }
    throw err
  } finally {
    clearTimeout(timer)
  }
}

export function getJSON(url, timeout = 8000) {
  return requestJSON(url, { timeout })
}

export function postJSON(url, body, timeout = 20000) {
  return requestJSON(url, { method: 'POST', body, timeout })
}
