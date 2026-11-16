#!/usr/bin/env node
/**
 * 成分镜 · MCP Server（stdio）
 *
 * 把「成分库」以 Model Context Protocol 的方式暴露给任意支持 MCP 的客户端
 * （Claude Desktop、Cursor、自研 Agent 等），让它能查成分、查法规限量、按角色列示例。
 *
 * 三个工具都是只读的：这个 server 不写任何文件。
 *
 * 零依赖：只用 Node 内置模块，协议手写（换行分隔的 JSON-RPC 2.0）。
 *
 * 用法：
 *   node mcp/server.mjs
 *
 * 数据来源（按顺序）：
 *   1. 环境变量 COSME_DATA_DIR 指向目录下的 ingredients.json
 *   2. 默认 ~/.cosme/data/ingredients.json
 *   3. 仓库内置 server/src/main/resources/ingredients.json
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import readline from 'node:readline'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(HERE, '..')

const PROTOCOL_VERSION = '2024-11-05'
const BACKEND = (process.env.COSME_BACKEND || 'http://127.0.0.1:8082').replace(/\/+$/, '')
const MCP_TOKEN = (process.env.COSME_MCP_TOKEN || '').trim()
const SERVER_INFO = { name: 'cosme-ingredients', version: '1.0.0' }

/* ------------------------------------------------------------------ 成分库 */

function candidateFiles() {
  const files = []
  const dir = process.env.COSME_DATA_DIR
  if (dir) files.push(path.join(dir, 'ingredients.json'))
  files.push(path.join(os.homedir(), '.cosme', 'data', 'ingredients.json'))
  files.push(path.join(ROOT, 'server', 'src', 'main', 'resources', 'ingredients.json'))
  return files
}

function loadLibrary() {
  for (const file of candidateFiles()) {
    try {
      const raw = fs.readFileSync(file, 'utf8')
      const data = JSON.parse(raw)
      if (Array.isArray(data) && data.length) {
        return { items: data, file }
      }
    } catch {
      /* 换下一个 */
    }
  }
  return { items: [], file: null }
}

const library = loadLibrary()

/** 与后端 IngredientRepository.norm 保持一致：小写、去空格、半角括号转全角 */
function norm(value) {
  return String(value == null ? '' : value)
    .toLowerCase()
    .replace(/ /g, '')
    .replace(/\u3000/g, '')
    .replace(/\(/g, '（')
    .replace(/\)/g, '）')
    .trim()
}

const index = new Map()
for (const item of library.items) {
  const names = [item.n, ...(Array.isArray(item.a) ? item.a : [])]
  for (const name of names) {
    const key = norm(name)
    if (key && !index.has(key)) index.set(key, item)
  }
}

function exact(name) {
  return index.get(norm(name)) || null
}

function suggestions(name, limit = 5) {
  const key = norm(name)
  if (!key) return []
  const out = []
  for (const item of library.items) {
    const names = [item.n, ...(Array.isArray(item.a) ? item.a : [])].map(norm)
    if (names.some((n) => n.includes(key) || key.includes(n))) {
      out.push(item.n)
      if (out.length >= limit) break
    }
  }
  return out
}

function brief(item) {
  return {
    标准名: item.n || '',
    别名: Array.isArray(item.a) ? item.a : [],
    角色: item.r || '',
    分类: item.c || '',
    一句话作用: item.f || '',
    法规限量: item.lm || '',
    敏感肌: item.sn || '',
    孕期: item.pg || '',
    致痘: item.cd == null ? null : item.cd
  }
}

/* ------------------------------------------------------------------ 工具定义 */

const TOOLS = [
  {
    name: 'search_ingredient',
    description:
      '在成分镜的成分库里精确查询一个化妆品成分（支持中文名、INCI、别名），返回角色、分类、一句话作用与风险标签。查不到时会给出相近名称。',
    inputSchema: {
      type: 'object',
      properties: {
        name: { type: 'string', description: '成分名，例如：烟酰胺 / Niacinamide / 柠檬烯' }
      },
      required: ['name']
    }
  },
  {
    name: 'lookup_limit',
    description: '查询某个成分在《化妆品安全技术规范》里的限量记录；没有记录时明确返回未收录，不要推测。',
    inputSchema: {
      type: 'object',
      properties: {
        name: { type: 'string', description: '成分名' }
      },
      required: ['name']
    }
  },
  {
    name: 'submit_research',
    description:
      '把一个成分库里没有的成分提交到研究队列。后台 Agent 会查证、自动收录进成分库，之后重新分析就能识别它。会真实触发模型调用，请只在确实需要时使用。',
    inputSchema: {
      type: 'object',
      properties: {
        name: { type: 'string', description: '要研究的成分名，例如：辛酰羟肟酸' },
        context: { type: 'string', description: '它出现的成分表片段（可选，帮助模型判断）' }
      },
      required: ['name']
    }
  },
  {
    name: 'list_by_role',
    description: '按角色（基质/功效/防腐/香精/防晒/表活）列出成分示例，用于判断某个成分大概属于哪一类。',
    inputSchema: {
      type: 'object',
      properties: {
        role: {
          type: 'string',
          enum: ['基质', '功效', '防腐', '香精', '防晒', '表活'],
          description: '成分角色'
        },
        limit: { type: 'integer', description: '最多返回几条，默认 10，上限 30' }
      },
      required: ['role']
    }
  }
]

