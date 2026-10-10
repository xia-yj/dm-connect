import { createHash } from "node:crypto";
import { app, BrowserWindow, dialog, ipcMain, Menu, net, protocol } from "electron";
import { createWriteStream, promises as fs } from "node:fs";
import { tmpdir } from "node:os";
import { join, normalize, resolve } from "node:path";
import { Readable, Transform } from "node:stream";
import { pipeline } from "node:stream/promises";
import { pathToFileURL } from "node:url";
import { fetchWithNodeFallback, isHttp2ProtocolError } from "./github-fetch";
import { JavaBridge, BridgeError } from "./java-bridge";
import { parseUpdateManifest, requireAllowedHttpsUrl, timingSafeEqualHex, type AvailableUpdate } from "./update-security";

protocol.registerSchemesAsPrivileged([
  { scheme: "dmconnect", privileges: { standard: true, secure: true, supportFetchAPI: true } }
]);

// Keep the runtime application name in sync with the packaged macOS bundle
// metadata, including when running an unpackaged development build.
app.setName("数据库连接工具");

const allowedMethods = new Set([
  "app.bootstrap", "app.ping", "storage.reset",
  "driver.list", "driver.import", "profile.list", "profile.save", "profile.copy", "profile.delete", "profile.test",
  "connection.connect", "connection.disconnect", "connection.schemas", "objects.list", "object.load", "object.preview", "object.updateCell", "object.updateCells", "object.deleteRow",
  "table.open", "table.status", "table.preview", "table.saveChanges", "table.autoCommit", "table.commit", "table.rollback", "table.close",
  "mongo.collections", "mongo.documents", "mongo.insert", "mongo.replace", "mongo.delete", "redis.keys", "redis.key", "redis.command",
  "table.create", "table.alter", "table.longRowStatus", "table.setLongRow",
  "query.open", "query.status", "query.execute", "query.cancel", "query.autoCommit", "query.commit", "query.rollback", "query.close",
  "history.list", "history.delete", "history.clear", "csv.export", "table.exportInsert", "table.exportCsv"
]);

const bridge = new JavaBridge();
let mainWindow: BrowserWindow | null = null;
let isQuitting = false;

function sendUpdateStatus(status: string): void {
  mainWindow?.webContents.send("update:status", status);
}

async function githubFetch(url: string, onFallback?: () => void): Promise<Response> {
  return fetchWithNodeFallback(url, input => net.fetch(input), input => globalThis.fetch(input), onFallback);
}

async function checkForUpdate(manifestUrlText: string): Promise<AvailableUpdate | null> {
  const manifestUrl = requireAllowedHttpsUrl(manifestUrlText, "更新清单地址");
  const response = await githubFetch(manifestUrl.toString());
  if (!response.ok) throw new Error(`读取更新清单失败（HTTP ${response.status}）`);
  return parseUpdateManifest(await response.text(), app.getVersion(), process.arch);
}

async function writeUpdateArchive(response: Response, archive: string, expectedSha256: string): Promise<void> {
  if (!response.ok || !response.body) throw new Error(`下载更新包失败（HTTP ${response.status}）`);
  const total = Number(response.headers.get("content-length") ?? "0");
  let received = 0;
  const digest = createHash("sha256");
  const progress = new Transform({ transform(chunk: Buffer, _encoding, callback) {
    received += chunk.length;
    digest.update(chunk);
    sendUpdateStatus(total > 0 ? `正在下载新版本… ${Math.min(100, Math.floor(received / total * 100))}%` : "正在下载新版本…");
    callback(null, chunk);
  } });
  await pipeline(Readable.fromWeb(response.body as never), progress, createWriteStream(archive));
  const actual = digest.digest("hex");
  if (!timingSafeEqualHex(actual, expectedSha256)) {
    await fs.rm(archive, { force: true });
    throw new Error("更新包校验失败，已取消安装");
  }
}

