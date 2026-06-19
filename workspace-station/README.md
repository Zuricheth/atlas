# Workspace Station — 项目工作台

一个轻量级的本地项目管理工作台，用于统一管理、启动和监控本地开发项目。

![Node.js](https://img.shields.io/badge/node-%3E%3D16-brightgreen)
![License](https://img.shields.io/badge/license-MIT-blue)

---

## 功能

- **项目注册** — 通过 Web UI 添加本地项目，支持端口项目、HTML 单文件
- **一键启停** — 从浏览器直接启动/停止/重启项目进程
- **健康检查** — 自动检测运行状态，实时更新 UI
- **项目导航** — 侧边栏快速切换，网格视图展示卡片
- **端口监控** — 跟踪每个项目的前端/后端端口占用情况
- **多端适配** — 支持端口项目、HTML 文件快速索引

## 快速开始

### 前置要求

- [Node.js](https://nodejs.org/) ≥ 16
- npm（Node.js 自带）

### 启动

```bash
# Windows
start.bat

# 或手动
node server.js
```

启动后浏览器访问 **[http://127.0.0.1:8765](http://127.0.0.1:8765)**

## 脚本说明

| 脚本 | 用途 |
|------|------|
| `start.bat` | Windows 快速启动（推荐） |
| `start.sh` | macOS / Linux 启动 |
| `dev.bat` | 开发模式（如果安装了 nodemon 则启用热重载） |

## 项目结构

```
workspace-station/
├── server.js          # HTTP 服务 + 进程管理
├── package.json       # 依赖与脚本
├── projects.json      # 项目注册数据
├── start.bat          # Windows 启动脚本
├── start.sh           # Unix 启动脚本
├── dev.bat            # 开发模式启动脚本
├── README.md          # 本文件
└── public/            # 前端静态资源
    ├── index.html     # 主页面
    ├── app.js         # 前端逻辑
    ├── styles.css     # 样式
    └── favicon.svg    # 图标
```

## 开发

```bash
# 启动开发模式（自动热重载）
dev.bat

# 或手动安装 nodemon 后
nodemon server.js
```

## 许可

MIT License