/* ------------------------------------------------------------------ 工具实现 */

async function callTool(name, args) {
  const arg = args || {}
  switch (name) {
    case 'search_ingredient': {
      const hit = exact(arg.name)
      if (hit) return { found: true, source: library.file, item: brief(hit) }
      return {
        found: false,
        message: `成分库里没有「${arg.name}」。不要编造它的作用或限量。`,
        suggestions: suggestions(arg.name)
      }
    }
    case 'lookup_limit': {
      const hit = exact(arg.name)
      if (!hit) return { found: false, message: `成分库里没有「${arg.name}」，无法给出限量。` }
      const limit = (hit.lm || '').trim()
      return {
        found: true,
        name: hit.n,
        limit: limit || null,
        message: limit ? `${hit.n} 的限量：${limit}` : `${hit.n} 在库里没有限量记录`
      }
    }
    case 'list_by_role': {
      const role = String(arg.role || '').trim()
      const limit = Math.min(Math.max(Number(arg.limit) || 10, 1), 30)
      const items = library.items.filter((item) => item.r === role).slice(0, limit)
      return {
        role,
        count: items.length,
        items: items.map((item) => ({ name: item.n, function: item.f || '' }))
      }
    }
    case 'submit_research':
      return submitResearch(arg)
    default:
      return { error: `未知工具：${name}` }
  }
}

/**
 * 把成分提交进研究队列：走后端的 /api/mcp/research。
 * 这个工具会真实触发模型调用，所以需要显式配置 COSME_MCP_TOKEN 才启用。
 */
async function submitResearch(args) {
  const name = String(args.name || '').trim()
  if (!name) return { error: '缺少 name' }
  if (!MCP_TOKEN) {
    return {
      error: 'MCP 写入口未启用：启动 server 时设置 COSME_MCP_TOKEN（与后端同一个值）。只读的三个工具不受影响。'
    }
  }
  try {
    const res = await fetch(`${BACKEND}/api/mcp/research`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-MCP-Token': MCP_TOKEN
      },
      body: JSON.stringify({ name, context: args.context || '' }),
      signal: AbortSignal.timeout(8000)
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) return { error: data.error || `后端返回 ${res.status}` }
    return data
  } catch (e) {
    return {
      error: `连不上后端（${BACKEND}）：${e.message}。请先启动仓库根目录的 start-server.bat`
    }
  }
}

/* ------------------------------------------------------------------ JSON-RPC */

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n')
}

function reply(id, result) {
  send({ jsonrpc: '2.0', id, result })
}

function fail(id, code, message) {
  send({ jsonrpc: '2.0', id, error: { code, message } })
}

async function handle(req) {
  const { id, method, params } = req

  // 通知类消息没有 id，不需要响应
  if (id === undefined || id === null) {
    if (method === 'notifications/initialized') return
    return
  }

  if (method === 'initialize') {
    reply(id, {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: { tools: { listChanged: false } },
      serverInfo: SERVER_INFO,
      instructions:
        '这是成分镜的成分库。所有答案必须来自工具返回的事实；查不到就说不确定，不要编造成分作用或法规限量。'
    })
    return
  }

  if (method === 'ping') {
    reply(id, {})
    return
  }

  if (method === 'tools/list') {
    reply(id, { tools: TOOLS })
    return
  }

  if (method === 'tools/call') {
    const name = params && params.name
    const args = (params && params.arguments) || {}
    const result = await callTool(name, args)
    if (result && result.error) {
      reply(id, { content: [{ type: 'text', text: JSON.stringify(result) }], isError: true })
      return
    }
    reply(id, {
      content: [{ type: 'text', text: JSON.stringify(result, null, 2) }],
      isError: false
    })
    return
  }

  if (method === 'resources/list' || method === 'prompts/list') {
    reply(id, method === 'resources/list' ? { resources: [] } : { prompts: [] })
    return
  }

  fail(id, -32601, `Method not found: ${method}`)
}

function main() {
  // 启动时把数据来源打到 stderr：stdout 只能走协议消息
  process.stderr.write(
    `[cosme-mcp] 已加载成分 ${library.items.length} 条（${library.file || '未找到数据文件'}）\n`
  )
  process.stderr.write(
    `[cosme-mcp] 写入口：${MCP_TOKEN ? '已启用（后端 ' + BACKEND + '）' : '未启用（设置 COSME_MCP_TOKEN 后可用 submit_research）'}\n`
  )

  const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity })
  let inflight = 0
  rl.on('line', (line) => {
    const text = line.trim()
    if (!text) return
    let req
    try {
      req = JSON.parse(text)
    } catch {
      send({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Parse error' } })
      return
    }
    inflight += 1
    handle(req)
      .catch((err) => {
        fail(req && req.id, -32603, String(err && err.message ? err.message : err))
      })
      .finally(() => { inflight -= 1 })
  })

  // stdin 关掉时不要立刻退出：submit_research 是异步的，等它落地再走
  rl.on('close', async () => {
    const started = Date.now()
    while (inflight > 0 && Date.now() - started < 5000) {
      await new Promise((r) => setTimeout(r, 50))
    }
    process.exit(0)
  })
}

main()
