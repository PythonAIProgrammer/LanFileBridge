const http = require("http");
const dgram = require("dgram");
const fs = require("fs");
const os = require("os");
const path = require("path");
const crypto = require("crypto");
const { URL } = require("url");

const HTTP_PORT = Number(process.env.LAN_BRIDGE_PORT || 4317);
const DISCOVERY_PORT = Number(process.env.LAN_BRIDGE_DISCOVERY_PORT || 4318);
const DEVICE_ID = process.env.LAN_BRIDGE_ID || crypto.randomUUID();
const DEVICE_NAME = process.env.LAN_BRIDGE_NAME || `${os.hostname()} PC`;
const peers = new Map();
const searchTasks = new Map();

function now() {
  return Date.now();
}

function localAddresses() {
  const result = [];
  for (const list of Object.values(os.networkInterfaces())) {
    for (const item of list || []) {
      if (item.family === "IPv4" && !item.internal) {
        result.push(item.address);
      }
    }
  }
  return result;
}

function broadcastAddresses() {
  const addresses = new Set(["255.255.255.255"]);
  for (const list of Object.values(os.networkInterfaces())) {
    for (const item of list || []) {
      if (item.family !== "IPv4" || item.internal || !item.netmask) continue;
      const ip = item.address.split(".").map(Number);
      const mask = item.netmask.split(".").map(Number);
      if (ip.length !== 4 || mask.length !== 4 || ip.some(Number.isNaN) || mask.some(Number.isNaN)) continue;
      const broadcast = ip.map((part, index) => (part | (~mask[index] & 255))).join(".");
      addresses.add(broadcast);
    }
  }
  return [...addresses];
}

function publicUrl() {
  const address = localAddresses()[0] || "127.0.0.1";
  return `http://${address}:${HTTP_PORT}`;
}

function json(res, status, value) {
  const body = JSON.stringify(value);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET,POST,OPTIONS",
    "access-control-allow-headers": "content-type"
  });
  res.end(body);
}

function text(res, status, value) {
  res.writeHead(status, { "content-type": "text/plain; charset=utf-8" });
  res.end(value);
}

function toApiPath(fullPath) {
  return path.resolve(fullPath).replace(/\\/g, "/");
}

function safePath(apiPath) {
  const clean = String(apiPath || "");
  if (!clean) throw new Error("No file path provided.");
  const normalized = path.resolve(clean);
  if (!path.isAbsolute(normalized)) throw new Error("Invalid file path.");
  return normalized;
}

function rootEntries() {
  if (process.platform === "win32") {
    const entries = [];
    for (let code = 65; code <= 90; code++) {
      const drive = `${String.fromCharCode(code)}:\\`;
      try {
        fs.accessSync(drive, fs.constants.R_OK);
        entries.push({
          name: drive,
          path: toApiPath(drive),
          type: "folder",
          size: 0,
          modified: 0,
          ext: ""
        });
      } catch {
        // Skip drives that are not mounted or not readable.
      }
    }
    return entries;
  }
  return [{
    name: "/",
    path: "/",
    type: "folder",
    size: 0,
    modified: 0,
    ext: ""
  }];
}

function statToEntry(fullPath, relativePath) {
  const stat = fs.statSync(fullPath);
  const ext = stat.isDirectory() ? "" : path.extname(fullPath).slice(1).toLowerCase();
  return {
    name: path.basename(fullPath),
    path: toApiPath(relativePath),
    type: stat.isDirectory() ? "folder" : "file",
    size: stat.size,
    modified: stat.mtimeMs,
    ext,
    media: stat.isDirectory() ? "" : mediaTypeForExt(ext)
  };
}

function listFiles(dirPath) {
  if (!dirPath) {
    return { root: "filesystem", path: "", entries: rootEntries() };
  }
  const full = safePath(dirPath);
  const stat = fs.statSync(full);
  if (!stat.isDirectory()) {
    throw new Error("Not a folder.");
  }
  const entries = fs.readdirSync(full)
    .map((name) => {
      try {
        const child = path.join(full, name);
        return statToEntry(child, child);
      } catch {
        return null;
      }
    })
    .filter(Boolean)
    .sort((a, b) => {
      if (a.type !== b.type) return a.type === "folder" ? -1 : 1;
      return a.name.localeCompare(b.name, "zh-Hans-CN", { sensitivity: "base" });
    });
  return { root: "filesystem", path: toApiPath(full), entries };
}

