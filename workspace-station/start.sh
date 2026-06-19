#!/usr/bin/env bash
# Workspace Station - Unix/Mac 启动脚本
set -e

echo "===================================="
echo "  Workspace Station - 项目工作台"
echo "===================================="
echo ""

# 检查 Node.js
if ! command -v node &>/dev/null; then
    echo "[错误] 未检测到 Node.js，请先安装 Node.js"
    exit 1
fi

echo "[信息] 正在启动 Workspace Station..."
echo "[信息] 默认访问地址: http://127.0.0.1:8765"
echo ""

node server.js
