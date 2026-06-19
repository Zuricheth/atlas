# Atlas Frontend

Atlas 前端是一个 Vue 3 + TypeScript + Vite 单页应用，负责登录注册、知识库/笔记管理、资料导入、搜索、RAG 问答和 AI 设置。

## 配置

前端后端地址由 `VITE_API_ORIGIN` 控制。推荐在项目根目录复制 `config.env.example` 为 `config.env` 后填写：

```text
VITE_API_ORIGIN=http://localhost:8080
```

使用根目录 `start-atlas.bat` 启动时，脚本会自动读取 `config.env` 并传给 Vite。手动运行 `npm run dev` 时，也可以在当前 shell 中设置同名环境变量。

## 命令

```bash
npm install
npm run dev
npm run build
```
