const http = require("http");
const fs = require("fs");
const path = require("path");
const net = require("net");
const { spawn, execFile } = require("child_process");
const { URL } = require("url");

const host = process.env.WORKSTATION_HOST || "127.0.0.1";
const port = Number(process.env.WORKSTATION_PORT || 8765);
const rootDir = __dirname;
const workspaceRoot = path.resolve(rootDir, "..");
const publicDir = path.join(rootDir, "public");
const configPath = path.join(rootDir, "projects.json");

const contentTypes = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml; charset=utf-8",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".webp": "image/webp",
  ".gif": "image/gif",
  ".ico": "image/x-icon"
};

function readConfig() {
  const raw = fs.readFileSync(configPath, "utf8");
  const data = JSON.parse(raw);
  return Array.isArray(data.projects) ? data.projects : [];
}

function writeConfig(projects) {
  fs.writeFileSync(configPath, `${JSON.stringify({ projects }, null, 2)}\n`, "utf8");
}

function resolveInsideWorkspace(value) {
  return path.resolve(rootDir, value || ".");
}

function resolveWorkspaceFile(value) {
  const text = String(value || "").trim();
  if (!text) return "";
  return path.isAbsolute(text) ? path.resolve(text) : path.resolve(workspaceRoot, text);
}

function parseEnvFile(filePath) {
  if (!fs.existsSync(filePath)) return {};
  return fs.readFileSync(filePath, "utf8").split(/\r?\n/).reduce((env, line) => {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) return env;
    const index = trimmed.indexOf("=");
    if (index < 1) return env;
    const key = trimmed.slice(0, index).trim();
    const value = trimmed.slice(index + 1);
    if (key) env[key] = value;
    return env;
  }, {});
}

function envForStart(start, commandConfig = {}) {
  const fileEnv = (start.envFiles || []).reduce((env, file) => {
    return { ...env, ...parseEnvFile(resolveInsideWorkspace(file)) };
  }, {});
  return {
    ...process.env,
    ...fileEnv,
    ...(start.env || {}),
    ...(commandConfig.env || {})
  };
}

function send(res, status, body, headers = {}) {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8", ...headers });
  res.end(body);
}

function sendJson(res, status, value) {
  send(res, status, JSON.stringify(value), { "Content-Type": "application/json; charset=utf-8" });
}

function readBody(req, limit = 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > limit) {
        reject(new Error("Request body is too large"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

function isPortOpen(targetPort, targetHost = "127.0.0.1") {
  return new Promise((resolve) => {
    const socket = new net.Socket();
    const finish = (open) => {
      socket.removeAllListeners();
      socket.destroy();
      resolve(open);
    };
    socket.setTimeout(700);
    socket.once("connect", () => finish(true));
    socket.once("timeout", () => finish(false));
    socket.once("error", () => finish(false));
    socket.connect(targetPort, targetHost);
  });
}

function projectPorts(project) {
  if (project.kind === "HTML") return [];
  if (project.listenEnabled === false) return [];
  if (Array.isArray(project.ports) && project.ports.length) return project.ports;
  return [project.backendPort, project.frontendPort]
    .map((value) => Number(value))
    .filter((value, index, values) => Number.isInteger(value) && value > 0 && value < 65536 && values.indexOf(value) === index);
}

async function projectStatus(project) {
  if (project.kind === "HTML") {
    const filePath = project.file ? resolveWorkspaceFile(project.file) : "";
    return {
      id: project.id,
      running: Boolean(filePath && fs.existsSync(filePath)),
      listening: false,
      manageable: false,
      checks: [],
      lastCheckedAt: new Date().toISOString()
    };
  }
  const listening = project.listenEnabled !== false;
  const ports = projectPorts(project);
  const checks = await Promise.all(ports.map(async (item) => {
    const value = typeof item === "number" ? { port: item } : item;
    return {
      port: value.port,
      label: value.label || `:${value.port}`,
      open: await isPortOpen(value.port, value.host || "127.0.0.1")
    };
  }));
  const running = listening && checks.length > 0 && checks.every((item) => item.open);
  return {
    id: project.id,
    running,
    listening,
    manageable: Boolean(project.startScript || project.stopScript || project.start || project.stop || ports.length),
    checks,
    lastCheckedAt: new Date().toISOString()
  };
}

function publicProject(project, status) {
  const filePath = project.file ? resolveWorkspaceFile(project.file) : "";
  const frontendPort = Number(project.frontendPort) || "";
  const backendPort = Number(project.backendPort) || "";
  return {
    id: project.id,
    name: project.name,
    kind: project.kind || (project.file ? "HTML" : "Project"),
    accent: project.accent,
    summary: project.summary,
    frontendPort,
    backendPort,
    htmlFile: project.file || "",
    startScript: project.startScript || "",
    stopScript: project.stopScript || "",
    listenEnabled: status.listening,
    url: project.url || (project.file ? `/files/${project.id}/` : frontendPort ? `http://127.0.0.1:${frontendPort}` : ""),
    path: project.path ? resolveInsideWorkspace(project.path) : "",
    filePath,
    running: status.running,
    manageable: status.manageable,
    checks: status.checks,
    lastCheckedAt: status.lastCheckedAt
  };
}

function findProject(id) {
  return readConfig().find((project) => project.id === id);
}

function slugify(value) {
  const slug = String(value || "")
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9\u4e00-\u9fa5]+/g, "-")
    .replace(/^-+|-+$/g, "");
  return slug || `project-${Date.now()}`;
}

function splitCommandLine(value) {
  const text = String(value || "").trim();
  if (!text) return null;
  const parts = [];
  text.replace(/"([^"]*)"|'([^']*)'|[^\s]+/g, (match, doubleQuoted, singleQuoted) => {
    parts.push(doubleQuoted ?? singleQuoted ?? match);
    return match;
  });
  return parts.length ? { command: parts[0], args: parts.slice(1) } : null;
}

