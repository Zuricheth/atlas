const grid = document.querySelector("#projectGrid");
const nav = document.querySelector("#projectNav");
const emptyState = document.querySelector("#emptyState");
const runningCount = document.querySelector("#runningCount");
const projectCount = document.querySelector("#projectCount");
const lastChecked = document.querySelector("#lastChecked");
const stageMeta = document.querySelector("#stageMeta");
const refreshButton = document.querySelector("#refreshButton");
const addProjectButton = document.querySelector("#addProjectButton");
const projectDialog = document.querySelector("#projectDialog");
const closeDialogButton = document.querySelector("#closeDialogButton");
const cancelDialogButton = document.querySelector("#cancelDialogButton");
const projectForm = document.querySelector("#projectForm");
const dialogEyebrow = document.querySelector("#dialogEyebrow");
const dialogTitle = document.querySelector("#dialogTitle");
const submitLabel = document.querySelector("#submitLabel");
const modeTabs = [...document.querySelectorAll(".mode-tab")];
const toast = document.querySelector("#toast");

let projects = [];
let toastTimer = null;
let editingProjectId = null;

const icons = {
  open: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 17 17 7"/><path d="M7 7h10v10"/></svg>',
  edit: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m4 20 4.5-1 10-10a2.1 2.1 0 0 0-3-3l-10 10z"/><path d="m13.5 6.5 3 3"/></svg>',
  play: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m8 5 11 7-11 7z"/></svg>',
  restart: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 12a8 8 0 1 1-2.34-5.66"/><path d="M20 4v5h-5"/></svg>',
  stop: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>',
  trash: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 6h18"/><path d="M8 6V4h8v2"/><path d="m19 6-1 14H6L5 6"/><path d="M10 11v5M14 11v5"/></svg>'
};

function escapeHtml(value) {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function showToast(message) {
  toast.textContent = message;
  toast.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove("show"), 2600);
}

async function api(path, options = {}) {
  try {
    const response = await fetch(path, {
      ...options,
      headers: { "Content-Type": "application/json", ...(options.headers || {}) }
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok || data.ok === false) {
      throw new Error(data.message || `请求失败：${response.status}`);
    }
    return data;
  } catch (error) {
    if (error instanceof TypeError || String(error.message || "").toLowerCase().includes("failed to fetch")) {
      throw new Error("工作站服务未启动，请先运行 start-workstation.bat");
    }
    throw error;
  }
}

function formatTime(value) {
  if (!value) return "--:--:--";
  return new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  }).format(new Date(value));
}

function renderNav() {
  nav.replaceChildren(...projects.map((project) => {
    const button = document.createElement("button");
    button.className = `nav-item${project.running ? " running" : ""}`;
    button.type = "button";
    button.innerHTML = `<span class="nav-dot"></span><span>${escapeHtml(project.name)}</span>`;
    button.addEventListener("click", () => {
      document.querySelector(`[data-project-card="${CSS.escape(project.id)}"]`)?.scrollIntoView({
        behavior: "smooth",
        block: "center"
      });
    });
    return button;
  }));
}

