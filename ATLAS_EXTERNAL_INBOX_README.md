# Atlas 外部投递箱接入说明

这个接口给其他项目使用：外部项目把文件投递到 Atlas，Atlas 不会立刻入库。文件会先进入“投递箱”，等待用户人工确认。

确认后流程是：

1. 用户在 Atlas 顶部进入 `投递箱`。
2. 用户选择这次请求里哪些文件要入库。
3. 用户选择入库方式：`AI 辅助入库` 或 `手动指定入库`。
4. AI 辅助模式会自动选择知识库、分类路径和标签；手动模式由用户选择目标知识库、分类前缀和做笔记 Agent。
5. Atlas 批量导入文件到知识库。
6. 每个成功导入的文件会自动触发 `AI 做笔记`。
7. Agent 返回的 `VCP_AI_MEMORY` 会进入 VCP 同步中心，之后再由用户决定是否同步到 VCP 日记本。

## 基础信息

默认服务地址：

```text
http://localhost:8080
```

所有投递接口都需要登录后的 JWT：

```http
Authorization: Bearer <ATLAS_TOKEN>
```

当前 MVP 不开放匿名投递。也就是说，其他项目要么使用用户登录拿到的 token，要么后续再扩展“项目级 API Key”。

## 1. 登录获取 Token

```bash
curl -X POST "http://localhost:8080/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"account\":\"你的账号\",\"password\":\"你的密码\"}"
```

响应里的 `data.token` 就是后续投递要用的 token。

## 2. 创建投递请求

接口：

```http
POST /api/inbox/requests
Content-Type: multipart/form-data
```

字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `sourceProject` | 否 | 来源项目名，例如 `DeepWiki`、`NovelWorkflow` |
| `title` | 否 | 这次投递的标题 |
| `description` | 否 | 给用户看的说明 |
| `files` | 是 | 可重复字段，上传一个或多个文件 |
| `paths` | 否 | 可重复字段，和 `files` 顺序对应，表示原始相对路径 |

限制：

- 一次最多 100 个文件。
- 单个文件最多 80MB。
- 支持任意文件保存；可提取文本的文件后续 AI 做笔记效果更好。

## 3. curl 示例

```bash
curl -X POST "http://localhost:8080/api/inbox/requests" \
  -H "Authorization: Bearer <ATLAS_TOKEN>" \
  -F "sourceProject=DeepWiki" \
  -F "title=DeepWiki 生成资料待入库" \
  -F "description=由 DeepWiki 生成的仓库说明、模块页和问答资料，请用户确认后入库。" \
  -F "files=@./overview.md" \
  -F "paths=deepwiki/overview.md" \
  -F "files=@./architecture.md" \
  -F "paths=deepwiki/architecture.md"
```

## 4. JavaScript 示例

```js
async function submitToAtlasInbox({ token, files }) {
  const form = new FormData();
  form.append("sourceProject", "DeepWiki");
  form.append("title", "DeepWiki 生成资料待入库");
  form.append("description", "用户确认后再进入 Atlas 知识库。");

  for (const item of files) {
    form.append("files", item.file, item.name);
    form.append("paths", item.path || item.name);
  }

  const response = await fetch("http://localhost:8080/api/inbox/requests", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
    },
    body: form,
  });

  const body = await response.json();
  if (!response.ok || body.code !== 0) {
    throw new Error(body.message || `Atlas inbox submit failed: ${response.status}`);
  }
  return body.data;
}
```

## 5. PowerShell 示例

```powershell
$token = "<ATLAS_TOKEN>"
$form = @{
  sourceProject = "NovelWorkflow"
  title = "小说设定资料待入库"
  description = "由外部项目整理出的设定文件，请用户确认后入库。"
  files = Get-Item ".\角色设定.md"
  paths = "小说设定\角色设定.md"
}

Invoke-RestMethod `
  -Uri "http://localhost:8080/api/inbox/requests" `
  -Method Post `
  -Headers @{ Authorization = "Bearer $token" } `
  -Form $form
```

## 6. 查看投递状态

Atlas 前端：

```text
顶部导航 -> 投递箱
```

后端接口：

```http
GET /api/inbox/requests?status=pending
```

状态说明：

| 状态 | 含义 |
| --- | --- |
| `pending` | 等待用户审核 |
| `imported` | 已全部入库 |
| `partial` | 部分成功，部分失败 |
| `rejected` | 用户驳回 |
| `failed` | 单个文件导入失败 |
| `skipped` | 用户没有选择该文件 |

## 7. 设计边界

外部项目只负责“投递候选资料”，不要直接替用户写入知识库。

Atlas 负责：

- 文件暂存
- 用户审核
- 知识库导入
- 自动 AI 做笔记
- 生成 VCP 记忆草稿
- 后续 VCP 同步

这样能避免随便丢进来的文件污染知识库，也保留人工判断这个关键步骤。