function parsePorts(value) {
  if (Array.isArray(value)) {
    return value
      .map((item) => Number(typeof item === "number" ? item : item.port || item))
      .filter((item) => Number.isInteger(item) && item > 0 && item < 65536);
  }
  return String(value || "")
    .split(/[,，\s]+/)
    .map((item) => Number(item.trim()))
    .filter((item) => Number.isInteger(item) && item > 0 && item < 65536);
}

function parsePort(value, label) {
  if (value === undefined || value === null || value === "") return "";
  const port = Number(value);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error(`${label} 必须是 1-65535 的端口号`);
  return port;
}

function enabledValue(value) {
  return value === true || value === "true" || value === "on" || value === "1";
}

function projectFromInput(input, existing = {}) {
  const name = String(input.name || "").trim();
  if (!name) throw new Error("项目名称不能为空");

  const frontendPort = parsePort(input.frontendPort, "前端端口");
  const backendPort = parsePort(input.backendPort, "后端端口");
  const isHtmlProject = input.projectMode === "html" || String(input.htmlFile || "").trim() !== "";
  const htmlFile = String(input.htmlFile || "").trim();
  if (isHtmlProject && !htmlFile) throw new Error("HTML 文件位置不能为空");
  if (htmlFile && !/\.html?$/i.test(htmlFile)) throw new Error("HTML 文件必须以 .html 或 .htm 结尾");
  if (htmlFile) {
    const resolvedHtml = resolveWorkspaceFile(htmlFile);
    if (!fs.existsSync(resolvedHtml) || !fs.statSync(resolvedHtml).isFile()) {
      throw new Error("HTML 文件不存在");
    }
  }
  const listenEnabled = isHtmlProject ? false : enabledValue(input.listenEnabled);
  const ports = listenEnabled ? [backendPort, frontendPort].filter(Boolean) : [];
  const project = {
    ...existing,
    file: undefined,
    id: existing.id,
    name,
    kind: isHtmlProject ? "HTML" : "Project",
    accent: String(input.accent || "#476f8a").trim(),
    summary: String(input.summary || "").trim(),
    frontendPort,
    backendPort,
    listenEnabled,
    ports,
    startScript: String(input.startScript || "").trim(),
    stopScript: String(input.stopScript || "").trim()
  };
  if (isHtmlProject) project.file = htmlFile;
  return project;
}

function createProject(input) {
  const projects = readConfig();
  const name = String(input.name || "").trim();
  const baseId = slugify(input.id || name);
  let id = baseId;
  let suffix = 2;
  while (projects.some((project) => project.id === id)) {
    id = `${baseId}-${suffix}`;
    suffix += 1;
  }

  const project = projectFromInput(input, { id });

  projects.push(project);
  writeConfig(projects);
  return project;
}

function updateProject(id, input) {
  const projects = readConfig();
  const index = projects.findIndex((project) => project.id === id);
  if (index < 0) return null;
  const project = projectFromInput(input, projects[index]);
  projects[index] = project;
  writeConfig(projects);
  return project;
}