function searchFiles(query, startPath) {
  const needle = String(query || "").trim().toLowerCase();
  if (!needle) return { query, entries: [] };

  const roots = startPath ? [safePath(startPath)] : rootEntries().map((entry) => entry.path);
  const entries = [];
  const stack = [...roots];
  let visited = 0;
  const maxResults = 250;
  const maxVisited = 50000;

  while (stack.length && entries.length < maxResults && visited < maxVisited) {
    const current = stack.pop();
    visited += 1;
    let stat;
    try {
      stat = fs.lstatSync(current);
      if (stat.isSymbolicLink()) continue;
    } catch {
      continue;
    }

    const name = path.basename(current) || current;
    if (name.toLowerCase().includes(needle)) {
      try {
        entries.push(statToEntry(current, current));
      } catch {
        // Skip unreadable matches.
      }
    }

    if (!stat.isDirectory()) continue;
    let children;
    try {
      children = fs.readdirSync(current);
    } catch {
      continue;
    }
    for (let i = children.length - 1; i >= 0; i -= 1) {
      stack.push(path.join(current, children[i]));
    }
  }

  return { query, path: startPath || "", truncated: stack.length > 0, entries };
}

function createSearchTask(query, startPath) {
  const needle = String(query || "").trim().toLowerCase();
  if (!needle) throw new Error("Search query is required.");
  const id = crypto.randomUUID();
  const task = {
    id,
    query: String(query || "").trim(),
    path: startPath || "",
    needle,
    stack: startPath ? [safePath(startPath)] : rootEntries().map((entry) => entry.path),
    entries: [],
    visited: 0,
    done: false,
    truncated: false,
    error: "",
    startedAt: now(),
    updatedAt: now(),
    maxResults: 250,
    maxVisited: 50000
  };
  searchTasks.set(id, task);
  setImmediate(() => runSearchTask(id));
  return publicSearchTask(task);
}

function runSearchTask(id) {
  const task = searchTasks.get(id);
  if (!task || task.done) return;
  const batchSize = 300;
  let processed = 0;
  try {
    while (task.stack.length && task.entries.length < task.maxResults && task.visited < task.maxVisited && processed < batchSize) {
      const current = task.stack.pop();
      processed += 1;
      task.visited += 1;
      let stat;
      try {
        stat = fs.lstatSync(current);
        if (stat.isSymbolicLink()) continue;
      } catch {
        continue;
      }

      const name = path.basename(current) || current;
      if (name.toLowerCase().includes(task.needle)) {
        try {
          task.entries.push(statToEntry(current, current));
        } catch {
          // Skip unreadable matches.
        }
      }

      if (!stat.isDirectory()) continue;
      let children;
      try {
        children = fs.readdirSync(current);
      } catch {
        continue;
      }
      for (let i = children.length - 1; i >= 0; i -= 1) {
        task.stack.push(path.join(current, children[i]));
      }
    }

    task.updatedAt = now();
    if (!task.stack.length || task.entries.length >= task.maxResults || task.visited >= task.maxVisited) {
      task.done = true;
      task.truncated = task.stack.length > 0;
    } else {
      setTimeout(() => runSearchTask(id), 20);
    }
  } catch (error) {
    task.done = true;
    task.error = error.message;
    task.updatedAt = now();
  }
}

function publicSearchTask(task) {
  return {
    id: task.id,
    query: task.query,
    path: task.path,
    visited: task.visited,
    resultCount: task.entries.length,
    done: task.done,
    truncated: task.truncated,
    error: task.error,
    entries: task.entries
  };
}

function cleanupSearchTasks() {
  const cutoff = now() - 10 * 60 * 1000;
  for (const [id, task] of searchTasks) {
    if (task.updatedAt < cutoff) searchTasks.delete(id);
  }
}