function renderProject(project) {
  const article = document.createElement("article");
  article.className = `project-card${project.running ? " running" : ""}`;
  article.dataset.projectCard = project.id;
  article.style.setProperty("--accent", project.accent || "#2f7d68");

  const url = project.url || "";
  const checks = project.checks || [];
  const ports = checks.length
    ? checks.map((check) => `<span class="port${check.open ? " open" : ""}">${escapeHtml(check.label)}</span>`).join("")
    : '<span class="port">no port</span>';
  const path = project.path || project.filePath || "";
  const portSummary = [
    project.kind === "HTML" ? "HTML" : "",
    project.frontendPort ? `前端 ${project.frontendPort}` : "",
    project.backendPort ? `后端 ${project.backendPort}` : ""
  ].filter(Boolean).join(" · ");

  article.innerHTML = `
    <div class="project-main">
      <div class="project-title">
        <h3>${escapeHtml(project.name)}</h3>
        <span class="kind">${escapeHtml(portSummary || project.kind || "Project")}</span>
      </div>
      <p class="summary">${escapeHtml(project.summary || "未填写说明")}</p>
      <p class="path" title="${escapeHtml(path || "点击打开会进入前端端口")}">${escapeHtml(path || "打开项目会进入前端端口")}</p>
    </div>
    <div class="project-health">
      <span class="state"><span class="state-dot"></span>${project.kind === "HTML" ? "静态" : project.listenEnabled === false ? "未监听" : project.running ? "运行中" : "空闲"}</span>
      <div class="ports">${ports}</div>
    </div>
    <div class="card-actions">
      <a href="${escapeHtml(url || "#")}" target="_blank" rel="noreferrer" title="打开项目" aria-label="打开 ${escapeHtml(project.name)}" class="${url ? "" : "disabled"}">${icons.open}</a>
      <button type="button" data-action="edit" title="编辑项目" aria-label="编辑 ${escapeHtml(project.name)}">${icons.edit}</button>
      <button type="button" data-action="start" title="启动项目" aria-label="启动 ${escapeHtml(project.name)}" ${project.manageable ? "" : "disabled"}>${icons.play}</button>
      <button type="button" data-action="restart" title="重新启动项目" aria-label="重新启动 ${escapeHtml(project.name)}" ${project.manageable ? "" : "disabled"}>${icons.restart}</button>
      <button type="button" data-action="stop" class="danger" title="停止项目" aria-label="停止 ${escapeHtml(project.name)}" ${project.manageable ? "" : "disabled"}>${icons.stop}</button>
      <button type="button" data-action="delete" class="danger" title="删除项目记录" aria-label="删除 ${escapeHtml(project.name)}">${icons.trash}</button>
    </div>
  `;

  article.querySelectorAll("button[data-action]").forEach((button) => {
    if (button.dataset.action === "delete") {
      button.addEventListener("click", () => deleteProject(project.id, project.name));
    } else if (button.dataset.action === "edit") {
      button.addEventListener("click", () => openDialog(project));
    } else {
      button.addEventListener("click", () => runAction(project.id, button.dataset.action, project.name));
    }
  });

  const openLink = article.querySelector("a");
  if (!url) {
    openLink.addEventListener("click", (event) => event.preventDefault());
  }

  return article;
}

function render() {
  grid.replaceChildren(...projects.map(renderProject));
  renderNav();
  emptyState.hidden = projects.length > 0;
  projectCount.textContent = String(projects.length);
  runningCount.textContent = String(projects.filter((project) => project.running).length);
  const newest = projects.map((project) => project.lastCheckedAt).filter(Boolean).sort().at(-1);
  lastChecked.textContent = formatTime(newest);
  stageMeta.textContent = projects.length ? `${projects.length} 个项目已接入` : "未接入项目";
}

async function loadProjects(silent = false) {
  refreshButton.disabled = true;
  try {
    const data = await api("/api/projects");
    projects = data.projects || [];
    render();
    if (!silent) showToast("状态已刷新");
  } catch (error) {
    showToast(error.message);
  } finally {
    refreshButton.disabled = false;
  }
}

async function runAction(id, action, name) {
  try {
    const data = await api(`/api/projects/${encodeURIComponent(id)}/${action}`, { method: "POST", body: "{}" });
    showToast(`${name}: ${data.message || "命令已发送"}`);
    const refreshDelay = action === "restart" ? 2600 : action === "start" ? 1800 : 900;
    setTimeout(() => loadProjects(true), refreshDelay);
  } catch (error) {
    showToast(`${name}: ${error.message}`);
  }
}

