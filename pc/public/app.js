const state = { me: null, peers: [], active: null, path: "", search: "" };

const $ = (id) => document.getElementById(id);
const peerList = $("peerList");
const fileGrid = $("fileGrid");
const breadcrumbs = $("breadcrumbs");
const toast = $("toast");
const viewer = $("viewer");
const viewerBody = $("viewerBody");
const viewerTitle = $("viewerTitle");
const viewerDownload = $("viewerDownload");
const searchInput = $("searchInput");

function showToast(message) {
  toast.textContent = message;
  toast.classList.add("show");
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => toast.classList.remove("show"), 2600);
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (char) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;"
  }[char]));
}

function fmtSize(size) {
  if (!size) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const index = Math.min(Math.floor(Math.log(size) / Math.log(1024)), units.length - 1);
  return `${(size / 1024 ** index).toFixed(index === 0 ? 0 : 1)} ${units[index]}`;
}

function fileKind(entry) {
  if (entry.type === "folder") return "folder";
  if (entry.media === "image") return "image";
  if (entry.media === "video") return "video";
  if (["doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf", "txt", "md"].includes(entry.ext)) return "doc";
  return "file";
}

function iconText(entry) {
  if (entry.type === "folder") return "DIR";
  if (entry.media === "video") return "PLAY";
  if (entry.ext) return entry.ext.slice(0, 4).toUpperCase();
  return "FILE";
}

function remoteUrl(api, entry) {
  return `${state.active.url}${api}?path=${encodeURIComponent(entry.path)}`;
}

async function getJson(url, options) {
  const response = await fetch(url, options);
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || response.statusText);
  return data;
}

async function loadMe() {
  state.me = await getJson("/api/me");
  $("deviceName").textContent = state.me.name;
  $("deviceUrl").textContent = state.me.url;
  $("localStatus").textContent = "Online";
}

async function loadPeers() {
  const data = await getJson("/api/peers");
  state.peers = data.peers;
  $("peerCount").textContent = String(state.peers.length);
  renderPeers();
}

function renderPeers() {
  if (!state.peers.length) {
    peerList.innerHTML = `<div class="empty-state"><p>No devices found</p></div>`;
    return;
  }
  peerList.innerHTML = "";
  for (const peer of state.peers) {
    const item = document.createElement("button");
    item.className = `peer ${state.active?.id === peer.id ? "active" : ""}`;
    item.innerHTML = `
      <div class="machine-icon ${peer.type === "android" ? "phone" : "pc"}">${peer.type === "android" ? "APP" : "PC"}</div>
      <div>
        <strong>${escapeHtml(peer.name)}</strong>
        <small>${escapeHtml(peer.host)}:${peer.port}</small>
      </div>
    `;
    item.addEventListener("click", () => {
      state.active = peer;
      state.path = "";
      state.search = "";
      searchInput.value = "";
      renderPeers();
      loadFiles();
    });
    peerList.appendChild(item);
  }
}

function renderBreadcrumbs() {
  breadcrumbs.innerHTML = "";
  if (!state.active) return;
  const root = document.createElement("button");
  root.className = "crumb";
  root.textContent = state.search ? `Search: ${state.search}` : state.active.name;
  root.addEventListener("click", () => {
    state.search = "";
    searchInput.value = "";
    state.path = "";
    loadFiles();
  });
  breadcrumbs.appendChild(root);
  if (state.search || !state.path) return;

  const normalized = state.path.replace(/\\/g, "/");
  const parts = normalized.split("/").filter(Boolean);
  let current = normalized.startsWith("/") ? "/" : "";
  for (const part of parts) {
    current = current === "/" ? `/${part}` : (current ? `${current}/${part}` : part);
    const btn = document.createElement("button");
    btn.className = "crumb";
    btn.textContent = part;
    const target = current;
    btn.addEventListener("click", () => {
      state.path = target;
      loadFiles();
    });
    breadcrumbs.appendChild(btn);
  }
}

function renderEmpty(title, message) {
  fileGrid.className = "file-grid empty";
  fileGrid.innerHTML = `
    <div class="empty-state">
      <div class="empty-icon">~</div>
      <h3>${escapeHtml(title)}</h3>
      <p>${escapeHtml(message)}</p>
    </div>
  `;
}

function previewMarkup(entry) {
  if (entry.media === "image") return `<img class="thumb-media" src="${remoteUrl("/api/preview", entry)}" alt="">`;
  if (entry.media === "video") return `<video class="thumb-media" src="${remoteUrl("/api/preview", entry)}" preload="metadata" muted playsinline></video><span class="play-badge">▶</span>`;
  return `<div class="file-icon">${iconText(entry)}</div>`;
}