function deleteProject(id) {
  const projects = readConfig();
  const index = projects.findIndex((project) => project.id === id);
  if (index < 0) return null;
  const [project] = projects.splice(index, 1);
  writeConfig(projects);
  return project;
}

function spawnCommand(start, commandConfig) {
  const cwd = resolveInsideWorkspace(commandConfig.cwd || start.cwd || ".");
  const command = commandConfig.command || start.command;
  const args = commandConfig.args || start.args || [];
  if (!command) throw new Error("Missing start command");
  const child = spawn(command, args, {
    cwd,
    detached: true,
    windowsHide: true,
    stdio: "ignore",
    shell: false,
    env: envForStart(start, commandConfig)
  });
  child.unref();
  return child.pid;
}

function runScript(script) {
  const text = String(script || "").trim();
  if (!text) throw new Error("Missing script");
  const child = spawn("cmd.exe", ["/d", "/s", "/c", text], {
    cwd: workspaceRoot,
    detached: true,
    windowsHide: true,
    stdio: "ignore",
    shell: false,
    env: process.env
  });
  child.unref();
  return child.pid;
}

function startProject(project) {
  if (project.startScript) {
    return { ok: true, pids: [runScript(project.startScript)], message: "启动脚本已发送" };
  }
  if (!project.start) {
    return { ok: false, message: "这个项目没有登记启动命令" };
  }
  const start = project.start;
  const commands = Array.isArray(start.commands) && start.commands.length ? start.commands : [start];
  const pids = commands.map((commandConfig) => spawnCommand(start, commandConfig));
  return { ok: true, pids, message: "启动命令已发送" };
}

function netstat() {
  return new Promise((resolve, reject) => {
    execFile("netstat", ["-ano"], { windowsHide: true }, (error, stdout) => {
      if (error) reject(error);
      else resolve(stdout);
    });
  });
}

async function pidsForPorts(ports) {
  const output = await netstat();
  const wanted = new Set(ports.map(String));
  const pids = new Set();
  output.split(/\r?\n/).forEach((line) => {
    if (!/\bLISTENING\b/i.test(line)) return;
    const parts = line.trim().split(/\s+/);
    const localAddress = parts[1] || "";
    const pid = parts[parts.length - 1];
    const match = localAddress.match(/:(\d+)$/);
    if (match && wanted.has(match[1]) && /^\d+$/.test(pid)) pids.add(pid);
  });
  return [...pids];
}