async function downloadUpdateArchive(url: string, archive: string, expectedSha256: string): Promise<void> {
  const switchToCompatibleDownload = () => sendUpdateStatus("下载连接异常，正在切换兼容下载方式…");
  try {
    await writeUpdateArchive(await githubFetch(url, switchToCompatibleDownload), archive, expectedSha256);
  } catch (cause) {
    if (!isHttp2ProtocolError(cause)) throw cause;
    switchToCompatibleDownload();
    await fs.rm(archive, { force: true });
    await writeUpdateArchive(await globalThis.fetch(url), archive, expectedSha256);
  }
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
  const archive = join(tmpdir(), `dm-connect-update-${crypto.randomUUID()}.zip`);
  try {
    await downloadUpdateArchive(update.url, archive, update.sha256);
    const parent = resolve(currentApp, "..");
    const scriptPath = join(tmpdir(), `install-dm-connect-${crypto.randomUUID()}.sh`);
    const script = `#!/bin/bash
set -e
ARCHIVE=${shellQuote(archive)}
CURRENT_APP=${shellQuote(currentApp)}
APP_PARENT=${shellQuote(parent)}
APP_NAME=${shellQuote(currentApp.split("/").pop() ?? "数据库连接工具.app")}
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
    setTimeout(() => {
      // macOS 的窗口 close handler 默认会拦截退出并隐藏窗口；更新脚本
      // 必须等 Electron 进程真正退出后才能替换 .app。
      isQuitting = true;
      app.quit();
    }, 200);
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
  ipcMain.handle("dialog:select-database-file", async event => {
    if (!trustedUrl(event.senderFrame?.url ?? "")) return null;
    const selected = await dialog.showOpenDialog(mainWindow!, {
      title: "选择 SQLite 数据库文件",
      properties: ["openFile"],
      filters: [
        { name: "SQLite 数据库", extensions: ["db", "sqlite", "sqlite3"] },
        { name: "所有文件", extensions: ["*"] }
      ]
    });
    return selected.canceled ? null : selected.filePaths[0] ?? null;
  });
  ipcMain.handle("dialog:create-database-file", async event => {
    if (!trustedUrl(event.senderFrame?.url ?? "")) return null;
    const selected = await dialog.showSaveDialog(mainWindow!, {
      title: "新建 SQLite 数据库文件",
      defaultPath: "database.sqlite",
      filters: [{ name: "SQLite 数据库", extensions: ["sqlite", "db", "sqlite3"] }]
    });
    return selected.canceled ? null : selected.filePath ?? null;
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
    title: "数据库连接工具",
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
  mainWindow.on("close", event => {
    if (process.platform === "darwin" && !isQuitting) {
      event.preventDefault();
      mainWindow?.hide();
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
      label: "数据库连接工具",
      submenu: [
        { role: "about", label: "关于数据库连接工具" },
        { label: "检查更新…", click: () => mainWindow?.webContents.send("update:check") },
        { type: "separator" },
        { role: "hide", label: "隐藏数据库连接工具" },
        { role: "hideOthers", label: "隐藏其他" },
        { role: "unhide", label: "全部显示" },
        { type: "separator" },
        { role: "quit", label: "退出数据库连接工具" }
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
      // On macOS the close button intentionally hides the window so the
      // application stays running. A second launch should bring that hidden
      // window back to the foreground as well.
      if (!mainWindow.isVisible()) mainWindow.show();
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
      dialog.showErrorBox("数据库连接工具启动失败", cause instanceof Error ? cause.message : String(cause));
      app.quit();
    }
  });
}

app.on("activate", () => {
  if (mainWindow && !mainWindow.isDestroyed()) {
    if (mainWindow.isMinimized()) mainWindow.restore();
    // macOS emits `activate` when the Dock icon is clicked. The close handler
    // hides (rather than destroys) the window, so explicitly show it here.
    if (!mainWindow.isVisible()) mainWindow.show();
    mainWindow.focus();
    return;
  }
  if (BrowserWindow.getAllWindows().length === 0) void createWindow();
});
app.on("window-all-closed", () => app.quit());
app.on("before-quit", () => { isQuitting = true; bridge.close(); });
