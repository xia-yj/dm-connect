import { app, BrowserWindow, dialog, ipcMain, Menu, net, protocol } from "electron";
import { createWriteStream, promises as fs } from "node:fs";
import { tmpdir } from "node:os";
import { join, normalize, resolve } from "node:path";
import { Readable, Transform } from "node:stream";
import { pipeline } from "node:stream/promises";
import { pathToFileURL } from "node:url";
import { JavaBridge, BridgeError } from "./java-bridge";

protocol.registerSchemesAsPrivileged([
  { scheme: "dmconnect", privileges: { standard: true, secure: true, supportFetchAPI: true } }
]);

const allowedMethods = new Set([
  "app.bootstrap", "app.ping", "storage.reset",
  "driver.list", "driver.import", "profile.list", "profile.save", "profile.copy", "profile.delete", "profile.test",
  "connection.connect", "connection.disconnect", "connection.schemas", "objects.list", "object.load", "object.preview", "object.updateCell", "object.updateCells",
  "table.create", "table.alter", "table.longRowStatus", "table.setLongRow",
  "query.open", "query.status", "query.execute", "query.cancel", "query.autoCommit", "query.commit", "query.rollback", "query.close",
  "history.list", "history.delete", "history.clear", "csv.export", "table.exportInsert", "table.exportCsv"
]);

const bridge = new JavaBridge();
let mainWindow: BrowserWindow | null = null;

interface UpdateManifest {
  version: string;
  build?: string;
  url?: string;
  arm64Url?: string;
  x64Url?: string;
  notes?: string;
}

interface AvailableUpdate {
  version: string;
  build?: string;
  url: string;
  notes?: string;
}

function sendUpdateStatus(status: string): void {
  mainWindow?.webContents.send("update:status", status);
}

function compareVersion(left: string, right: string): number {
  const parse = (value: string) => value.split(".").map(part => Number.parseInt(part.replace(/\D.*$/, ""), 10) || 0);
  const a = parse(left);
  const b = parse(right);
  for (let index = 0; index < Math.max(a.length, b.length); index += 1) {
    if ((a[index] ?? 0) !== (b[index] ?? 0)) return (a[index] ?? 0) > (b[index] ?? 0) ? 1 : -1;
  }
  return 0;
}

function updateUrlForCurrentArchitecture(manifest: UpdateManifest): string | undefined {
  if (process.arch === "arm64") return manifest.arm64Url ?? manifest.url;
  if (process.arch === "x64") return manifest.x64Url ?? manifest.url;
  return undefined;
}

function requireHttpUrl(value: string, label: string): URL {
  let url: URL;
  try { url = new URL(value.trim()); } catch { throw new Error(`${label}不正确`); }
  if (url.protocol !== "http:" && url.protocol !== "https:") throw new Error(`${label}仅支持 http 或 https 地址`);
  return url;
}

async function checkForUpdate(manifestUrlText: string): Promise<AvailableUpdate | null> {
  const manifestUrl = requireHttpUrl(manifestUrlText, "更新清单地址");
  const response = await net.fetch(manifestUrl.toString());
  if (!response.ok) throw new Error(`读取更新清单失败（HTTP ${response.status}）`);
  let manifest: UpdateManifest;
  try { manifest = JSON.parse(await response.text()) as UpdateManifest; } catch { throw new Error("更新清单不是有效的 JSON"); }
  if (!manifest.version || typeof manifest.version !== "string") throw new Error("更新清单缺少版本号");
  const url = updateUrlForCurrentArchitecture(manifest);
  if (!url) throw new Error(`更新清单未提供 ${process.arch} 架构的安装包`);
  requireHttpUrl(url, "更新包地址");
  return compareVersion(manifest.version, app.getVersion()) > 0 ? { version: manifest.version, build: manifest.build, url, notes: manifest.notes } : null;
}

function shellQuote(value: string): string {
  return `'${value.replace(/'/g, "'\\\"'\\\"'")}'`;
}

function currentApplicationBundlePath(): string {
  if (!app.isPackaged) throw new Error("当前不是从 .app 应用包运行，无法自动替换更新");
  const bundle = resolve(process.resourcesPath, "..", "..");
  if (!bundle.endsWith(".app")) throw new Error("当前不是从 .app 应用包运行，无法自动替换更新");
  return bundle;
}