function taskkill(pid) {
  return new Promise((resolve) => {
    execFile("taskkill", ["/F", "/PID", String(pid)], { windowsHide: true }, (error, stdout, stderr) => {
      resolve({ pid, ok: !error, output: stdout || stderr || "" });
    });
  });
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function stopProject(project) {
  if (project.stopScript) {
    return { ok: true, pids: [runScript(project.stopScript)], message: "关闭脚本已发送" };
  }
  const ports = projectPorts(project).map((item) => typeof item === "number" ? item : item.port).filter(Boolean);
  if (!ports.length) return { ok: false, message: "这个项目没有登记可停止端口" };
  const pids = await pidsForPorts(ports);
  const results = await Promise.all(pids.map(taskkill));
  return {
    ok: true,
    message: pids.length ? "停止命令已发送" : "没有发现运行中的端口",
    pids,
    results
  };
}

async function restartProject(project) {
  const stopped = await stopProject(project);
  await delay(1400);
  const started = startProject(project);
  return {
    ok: started.ok,
    message: started.ok ? "重新启动命令已发送" : started.message,
    stop: stopped,
    start: started
  };
}

function serveStatic(req, res, pathname) {
  const filePath = pathname === "/" ? path.join(publicDir, "index.html") : path.join(publicDir, pathname);
  const normalized = path.normalize(filePath);
  if (!normalized.startsWith(publicDir)) {
    send(res, 403, "Forbidden", { "Content-Type": "text/plain; charset=utf-8" });
    return;
  }
  if (!fs.existsSync(normalized) || !fs.statSync(normalized).isFile()) {
    send(res, 404, "Not found", { "Content-Type": "text/plain; charset=utf-8" });
    return;
  }
  send(res, 200, fs.readFileSync(normalized), {
    "Content-Type": contentTypes[path.extname(normalized).toLowerCase()] || "application/octet-stream"
  });
}

function serveProjectFile(project, res, assetPath = "") {
  if (!project || !project.file) {
    send(res, 404, "Not found", { "Content-Type": "text/plain; charset=utf-8" });
    return;
  }
  const htmlPath = resolveWorkspaceFile(project.file);
  if (!/\.html?$/i.test(htmlPath)) {
    send(res, 403, "Forbidden", { "Content-Type": "text/plain; charset=utf-8" });
    return;
  }
  const baseDir = path.dirname(htmlPath);
  const filePath = assetPath ? path.resolve(baseDir, assetPath) : htmlPath;
  if (filePath !== baseDir && !filePath.startsWith(baseDir + path.sep)) {
    send(res, 403, "Forbidden", { "Content-Type": "text/plain; charset=utf-8" });
    return;
  }
  if (!fs.existsSync(filePath) || !fs.statSync(filePath).isFile()) {
    send(res, 404, "Not found", { "Content-Type": "text/plain; charset=utf-8" });
    return;
  }
  send(res, 200, fs.readFileSync(filePath), {
    "Content-Type": contentTypes[path.extname(filePath).toLowerCase()] || "application/octet-stream"
  });
}

const server = http.createServer(async (req, res) => {
  try {
    const requestUrl = new URL(req.url, `http://${host}:${port}`);
    const pathname = decodeURIComponent(requestUrl.pathname);

    if (req.method === "GET" && pathname === "/api/health") {
      sendJson(res, 200, { ok: true, name: "workspace-station", time: new Date().toISOString() });
      return;
    }

    if (req.method === "GET" && pathname === "/api/projects") {
      const projects = readConfig();
      const statuses = await Promise.all(projects.map(projectStatus));
      sendJson(res, 200, {
        ok: true,
        projects: projects.map((project, index) => publicProject(project, statuses[index]))
      });
      return;
    }

    if (req.method === "POST" && pathname === "/api/projects") {
      const body = await readBody(req);
      const input = body ? JSON.parse(body) : {};
      const project = createProject(input);
      const status = await projectStatus(project);
      sendJson(res, 201, { ok: true, project: publicProject(project, status), message: "项目已添加" });
      return;
    }

    const projectMatch = pathname.match(/^\/api\/projects\/([^/]+)$/);
    if (projectMatch && req.method === "PUT") {
      const body = await readBody(req);
      const input = body ? JSON.parse(body) : {};
      const project = updateProject(projectMatch[1], input);
      if (!project) {
        sendJson(res, 404, { ok: false, message: "项目不存在" });
        return;
      }
      const status = await projectStatus(project);
      sendJson(res, 200, { ok: true, project: publicProject(project, status), message: "项目已更新" });
      return;
    }

    if (projectMatch && req.method === "DELETE") {
      const project = deleteProject(projectMatch[1]);
      if (!project) {
        sendJson(res, 404, { ok: false, message: "项目不存在" });
        return;
      }
      sendJson(res, 200, { ok: true, id: project.id, message: "项目已删除" });
      return;
    }

    const deleteMatch = pathname.match(/^\/api\/projects\/([^/]+)\/delete$/);
    if (deleteMatch && req.method === "POST") {
      await readBody(req);
      const project = deleteProject(deleteMatch[1]);
      if (!project) {
        sendJson(res, 404, { ok: false, message: "项目不存在" });
        return;
      }
      sendJson(res, 200, { ok: true, id: project.id, message: "项目已删除" });
      return;
    }

    const actionMatch = pathname.match(/^\/api\/projects\/([^/]+)\/(start|stop|restart)$/);
    if (actionMatch && req.method === "POST") {
      await readBody(req);
      const project = findProject(actionMatch[1]);
      if (!project) {
        sendJson(res, 404, { ok: false, message: "项目不存在" });
        return;
      }
      const action = actionMatch[2];
      const result = action === "start" ? startProject(project) : action === "stop" ? await stopProject(project) : await restartProject(project);
      sendJson(res, result.ok ? 200 : 400, result);
      return;
    }

    const fileMatch = pathname.match(/^\/files\/([^/]+)\/?(.*)$/);
    if (fileMatch && req.method === "GET") {
      serveProjectFile(findProject(fileMatch[1]), res, fileMatch[2] || "");
      return;
    }

    if (req.method === "GET") {
      serveStatic(req, res, pathname);
      return;
    }

    sendJson(res, 405, { ok: false, message: "Method not allowed" });
  } catch (error) {
    sendJson(res, 500, { ok: false, message: error.message });
  }
});

server.listen(port, host, () => {
  console.log(`Workspace Station: http://${host}:${port}/`);
});