function mimeType(filePath) {
  const ext = path.extname(filePath).toLowerCase();
  return {
    ".html": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".png": "image/png",
    ".gif": "image/gif",
    ".webp": "image/webp",
    ".pdf": "application/pdf",
    ".txt": "text/plain; charset=utf-8",
    ".md": "text/markdown; charset=utf-8",
    ".doc": "application/msword",
    ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ".xls": "application/vnd.ms-excel",
    ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    ".ppt": "application/vnd.ms-powerpoint",
    ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    ".mp4": "video/mp4",
    ".mov": "video/quicktime",
    ".m4v": "video/x-m4v",
    ".webm": "video/webm",
    ".mkv": "video/x-matroska",
    ".avi": "video/x-msvideo",
    ".mp3": "audio/mpeg"
  }[ext] || "application/octet-stream";
}

function mediaTypeForExt(ext) {
  if (["jpg", "jpeg", "png", "gif", "webp"].includes(ext)) return "image";
  if (["mp4", "mov", "m4v", "webm", "mkv", "avi", "3gp"].includes(ext)) return "video";
  return "";
}

function streamFile(req, res, filePath, disposition) {
  const stat = fs.statSync(filePath);
  if (!stat.isFile()) throw new Error("Only files can be opened.");
  const fileName = encodeURIComponent(path.basename(filePath));
  const type = mimeType(filePath);
  const range = req.headers.range;
  if (range) {
    const match = /bytes=(\d*)-(\d*)/.exec(range);
    if (match) {
      const start = match[1] ? Number(match[1]) : 0;
      const end = match[2] ? Number(match[2]) : stat.size - 1;
      if (start >= stat.size || end >= stat.size || start > end) {
        res.writeHead(416, { "content-range": `bytes */${stat.size}` });
        res.end();
        return;
      }
      res.writeHead(206, {
        "content-type": type,
        "content-length": end - start + 1,
        "content-range": `bytes ${start}-${end}/${stat.size}`,
        "accept-ranges": "bytes",
        "content-disposition": `${disposition}; filename*=UTF-8''${fileName}`,
        "access-control-allow-origin": "*"
      });
      fs.createReadStream(filePath, { start, end }).pipe(res);
      return;
    }
  }
  res.writeHead(200, {
    "content-type": type,
    "content-length": stat.size,
    "accept-ranges": "bytes",
    "content-disposition": `${disposition}; filename*=UTF-8''${fileName}`,
    "access-control-allow-origin": "*"
  });
  fs.createReadStream(filePath).pipe(res);
}

function peerList() {
  const cutoff = now() - 15000;
  for (const [id, peer] of peers) {
    if (peer.seenAt < cutoff) peers.delete(id);
  }
  return [...peers.values()].sort((a, b) => b.seenAt - a.seenAt);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = "";
    req.setEncoding("utf8");
    req.on("data", (chunk) => {
      body += chunk;
      if (body.length > 1024 * 1024) {
        reject(new Error("Request body too large."));
        req.destroy();
      }
    });
    req.on("end", () => resolve(body));
    req.on("error", reject);
  });
}

