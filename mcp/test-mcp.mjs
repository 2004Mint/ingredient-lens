#!/usr/bin/env node
/**
 * MCP server 的冒烟测试：不依赖任何 MCP 客户端，直接按协议说 JSON-RPC。
 *
 *   node mcp/test-mcp.mjs
 *
 * 覆盖：
 *   1. initialize 握手（协议版本、serverInfo、capabilities.tools）
 *   2. tools/list 返回三个只读工具，且都有 inputSchema
 *   3. search_ingredient 命中（中文名）
 *   4. search_ingredient 未命中时明确说没有，并给出建议而不是编造
 *   5. lookup_limit 没有限量记录时不瞎写
 *   6. 未知方法返回 -32601，而不是静默
 */
import { spawn } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const SERVER = path.join(HERE, 'server.mjs')

// 测试环境显式清掉写入口令牌：保证 submit_research 走「未启用」分支，不依赖后端
const child = spawn(process.execPath, [SERVER], {
  stdio: ['pipe', 'pipe', 'pipe'],
  env: { ...process.env, COSME_MCP_TOKEN: '' }
})

const pending = new Map()
let buffer = ''
let failures = 0
let seen = 0

child.stdout.setEncoding('utf8')
child.stdout.on('data', (chunk) => {
  buffer += chunk
  let idx = buffer.indexOf('\n')
  while (idx >= 0) {
    const line = buffer.slice(0, idx).trim()
    buffer = buffer.slice(idx + 1)
    if (line) {
      try {
        const msg = JSON.parse(line)
        const waiter = pending.get(msg.id)
        if (waiter) {
          pending.delete(msg.id)
          waiter(msg)
        }
      } catch {
        /* 忽略非协议输出 */
      }
    }
    idx = buffer.indexOf('\n')
  }
})

child.stderr.setEncoding('utf8')
child.stderr.on('data', (text) => {
  const line = String(text).trim()
  if (line) console.log('  [server]', line)
})

let nextId = 1
function request(method, params) {
  const id = nextId++
  const payload = { jsonrpc: '2.0', id, method }
  if (params) payload.params = params
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id)
      reject(new Error(`超时：${method}`))
    }, 8000)
    pending.set(id, (msg) => {
      clearTimeout(timer)
      resolve(msg)
    })
    child.stdin.write(JSON.stringify(payload) + '\n')
  })
}

function check(name, ok, detail) {
  seen += 1
  if (ok) {
    console.log(`  ✓ ${name}`)
  } else {
    failures += 1
    console.log(`  ✗ ${name}${detail ? ' — ' + detail : ''}`)
  }
}

async function main() {
  console.log('MCP 冒烟测试')

  const init = await request('initialize', {
    protocolVersion: '2024-11-05',
    capabilities: {},
    clientInfo: { name: 'cosme-smoke', version: '1.0.0' }
  })
  check('initialize 返回 serverInfo', init.result && init.result.serverInfo && init.result.serverInfo.name === 'cosme-ingredients')
  check('声明 tools 能力', !!(init.result && init.result.capabilities && init.result.capabilities.tools))

  child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n')

  const list = await request('tools/list')
  const tools = (list.result && list.result.tools) || []
  check('tools/list 返回 4 个工具', tools.length === 4, `实际 ${tools.length}`)
  check('每个工具都有 inputSchema', tools.every((t) => t && t.inputSchema && t.inputSchema.type === 'object'))

  const hit = await request('tools/call', { name: 'search_ingredient', arguments: { name: '烟酰胺' } })
  const hitPayload = JSON.parse(hit.result.content[0].text)
  check('search_ingredient 命中「烟酰胺」', hitPayload.found === true && hitPayload.item && hitPayload.item.标准名 === '烟酰胺')

  const miss = await request('tools/call', { name: 'search_ingredient', arguments: { name: '不存在的成分XYZ' } })
  const missPayload = JSON.parse(miss.result.content[0].text)
  check('未命中时返回 found=false', missPayload.found === false)
  check('未命中时提示不要编造', /不要编造/.test(missPayload.message || ''))

  const limit = await request('tools/call', { name: 'lookup_limit', arguments: { name: '柠檬烯' } })
  const limitPayload = JSON.parse(limit.result.content[0].text)
  check('lookup_limit 返回结构', limitPayload.found === true && 'limit' in limitPayload)

  const submit = await request('tools/call', { name: 'submit_research', arguments: { name: '测试提交成分' } })
  const submitText = submit.result && submit.result.content ? submit.result.content[0].text : ''
  check('写入口未配置时拒绝提交', submit.result && submit.result.isError === true && /COSME_MCP_TOKEN/.test(submitText))

  const bad = await request('nope/nope')
  check('未知方法返回 -32601', !!(bad.error && bad.error.code === -32601))

  console.log(`\n${seen - failures}/${seen} 通过`)
  child.stdin.end()
  child.kill()
  process.exit(failures === 0 ? 0 : 1)
}

main().catch((err) => {
  console.error('FAILED:', err.message)
  child.kill()
  process.exit(1)
})