async function downloadAndInstallUpdate(manifestUrl: string): Promise<void> {
  const update = await checkForUpdate(manifestUrl);
  if (!update) throw new Error("当前已是最新版本");
  const currentApp = currentApplicationBundlePath();
  sendUpdateStatus("正在下载新版本…");
  const response = await net.fetch(update.url);
  if (!response.ok || !response.body) throw new Error(`下载更新包失败（HTTP ${response.status}）`);
  const archive = join(tmpdir(), `dm-connect-update-${crypto.randomUUID()}.zip`);
  const total = Number(response.headers.get("content-length") ?? "0");
  let received = 0;
  const progress = new Transform({ transform(chunk: Buffer, _encoding, callback) {
    received += chunk.length;
    sendUpdateStatus(total > 0 ? `正在下载新版本… ${Math.min(100, Math.floor(received / total * 100))}%` : "正在下载新版本…");
    callback(null, chunk);
  } });
  try {
    await pipeline(Readable.fromWeb(response.body as never), progress, createWriteStream(archive));
    const parent = resolve(currentApp, "..");
    const scriptPath = join(tmpdir(), `install-dm-connect-${crypto.randomUUID()}.sh`);
    const script = `#!/bin/bash
set -e
ARCHIVE=${shellQuote(archive)}
CURRENT_APP=${shellQuote(currentApp)}
APP_PARENT=${shellQuote(parent)}
APP_NAME=${shellQuote(currentApp.split("/").pop() ?? "DM Connect.app")}
WORK_DIR="$(mktemp -d /tmp/dm-connect-update.XXXXXX)"
trap 'rm -rf "$WORK_DIR"; rm -f "$ARCHIVE"; rm -f "$0"' EXIT
while kill -0 ${process.pid} 2>/dev/null; do sleep 0.2; done
/usr/bin/ditto -x -k "$ARCHIVE" "$WORK_DIR"
NEW_APP="$(/usr/bin/find "$WORK_DIR" -name "*.app" -type d -print -quit)"
if [ -z "$NEW_APP" ]; then exit 12; fi
rm -rf "$CURRENT_APP"
/usr/bin/ditto "$NEW_APP" "$APP_PARENT/$APP_NAME"
/usr/bin/open "$APP_PARENT/$APP_NAME"
`;
    await fs.writeFile(scriptPath, script, { mode: 0o755 });
    const { spawn } = await import("node:child_process");
    spawn("/bin/bash", [scriptPath], { detached: true, stdio: "ignore" }).unref();
    sendUpdateStatus("更新包已准备好，正在重启…");
    setTimeout(() => app.quit(), 200);
  } catch (cause) {
    await fs.rm(archive, { force: true });
    throw cause;
  }
}

// Keep isolated preview/test runs from sharing Chromium state or the
// single-instance lock with the installed application.
if (process.env.DMCONNECT_DATA_DIR) {
  app.setPath("userData", join(process.env.DMCONNECT_DATA_DIR, "electron"));
}

function trustedUrl(raw: string): boolean {
  if (raw.startsWith("dmconnect://app/")) return true;
  const dev = process.env.VITE_DEV_SERVER_URL;
  return Boolean(dev && raw.startsWith(dev));
}