function renderFiles(entries) {
  fileGrid.className = entries.length ? "file-grid" : "file-grid empty";
  if (!entries.length) {
    renderEmpty(state.search ? "No matches" : "This location is empty", state.search ? "Try another keyword." : "Try another folder.");
    return;
  }
  fileGrid.innerHTML = "";
  for (const entry of entries) {
    const card = document.createElement("article");
    card.className = `file-card ${fileKind(entry)}`;
    card.innerHTML = `
      <div class="thumb ${entry.media ? "has-media" : ""}">${previewMarkup(entry)}</div>
      <div>
        <div class="file-name">${escapeHtml(entry.name)}</div>
        <div class="file-meta">
          <span>${entry.type === "folder" ? "Folder" : fmtSize(entry.size)}</span>
          <button class="mini-action">${entry.type === "folder" ? "Open" : (entry.media ? "Preview" : "Download")}</button>
        </div>
      </div>
    `;
    card.addEventListener("click", () => openEntry(entry));
    card.querySelector(".mini-action").addEventListener("click", (event) => {
      event.stopPropagation();
      if (entry.type === "file" && !entry.media) downloadEntry(entry);
      else openEntry(entry);
    });
    fileGrid.appendChild(card);
  }
}

function openEntry(entry) {
  if (entry.type === "folder") {
    state.path = entry.path;
    state.search = "";
    searchInput.value = "";
    loadFiles();
    return;
  }
  if (entry.media) openViewer(entry);
  else downloadEntry(entry);
}

function downloadEntry(entry) {
  window.open(remoteUrl("/api/download", entry), "_blank");
}

function openViewer(entry) {
  const src = remoteUrl("/api/preview", entry);
  viewerTitle.textContent = entry.name;
  viewerDownload.href = remoteUrl("/api/download", entry);
  viewerBody.innerHTML = entry.media === "image"
    ? `<img class="viewer-media" src="${src}" alt="">`
    : `<video class="viewer-media" src="${src}" controls autoplay playsinline></video>`;
  viewer.classList.add("show");
  viewer.setAttribute("aria-hidden", "false");
}

function closeViewer() {
  viewer.classList.remove("show");
  viewer.setAttribute("aria-hidden", "true");
  viewerBody.innerHTML = "";
}

async function loadFiles() {
  if (!state.active) {
    $("activeTitle").textContent = "Choose a device";
    renderEmpty("Waiting for devices", "Keep the PC and Android device on the same local network.");
    renderBreadcrumbs();
    return;
  }
  $("activeTitle").textContent = state.search ? `Search results` : (state.path ? state.path.split(/[\\/]/).filter(Boolean).at(-1) || state.path : state.active.name);
  renderBreadcrumbs();
  try {
    const url = state.search
      ? `${state.active.url}/api/search?q=${encodeURIComponent(state.search)}&path=${encodeURIComponent(state.path)}`
      : `${state.active.url}/api/files?path=${encodeURIComponent(state.path)}`;
    const data = await getJson(url);
    renderFiles(data.entries);
    if (data.truncated) showToast("Search stopped after the result limit.");
  } catch (error) {
    renderEmpty("Unable to read files", error.message);
  }
}

function runSearch() {
  if (!state.active) {
    showToast("Choose a device first.");
    return;
  }
  const query = searchInput.value.trim();
  if (!query) {
    clearSearch();
    return;
  }
  state.search = query;
  loadFiles();
}

function clearSearch() {
  state.search = "";
  searchInput.value = "";
  loadFiles();
}

$("searchButton").addEventListener("click", runSearch);
$("clearSearchButton").addEventListener("click", clearSearch);
searchInput.addEventListener("keydown", (event) => {
  if (event.key === "Enter") runSearch();
});
$("refreshButton").addEventListener("click", () => {
  loadPeers().then(loadFiles).catch((error) => showToast(error.message));
});
$("upButton").addEventListener("click", () => {
  if (state.search) {
    clearSearch();
    return;
  }
  if (!state.path) return;
  const normalized = state.path.replace(/\\/g, "/").replace(/\/+$/, "");
  if (/^[A-Za-z]:$/.test(normalized) || normalized === "/") state.path = "";
  else {
    const index = normalized.lastIndexOf("/");
    state.path = index <= 0 ? "" : normalized.slice(0, index);
  }
  loadFiles();
});
$("viewerClose").addEventListener("click", closeViewer);
viewer.addEventListener("click", (event) => {
  if (event.target === viewer) closeViewer();
});
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") closeViewer();
});

async function boot() {
  try {
    await loadMe();
    await loadPeers();
    await loadFiles();
    setInterval(loadPeers, 2500);
  } catch (error) {
    showToast(error.message);
    $("localStatus").textContent = "Error";
  }
}

boot();