async function deleteProject(id, name) {
  const confirmed = window.confirm(`删除“${name}”的工作站记录？项目文件不会被删除。`);
  if (!confirmed) return;
  try {
    const data = await api(`/api/projects/${encodeURIComponent(id)}/delete`, { method: "POST", body: "{}" });
    showToast(`${name}: ${data.message || "项目已删除"}`);
    await loadProjects(true);
  } catch (error) {
    showToast(`${name}: ${error.message}`);
  }
}

function openDialog(project = null) {
  editingProjectId = project?.id || null;
  projectForm.reset();
  dialogEyebrow.textContent = editingProjectId ? "Edit Project" : "New Project";
  dialogTitle.textContent = editingProjectId ? "编辑项目" : "新增项目";
  submitLabel.textContent = editingProjectId ? "保存修改" : "保存项目";

  if (project) {
    const isHtml = project.kind === "HTML" || Boolean(project.htmlFile);
    setProjectMode(isHtml ? "html" : "project");
    projectForm.elements.name.value = project.name || "";
    projectForm.elements.summary.value = project.summary || "";
    projectForm.elements.frontendPort.value = project.frontendPort || "";
    projectForm.elements.backendPort.value = project.backendPort || "";
    projectForm.elements.htmlFile.value = project.htmlFile || "";
    projectForm.elements.startScript.value = project.startScript || "";
    projectForm.elements.stopScript.value = project.stopScript || "";
    projectForm.elements.listenEnabled.checked = project.listenEnabled !== false;
  } else {
    setProjectMode("project");
  }
  projectDialog.showModal();
  projectForm.elements.name.focus();
}

function closeDialog() {
  editingProjectId = null;
  projectDialog.close();
}

function formPayload(form) {
  const data = new FormData(form);
  const payload = Object.fromEntries([...data.entries()].map(([key, value]) => [key, String(value).trim()]));
  const isHtml = payload.projectMode === "html";
  if (isHtml) {
    payload.frontendPort = "";
    payload.backendPort = "";
    payload.startScript = "";
    payload.stopScript = "";
    payload.listenEnabled = false;
  } else {
    payload.htmlFile = "";
    payload.listenEnabled = form.elements.listenEnabled.checked;
  }
  return payload;
}

function setProjectMode(mode) {
  const isHtml = mode === "html";
  projectForm.elements.projectMode.value = isHtml ? "html" : "project";
  modeTabs.forEach((button) => {
    button.classList.toggle("active", button.dataset.mode === projectForm.elements.projectMode.value);
  });
  document.querySelectorAll(".project-only").forEach((element) => {
    element.hidden = isHtml;
  });
  document.querySelectorAll(".html-only").forEach((element) => {
    element.hidden = !isHtml;
  });
  projectForm.elements.htmlFile.required = isHtml;
  projectForm.elements.frontendPort.required = false;
}

async function submitProject(event) {
  event.preventDefault();
  const submit = projectForm.querySelector('button[type="submit"]');
  submit.disabled = true;
  try {
    const isEditing = Boolean(editingProjectId);
    await api(isEditing ? `/api/projects/${encodeURIComponent(editingProjectId)}` : "/api/projects", {
      method: isEditing ? "PUT" : "POST",
      body: JSON.stringify(formPayload(projectForm))
    });
    closeDialog();
    await loadProjects(true);
    showToast(isEditing ? "项目已更新" : "项目已添加");
  } catch (error) {
    showToast(error.message);
  } finally {
    submit.disabled = false;
  }
}

refreshButton.addEventListener("click", () => loadProjects(false));
addProjectButton.addEventListener("click", openDialog);
modeTabs.forEach((button) => {
  button.addEventListener("click", () => setProjectMode(button.dataset.mode));
});
closeDialogButton.addEventListener("click", closeDialog);
cancelDialogButton.addEventListener("click", closeDialog);
projectDialog.addEventListener("click", (event) => {
  if (event.target === projectDialog) closeDialog();
});
projectForm.addEventListener("submit", submitProject);

loadProjects(true);
setInterval(() => loadProjects(true), 6000);