function registerIpc(): void {
  ipcMain.handle("backend:request", async (event, method: string, params: unknown) => {
    if (!trustedUrl(event.senderFrame?.url ?? "")) return { ok: false, error: { code: "UNTRUSTED_RENDERER", message: "拒绝未知页面请求" } };
    if (!allowedMethods.has(method)) return { ok: false, error: { code: "METHOD_NOT_ALLOWED", message: `不允许调用：${method}` } };
    try {
      return { ok: true, value: await bridge.request(method, params) };
    } catch (cause) {
      const error = cause as BridgeError;
      return { ok: false, error: { code: error.code ?? "BACKEND_ERROR", message: error.message, data: error.data } };
    }
  });
  ipcMain.handle("dialog:select-driver", async event => {
    if (!trustedUrl(event.senderFrame?.url ?? "")) return null;
    const selected = await dialog.showOpenDialog(mainWindow!, {
      title: "导入 JDBC 驱动",
      properties: ["openFile"],
      filters: [{ name: "JDBC JAR", extensions: ["jar"] }]
    });
    return selected.canceled ? null : selected.filePaths[0] ?? null;
  });
  ipcMain.handle("dialog:save-csv", async (event, defaultName: string) => {
    if (!trustedUrl(event.senderFrame?.url ?? "")) return null;
    const selected = await dialog.showSaveDialog(mainWindow!, {
      title: "导出查询结果",
      defaultPath: defaultName || "query-result.csv",
      filters: [{ name: "CSV 文件", extensions: ["csv"] }]
    });
    return selected.canceled ? null : selected.filePath ?? null;
  });
  ipcMain.handle("dialog:save-sql", async event => {
    if (!trustedUrl(event.senderFrame?.url ?? "")) return null;
    const selected = await dialog.showSaveDialog(mainWindow!, { title: "导出 INSERT 语句", defaultPath: "table-inserts.sql", filters: [{ name: "SQL 文件", extensions: ["sql"] }] });
    return selected.canceled ? null : selected.filePath ?? null;
  });
  ipcMain.handle("dialog:save-local-sql", async (event, defaultName: unknown, content: unknown) => {
    if (!trustedUrl(event.senderFrame?.url ?? "")) return null;
    if (typeof content !== "string") throw new Error("SQL 内容不正确");
    if (content.length > 20 * 1024 * 1024) throw new Error("SQL 文件不能超过 20 MB");
    const selected = await dialog.showOpenDialog(mainWindow!, {
      title: "选择 SQL 保存文件夹",
      properties: ["openDirectory", "createDirectory"]
    });
    if (selected.canceled || !selected.filePaths[0]) return null;
    const rawName = typeof defaultName === "string" ? defaultName : "query.sql";
    const cleaned = rawName.trim().replace(/[\\/:*?"<>|]/g, "_").replace(/\s+/g, " ").slice(0, 120) || "query";
    const stem = cleaned.toLowerCase().endsWith(".sql") ? cleaned.slice(0, -4) : cleaned;
    for (let index = 1; index < 10000; index += 1) {
      const suffix = index === 1 ? "" : `-${index}`;
      const filePath = join(selected.filePaths[0], `${stem}${suffix}.sql`);
      try {
        await fs.writeFile(filePath, content, { encoding: "utf8", flag: "wx" });
        return filePath;
      } catch (cause) {
        if ((cause as NodeJS.ErrnoException).code !== "EEXIST") throw cause;
      }
    }
    throw new Error("无法生成不重复的 SQL 文件名");
  });
  ipcMain.handle("update:check", async (event, manifestUrl: string) => {
    if (!trustedUrl(event.senderFrame?.url ?? "")) throw new Error("拒绝未知页面请求");
    return checkForUpdate(manifestUrl);
  });
  ipcMain.handle("update:install", async (event, manifestUrl: string) => {
    if (!trustedUrl(event.senderFrame?.url ?? "")) throw new Error("拒绝未知页面请求");
    await downloadAndInstallUpdate(manifestUrl);
  });
}

async function registerAppProtocol(): Promise<void> {
  const rendererRoot = join(app.getAppPath(), "dist");
  protocol.handle("dmconnect", request => {
    const url = new URL(request.url);
    const requested = decodeURIComponent(url.pathname === "/" ? "/index.html" : url.pathname);
    const file = normalize(join(rendererRoot, requested));
    if (!file.startsWith(rendererRoot)) return new Response("Forbidden", { status: 403 });
    return net.fetch(pathToFileURL(file).toString());
  });
}

async function createWindow(): Promise<void> {
  mainWindow = new BrowserWindow({
    width: 1540,
    height: 960,
    minWidth: 1180,
    minHeight: 760,
    show: false,
    backgroundColor: "#0b1020",
    title: "DM Connect",
    titleBarStyle: "hiddenInset",
    trafficLightPosition: { x: 18, y: 18 },
    webPreferences: {
      preload: join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
      webviewTag: false
    }
  });
  mainWindow.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
  mainWindow.webContents.on("will-navigate", (event, url) => {
    if (!trustedUrl(url)) event.preventDefault();
  });
  mainWindow.once("ready-to-show", () => mainWindow?.show());
  mainWindow.on("closed", () => { mainWindow = null; });
  if (process.env.VITE_DEV_SERVER_URL) await mainWindow.loadURL(process.env.VITE_DEV_SERVER_URL);
  else await mainWindow.loadURL("dmconnect://app/index.html");
}

function installMenu(): void {
  const template: Electron.MenuItemConstructorOptions[] = [
    {
      label: "DM Connect",
      submenu: [
        { role: "about", label: "关于 DM Connect" },
        { label: "检查更新…", click: () => mainWindow?.webContents.send("update:check") },
        { type: "separator" },
        { role: "hide", label: "隐藏 DM Connect" },
        { role: "hideOthers", label: "隐藏其他" },
        { role: "unhide", label: "全部显示" },
        { type: "separator" },
        { role: "quit", label: "退出 DM Connect" }
      ]
    },
    { label: "编辑", submenu: [{ role: "undo", label: "撤销" }, { role: "redo", label: "重做" }, { type: "separator" }, { role: "cut", label: "剪切" }, { role: "copy", label: "复制" }, { role: "paste", label: "粘贴" }, { role: "selectAll", label: "全选" }] },
    { label: "窗口", submenu: [{ role: "minimize", label: "最小化" }, { role: "zoom", label: "缩放" }, { role: "front", label: "前置全部窗口" }] }
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

const hasLock = app.requestSingleInstanceLock();
if (!hasLock) app.quit();
else {
  app.on("second-instance", () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });
  app.whenReady().then(async () => {
    try {
      bridge.start();
      registerIpc();
      installMenu();
      if (!process.env.VITE_DEV_SERVER_URL) await registerAppProtocol();
      await createWindow();
    } catch (cause) {
      dialog.showErrorBox("DM Connect 启动失败", cause instanceof Error ? cause.message : String(cause));
      app.quit();
    }
  });
}

app.on("activate", () => {
  if (BrowserWindow.getAllWindows().length === 0) void createWindow();
});
app.on("window-all-closed", () => app.quit());
app.on("before-quit", () => bridge.close());
