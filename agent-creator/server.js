import http from "node:http";
import https from "node:https";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const host = process.env.AGENT_CREATOR_HOST || "127.0.0.1";
const port = Number(process.env.AGENT_CREATOR_PORT || 8796);
const gatewayUrl = new URL(process.env.VCP_GATEWAY_URL || "http://localhost:6005");
const envApiKey = process.env.VCP_API_KEY || "";
const root = path.dirname(fileURLToPath(import.meta.url));

const types = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".gif": "image/gif",
  ".webp": "image/webp",
  ".ico": "image/x-icon",
  ".woff": "font/woff",
  ".woff2": "font/woff2"
};

function send(res, status, body, type = "text/plain; charset=utf-8") {
  res.writeHead(status, { "Content-Type": type });
  res.end(body);
}

function safeDecodePathname(pathname) {
  try {
    return decodeURIComponent(pathname);
  } catch {
    return null;
  }
}

function readBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on("data", (chunk) => chunks.push(chunk));
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", () => resolve(Buffer.alloc(0)));
  });
}

// 反向代理：让浏览器同源请求 /proxy/* 规避 CORS，并可由服务端注入 API Key
function proxyRequest(req, res, body) {
  const isTls = gatewayUrl.protocol === "https:";
  const lib = isTls ? https : http;
  const targetPath = req.url.replace(/^\/proxy/, "") || "/";
  const headers = {
    "Content-Type": req.headers["content-type"] || "application/json",
    "Accept": req.headers["accept"] || "application/json"
  };
  if (envApiKey) {
    headers["Authorization"] = `Bearer ${envApiKey}`;
  } else if (req.headers["authorization"]) {
    headers["Authorization"] = req.headers["authorization"];
  }

  const upstream = lib.request(
    {
      protocol: gatewayUrl.protocol,
      hostname: gatewayUrl.hostname,
      port: gatewayUrl.port || (isTls ? 443 : 80),
      path: targetPath,
      method: req.method,
      headers
    },
    (upRes) => {
      res.writeHead(upRes.statusCode || 502, upRes.headers);
      upRes.pipe(res);
    }
  );
  upstream.on("error", (err) => {
    send(res, 502, `Gateway error: ${err.message}`);
  });
  if (body && body.length) upstream.write(body);
  upstream.end();
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, `http://${host}:${port}`);

    if ((url.pathname === "/proxy" || url.pathname.startsWith("/proxy/")) && req.method === "POST") {
      const body = await readBody(req);
      proxyRequest(req, res, body);
      return;
    }

    const decoded = safeDecodePathname(url.pathname === "/" ? "/index.html" : url.pathname);
    if (decoded === null) {
      send(res, 400, "Bad request");
      return;
    }
    const requested = decoded === "/index.html" ? "index.html" : decoded.slice(1);
    const filePath = path.resolve(root, requested);
    if (!filePath.startsWith(root)) {
      send(res, 403, "Forbidden");
      return;
    }
    fs.readFile(filePath, (error, data) => {
      if (error) {
        send(res, 404, "Not found");
        return;
      }
      send(res, 200, data, types[path.extname(filePath).toLowerCase()] || "application/octet-stream");
    });
  } catch (err) {
    send(res, 400, `Bad request: ${err.message}`);
  }
});

server.listen(port, host, () => {
  console.log(`VCP Agent Creator: http://${host}:${port}/`);
  console.log(`Gateway proxy: /proxy/* -> ${gatewayUrl.origin}`);
  console.log(envApiKey ? "API Key 由服务端注入（VCP_API_KEY）。" : "API Key 由前端透传（可在环境变量设置 VCP_API_KEY 隐藏）。");
});