async function handleApi(req, res, url) {
  if (req.method === "OPTIONS") {
    res.writeHead(204, {
      "access-control-allow-origin": "*",
      "access-control-allow-methods": "GET,POST,OPTIONS",
      "access-control-allow-headers": "content-type"
    });
    res.end();
    return;
  }

  if (url.pathname === "/api/me") {
    json(res, 200, {
      id: DEVICE_ID,
      name: DEVICE_NAME,
      type: "pc",
      port: HTTP_PORT,
      url: publicUrl(),
      shareRoot: "All accessible files",
      addresses: localAddresses()
    });
    return;
  }

  if (url.pathname === "/api/peers") {
    json(res, 200, { peers: peerList() });
    return;
  }

  if (url.pathname === "/api/files") {
    try {
      json(res, 200, listFiles(url.searchParams.get("path") || ""));
    } catch (error) {
      json(res, 400, { error: error.message });
    }
    return;
  }

  if (url.pathname === "/api/search") {
    try {
      json(res, 200, searchFiles(url.searchParams.get("q") || "", url.searchParams.get("path") || ""));
    } catch (error) {
      json(res, 400, { error: error.message });
    }
    return;
  }

  if (url.pathname === "/api/search/start") {
    try {
      cleanupSearchTasks();
      json(res, 200, createSearchTask(url.searchParams.get("q") || "", url.searchParams.get("path") || ""));
    } catch (error) {
      json(res, 400, { error: error.message });
    }
    return;
  }

  if (url.pathname === "/api/search/status") {
    const id = url.searchParams.get("id") || "";
    const task = searchTasks.get(id);
    if (!task) {
      json(res, 404, { error: "Search task not found." });
    } else {
      json(res, 200, publicSearchTask(task));
    }
    return;
  }

  if (url.pathname === "/api/download") {
    try {
      const filePath = safePath(url.searchParams.get("path") || "");
      streamFile(req, res, filePath, "attachment");
    } catch (error) {
      json(res, 404, { error: error.message });
    }
    return;
  }

  if (url.pathname === "/api/preview") {
    try {
      const filePath = safePath(url.searchParams.get("path") || "");
      streamFile(req, res, filePath, "inline");
    } catch (error) {
      json(res, 404, { error: error.message });
    }
    return;
  }

  if (url.pathname === "/api/ping") {
    json(res, 200, { ok: true, time: now() });
    return;
  }

  text(res, 404, "Not found");
}

function serveStatic(req, res, url) {
  const publicDir = path.join(__dirname, "public");
  const requested = url.pathname === "/" ? "index.html" : decodeURIComponent(url.pathname.slice(1));
  const full = path.resolve(publicDir, requested);
  if (full !== publicDir && !full.startsWith(publicDir + path.sep)) {
    text(res, 403, "Forbidden");
    return;
  }
  if (!fs.existsSync(full) || !fs.statSync(full).isFile()) {
    text(res, 404, "Not found");
    return;
  }
  res.writeHead(200, { "content-type": mimeType(full) });
  fs.createReadStream(full).pipe(res);
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  if (url.pathname.startsWith("/api/")) {
    handleApi(req, res, url).catch((error) => json(res, 500, { error: error.message }));
  } else {
    serveStatic(req, res, url);
  }
});

const udp = dgram.createSocket({ type: "udp4", reuseAddr: true });

function announcement(kind = "announce") {
  return Buffer.from(JSON.stringify({
    app: "LanFileBridge",
    version: 1,
    kind,
    id: DEVICE_ID,
    name: DEVICE_NAME,
    type: "pc",
    port: HTTP_PORT,
    url: publicUrl(),
    time: now()
  }));
}

function broadcast() {
  const packet = announcement("announce");
  udp.setBroadcast(true);
  for (const address of broadcastAddresses()) {
    udp.send(packet, 0, packet.length, DISCOVERY_PORT, address);
  }
}

function replyTo(address) {
  const packet = announcement("reply");
  udp.send(packet, 0, packet.length, DISCOVERY_PORT, address);
}

udp.on("message", (message, rinfo) => {
  try {
    const data = JSON.parse(message.toString("utf8"));
    if (data.app !== "LanFileBridge" || data.id === DEVICE_ID) return;
    const port = Number(data.port || HTTP_PORT);
    const url = `http://${rinfo.address}:${port}`;
    peers.set(data.id, {
      id: data.id,
      name: data.name || "Unknown device",
      type: data.type || "device",
      host: rinfo.address,
      port,
      url,
      seenAt: now()
    });
    if (data.kind !== "reply") {
      replyTo(rinfo.address);
    }
  } catch {
    // Ignore unrelated local network UDP traffic.
  }
});

udp.bind(DISCOVERY_PORT, () => {
  udp.setBroadcast(true);
  broadcast();
  setInterval(broadcast, 3000);
});

server.listen(HTTP_PORT, () => {
  console.log(`LanFileBridge PC running at http://localhost:${HTTP_PORT}`);
  console.log("Shared scope: all accessible files");
  console.log("Open the URL above to manage nearby devices and files.");
});
