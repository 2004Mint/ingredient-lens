# 成分镜 · MCP Server

把「成分库」按 [Model Context Protocol](https://modelcontextprotocol.io) 暴露给任意支持 MCP 的客户端（Claude Desktop、Cursor、自研 Agent……），
让模型在回答化妆品相关问题时，**只能基于成分库里的事实说话**，查不到就明确说查不到，必要时还能把新成分送进研究队列。

- **默认只读**：三个查询工具不写任何文件；写入口要显式开启令牌。
- **零依赖**：只用 Node 内置模块，协议手写（换行分隔的 JSON-RPC 2.0），`node mcp/server.mjs` 直接跑。
- **与产品同一份数据**：优先读 `~/.cosme/data/ingredients.json`（含研究队列补录的成分），没有就回退仓库内置的 60 条。

## 四个工具

| 工具 | 读写 | 作用 | 返回 |
|---|---|---|---|
| `search_ingredient` | 只读 | 按中文名 / INCI / 别名精确查成分 | 是否收录、标准名、角色、分类、一句话作用、限量与风险标签；未收录时给相近名称 |
| `lookup_limit` | 只读 | 查法规限量 | 限量原文；没有记录时明确返回 `limit: null`，不推测 |
| `list_by_role` | 只读 | 按角色列示例 | 该角色下的成分与一句话作用，用于判断分类 |
| `submit_research` | 写入 | 把库里没有的成分提交到研究队列 | 任务 ID 与状态；后台 Agent 查证后自动收录 |

> 设计原则和产品一致：**认不出就明说，不编造**。未命中时返回的文案是「不要编造它的作用或限量」，
> 服务端也会在 `initialize` 的 instructions 里再强调一次。

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `COSME_DATA_DIR` | `~/.cosme/data` | 成分库所在目录（找不到就回退仓库内置库） |
| `COSME_MCP_TOKEN` | 空（关闭写入口） | 与后端同一个值；不设置时 `submit_research` 返回「未启用」 |
| `COSME_BACKEND` | `http://127.0.0.1:8082` | 后端地址；`submit_research` 会 POST 到 `/api/mcp/research` |

## 启动

```bash
node mcp/server.mjs
```

启动日志走 stderr（stdout 只留给协议消息）：

```
[cosme-mcp] 已加载成分 86 条（C:\Users\你\.cosme\data\ingredients.json）
[cosme-mcp] 写入口：未启用（设置 COSME_MCP_TOKEN 后可用 submit_research）
```

指定数据目录与写入口：

```bash
# PowerShell
$env:COSME_DATA_DIR="C:\Users\你\.cosme\data"
$env:COSME_MCP_TOKEN="换成一个你自己的长字符串"
$env:COSME_BACKEND="http://127.0.0.1:8083"
node mcp/server.mjs
```

## 接到 MCP 客户端

Claude Desktop（`claude_desktop_config.json`）或 Cursor（`~/.cursor/mcp.json`）：

```json
{
  "mcpServers": {
    "cosme-ingredients": {
      "command": "node",
      "args": ["E:/AIWEB/mcp/server.mjs"],
      "env": {
        "COSME_DATA_DIR": "C:/Users/你/.cosme/data",
        "COSME_MCP_TOKEN": "换成一个你自己的长字符串",
        "COSME_BACKEND": "http://127.0.0.1:8083"
      }
    }
  }
}
```

重启客户端后，可以直接问：

> 「烟酰胺是防腐剂吗？柠檬烯对敏感肌有什么风险？成分库里有没有这两个？」

模型会先调用 `search_ingredient`，再根据返回的事实回答；查不到时会说查不到，而不是编一段。

## 写入口的安全边界

- 后端也要设置**同一个** `COSME_MCP_TOKEN` 才会启用 `/api/mcp/research`，否则返回 503；
- 令牌不对返回 401；同一进程 **10 分钟内最多 10 次**，超出返回 429（研究要花模型调用，不能刷）；
- 提交的查询走与产品完全相同的「入队 → Agent 研究 → 自动收录」链路，管理台可见、可删；
- 默认不配置令牌 = 关闭。一个会烧额度的写接口不应该默认对局域网开放。

## 协议实现

stdio 传输，换行分隔的 JSON-RPC 2.0：

| 方法 | 说明 |
|---|---|
| `initialize` | 返回 `protocolVersion: 2024-11-05`、`capabilities.tools`、`serverInfo` 与一段 instructions |
| `notifications/initialized` | 收到即忽略（通知不需要响应） |
| `tools/list` | 返回四个工具及 `inputSchema` |
| `tools/call` | 执行工具，返回 `content[{type:"text"}]`；工具内部错误用 `isError: true` |
| `ping` | 返回 `{}` |
| 其它 | `-32601 Method not found` |

`tools/call` 是异步的：`submit_research` 要访问后端，stdin 关闭时会等请求落地再退出。

## 自测

```bash
node mcp/test-mcp.mjs
```

不依赖任何 MCP 客户端，直接按协议发 JSON-RPC 并断言 10 项：握手、工具清单（4 个）、命中、未命中、限量、写入口未配置时拒绝、未知方法。

```
MCP 冒烟测试
  ✓ initialize 返回 serverInfo
  ✓ 声明 tools 能力
  ✓ tools/list 返回 4 个工具
  ✓ 每个工具都有 inputSchema
  ✓ search_ingredient 命中「烟酰胺」
  ✓ 未命中时返回 found=false
  ✓ 未命中时提示不要编造
  ✓ lookup_limit 返回结构
  ✓ 写入口未配置时拒绝提交
  ✓ 未知方法返回 -32601

10/10 通过
```

## 与「研究 Agent」的关系

产品内部的研究 Agent 用的是同一套工具语义（`search_library` / `lookup_limit` / `list_role_examples`），
只是走的是 Function Calling；这个 MCP Server 把同样的能力开放给外部客户端，并额外给了写入口。

- 产品内：模型在「补全一个没见过的成分」时，自己决定要不要先查限量（见技术文档 4.4 节）。
- 产品外：任何 MCP 客户端都能把成分库当成一个事实来源，查不到时还能把成分送回研究队列。
