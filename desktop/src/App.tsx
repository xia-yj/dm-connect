import { useEffect, useMemo, useState } from "react";
import {
  CheckCircle2, CircleHelp, Code2, Database, FileUp, History, LoaderCircle, LockKeyhole, Save,
  Plus, RefreshCw, Settings, Unplug, X
} from "lucide-react";
import { asRpcError, errorMessage, rpc } from "./api";
import { connectionSummary, databaseTypeLabel, isJdbcDatabase, isNativeDatabase, supportsTableDesigner } from "./database";
import { ConnectionModal } from "./components/ConnectionModal";
import { ConfirmModal, Modal } from "./components/Modal";
import { HistoryModal } from "./components/HistoryModal";
import { ObjectView } from "./components/ObjectView";
import { Sidebar } from "./components/Sidebar";
import { SqlWorkspace } from "./components/SqlWorkspace";
import { supportedTableTypes, TableDesignerModal } from "./components/TableDesignerModal";
import { WelcomePanel } from "./components/WelcomePanel";
import { NativeWorkspace } from "./components/NativeWorkspace";
import type {
  BootstrapData, ConnectionProfile, DatabaseObject, DatabaseObjectKind, DatabaseType,
  AppUpdateInfo, HistoryEntry, ObjectLoadResult, QueryOpenResult, QueryStatus, TableDefinitionDraft, TableDetails, WorkspaceTab
} from "./types";
import type { PagedResultTable } from "./types";

interface PasswordPrompt {
  profile: ConnectionProfile;
  password: string;
  error: string;
  busy: boolean;
}

interface PendingClose {
  tab: Extract<WorkspaceTab, { type: "sql" }>;
}

interface TableDesignerState {
  profileId: string;
  databaseType: DatabaseType;
  schema: string;
  object?: DatabaseObject;
  details?: TableDetails;
  focusColumnName?: string;
}

interface TabContextMenuState {
  x: number;
  y: number;
  tab: WorkspaceTab;
}

const welcomeTab: WorkspaceTab = { id: "welcome", type: "welcome", title: "概览" };
const defaultUpdateManifestUrl = "https://github.com/xia-yj/dm-connect/releases/latest/download/update.json";
const legacyUpdateManifestUrl = "http://10.20.25.68:8093/dm-connect-updates/update.json";

export default function App() {
  const [data, setData] = useState<BootstrapData | null>(null);
  const [fatal, setFatal] = useState("");
  const [loading, setLoading] = useState(true);
  const [status, setStatus] = useState("正在启动 Java 后端…");
  const [selectedProfileId, setSelectedProfileId] = useState<string | null>(null);
  const [schemasByProfile, setSchemasByProfile] = useState<Record<string, string[]>>({});
  const [objectsByKey, setObjectsByKey] = useState<Record<string, DatabaseObject[]>>({});
  const [loadingKeys, setLoadingKeys] = useState<Set<string>>(new Set());
  const [tabs, setTabs] = useState<WorkspaceTab[]>([welcomeTab]);
  const [activeTabId, setActiveTabId] = useState("welcome");
  const [tabContextMenu, setTabContextMenu] = useState<TabContextMenuState | null>(null);
  const [editingTabId, setEditingTabId] = useState<string | null>(null);
  const [editingTabTitle, setEditingTabTitle] = useState("");
  const [sqlByTabId, setSqlByTabId] = useState<Record<string, string>>({});
  const [connectionModal, setConnectionModal] = useState<ConnectionProfile | "new" | null>(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyRevision, setHistoryRevision] = useState(0);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [passwordPrompt, setPasswordPrompt] = useState<PasswordPrompt | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<ConnectionProfile | null>(null);
  const [resetConfirm, setResetConfirm] = useState(false);
  const [pendingClose, setPendingClose] = useState<PendingClose | null>(null);
  const [saveNotice, setSaveNotice] = useState("");
  const [tableDesigner, setTableDesigner] = useState<TableDesignerState | null>(null);
  const [updateManifestUrl, setUpdateManifestUrl] = useState(() => {
    const stored = localStorage.getItem("dm-connect.update-manifest-url");
    return stored && stored !== legacyUpdateManifestUrl ? stored : defaultUpdateManifestUrl;
  });
  const [availableUpdate, setAvailableUpdate] = useState<AppUpdateInfo | null>(null);
  const [updateDialog, setUpdateDialog] = useState<AppUpdateInfo | null>(null);
  const [checkingUpdate, setCheckingUpdate] = useState(false);
  const [installingUpdate, setInstallingUpdate] = useState(false);
  const [updateDownloadProgress, setUpdateDownloadProgress] = useState<number | null>(null);
  const [sidebarWidth, setSidebarWidth] = useState(() => {
    const saved = Number(localStorage.getItem("dm-connect.sidebar-width"));
    return Number.isFinite(saved) ? Math.max(280, Math.min(420, saved)) : 300;
  });
  const [resizingSidebar, setResizingSidebar] = useState(false);

  useEffect(() => {
    localStorage.setItem("dm-connect.sidebar-width", String(sidebarWidth));
  }, [sidebarWidth]);

  useEffect(() => {
    localStorage.setItem("dm-connect.update-manifest-url", updateManifestUrl);
  }, [updateManifestUrl]);

  useEffect(() => {
    if (!resizingSidebar) return;
    const resize = (event: PointerEvent) => {
      setSidebarWidth(Math.max(280, Math.min(440, window.innerWidth - 380, event.clientX)));
    };
    const stop = () => setResizingSidebar(false);
    window.addEventListener("pointermove", resize);
    window.addEventListener("pointerup", stop);
    return () => {
      window.removeEventListener("pointermove", resize);
      window.removeEventListener("pointerup", stop);
    };
  }, [resizingSidebar]);

  async function loadBootstrap() {
    const bootstrap = await rpc<BootstrapData>("app.bootstrap");
    setData(bootstrap);
    setSelectedProfileId(current => current && bootstrap.profiles.some(profile => profile.id === current)
      ? current : bootstrap.profiles[0]?.id ?? null);
    return bootstrap;
  }

  useEffect(() => {
    loadBootstrap().then(bootstrap => setStatus(bootstrap.legacyVaultBackup
      ? "已去掉主密码，旧加密数据已安全备份"
      : "工作台已就绪")).catch(cause => setFatal(errorMessage(cause))).finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    if (loading) return;
    void checkForAppUpdate(true);
  }, [loading]);

  useEffect(() => {
    const disposeCheck = window.dmConnect.onUpdateCheck(() => { void checkForAppUpdate(false); });
    const disposeStatus = window.dmConnect.onUpdateStatus(status => {
      setStatus(status);
      const progress = status.match(/(\d{1,3})%/);
      if (progress) setUpdateDownloadProgress(Math.min(100, Number(progress[1])));
    });
    return () => { disposeCheck(); disposeStatus(); };
  }, [updateManifestUrl]);

  const selectedProfile = useMemo(() => data?.profiles.find(profile => profile.id === selectedProfileId) ?? null, [data, selectedProfileId]);
  const hasConnectedJdbcProfile = Boolean(data?.profiles.some(profile => profile.connected && isJdbcDatabase(profile.databaseType)));
  const showSqlEntrypoints = !selectedProfile || isJdbcDatabase(selectedProfile.databaseType);

  function selectProfile(profileId: string) {
    setSelectedProfileId(profileId);
    const profile = data?.profiles.find(item => item.id === profileId);
    if (profile?.connected && isNativeDatabase(profile.databaseType)) setActiveTabId("welcome");
  }

  async function connect(profile: ConnectionProfile, password = "") {
    setSelectedProfileId(profile.id);
    setStatus(`正在连接 ${profile.name}…`);
    try {
      const result = await rpc<{ profile: ConnectionProfile; schemas: string[] }>("connection.connect", { profileId: profile.id, password });
      setSchemasByProfile(value => ({ ...value, [profile.id]: result.schemas }));
      await loadBootstrap();
      if (isNativeDatabase(profile.databaseType)) setActiveTabId("welcome");
      setPasswordPrompt(null);
      setStatus(`已连接 ${profile.name} · ${result.schemas.length} 个模式`);
    } catch (cause) {
      const error = asRpcError(cause);
      if (error.code === "PASSWORD_REQUIRED") {
        setPasswordPrompt({ profile, password: "", error: "", busy: false });
      } else {
        setPasswordPrompt(current => current?.profile.id === profile.id ? { ...current, busy: false, error: error.message } : current);
        setStatus(`连接失败：${error.message}`);
      }
    }
  }

  async function disconnect(profile: ConnectionProfile) {
    await rpc("connection.disconnect", { profileId: profile.id });
    setSchemasByProfile(value => { const next = { ...value }; delete next[profile.id]; return next; });
    setObjectsByKey(value => Object.fromEntries(Object.entries(value).filter(([key]) => !key.startsWith(`${profile.id}:`))));
    setTabs(value => value.filter(tab => tab.type === "welcome" || tab.profileId !== profile.id));
    setActiveTabId("welcome");
    await loadBootstrap();
    setStatus(`已断开 ${profile.name}`);
  }

  async function refreshSchemas(profile: ConnectionProfile) {
    setStatus(`正在刷新 ${profile.name}…`);
    try {
      const schemas = await rpc<string[]>("connection.schemas", { profileId: profile.id });
      setSchemasByProfile(value => ({ ...value, [profile.id]: schemas }));
      setObjectsByKey(value => Object.fromEntries(Object.entries(value).filter(([key]) => !key.startsWith(`${profile.id}:`))));
      setStatus(`对象树已刷新 · ${schemas.length} 个模式`);
    } catch (cause) {
      setStatus(`刷新失败：${errorMessage(cause)}`);
    }
  }

  async function loadObjects(profileId: string, schema: string, kind: DatabaseObjectKind) {
    const key = `${profileId}:${schema}:${kind}`;
    setLoadingKeys(value => new Set(value).add(key));
    try {
      const objects = await rpc<DatabaseObject[]>("objects.list", { profileId, schema, kind });
      setObjectsByKey(value => ({ ...value, [key]: objects }));
    } catch (cause) {
      setStatus(`加载对象失败：${errorMessage(cause)}`);
      setObjectsByKey(value => ({ ...value, [key]: [] }));
    } finally {
      setLoadingKeys(value => { const next = new Set(value); next.delete(key); return next; });
    }
  }

  async function reloadTable(profileId: string, object: DatabaseObject) {
    const result = await rpc<ObjectLoadResult>("object.load", { profileId, ...object });
    const id = `object:${profileId}:${object.schema}:${object.kind}:${object.name}`;
    setTabs(value => value.map(tab => tab.id === id && tab.type === "object" ? { ...tab, result } : tab));
  }

  async function editTable(profileId: string, object: DatabaseObject, focusColumnName?: string) {
    setStatus(`正在读取 ${object.schema}.${object.name} 的表结构…`);
    try {
      const profile = data?.profiles.find(item => item.id === profileId);
      if (!profile) throw new Error("找不到数据库连接配置");
      if (!supportsTableDesigner(profile.databaseType)) throw new Error("当前数据库请通过 SQL 工作台修改表结构");
      const result = await rpc<ObjectLoadResult>("object.load", { profileId, ...object });
      if (!result.details) throw new Error(result.detailsError || "未能读取表结构");
      const unsafe = result.details.columns.find(column => column.safelyEditable === false);
      if (unsafe) throw new Error(`字段“${unsafe.name}”不能安全地使用表设计器编辑：${unsafe.editWarning || "包含图形化 DDL 暂无法完整保留的属性"}，请通过 SQL 工作台处理`);
      const supportedTypes = supportedTableTypes(profile.databaseType);
      const unsupported = result.details.columns.find(column => !supportedTypes.has(column.typeName.toUpperCase()));
      if (unsupported) throw new Error(`字段“${unsupported.name}”使用 ${unsupported.typeName} 类型，当前 ${profile.databaseType === "mysql" ? "MySQL" : "达梦"} 表设计器尚不支持，请通过 SQL 工作台编辑`);
      setTableDesigner({ profileId, databaseType: profile.databaseType, schema: object.schema, object, details: result.details, focusColumnName });
      setStatus("表结构已加载");
    } catch (cause) {
      setStatus(`读取表结构失败：${errorMessage(cause)}`);
    }
  }

  async function getLongRowStatus(profileId: string, object: DatabaseObject): Promise<boolean> {
    const result = await rpc<{ enabled: boolean }>("table.longRowStatus", { profileId, schema: object.schema, name: object.name });
    return result.enabled;
  }

  async function setLongRow(profileId: string, object: DatabaseObject, enabled: boolean) {
    setStatus(`${enabled ? "正在启用" : "正在关闭"} ${object.schema}.${object.name} 的超长记录…`);
    try {
      await rpc("table.setLongRow", { profileId, schema: object.schema, name: object.name, enabled });
      await reloadTable(profileId, object);
      setStatus(`${object.schema}.${object.name} 已${enabled ? "启用" : "关闭"}超长记录`);
    } catch (cause) {
      setStatus(`${enabled ? "启用" : "关闭"}超长记录失败：${errorMessage(cause)}`);
    }
  }

  async function createTable(profileId: string, definition: TableDefinitionDraft) {
    await rpc("table.create", { profileId, ...definition });
    const object: DatabaseObject = { schema: definition.schema, name: definition.name, kind: "TABLE", remarks: "" };
    await loadObjects(profileId, definition.schema, "TABLE");
    setTableDesigner(null);
    await openObject(profileId, object);
    setStatus(`已创建表 ${definition.schema}.${definition.name}`);
  }

  async function alterTable(profileId: string, object: DatabaseObject, original: TableDefinitionDraft, target: TableDefinitionDraft) {
    await rpc("table.alter", { profileId, original, target });
    await loadObjects(profileId, object.schema, "TABLE");
    await reloadTable(profileId, object);
    setTableDesigner(null);
    setStatus(`已更新表 ${object.schema}.${object.name}`);
  }

  async function openObject(profileId: string, object: DatabaseObject) {
    const id = `object:${profileId}:${object.schema}:${object.kind}:${object.name}`;
    if (tabs.some(tab => tab.id === id)) { setActiveTabId(id); return; }
    setStatus(`正在加载 ${object.schema}.${object.name}…`);
    try {
      const result = await rpc<ObjectLoadResult>("object.load", { profileId, ...object });
      const tab: WorkspaceTab = { id, type: "object", title: object.name, profileId, result };
      setTabs(value => [...value, tab]);
      setActiveTabId(id);
      setStatus(`${object.kind === "TABLE" ? "表数据预览" : "对象详情"}已加载`);
    } catch (cause) {
      setStatus(`加载对象失败：${errorMessage(cause)}`);
    }
  }

  async function loadObjectPreview(profileId: string, object: DatabaseObject, page: number, filter: { column: string; operator: "=" | "LIKE"; value: string }[] | null): Promise<PagedResultTable> {
    return rpc<PagedResultTable>("object.preview", { profileId, ...object, page, pageSize: 100, ...(filter ? { filters: filter } : {}) });
  }

  async function saveObjectChanges(profileId: string, object: DatabaseObject, changes: { column: string; value: string | null; keyValues: Record<string, unknown> }[]) {
    await rpc("object.updateCells", { profileId, ...object, changes });
    setStatus(`已保存 ${changes.length} 项修改`);
    setSaveNotice(`已保存 ${changes.length} 项修改`);
    window.setTimeout(() => setSaveNotice(""), 2400);
  }

  async function deleteObjectRow(profileId: string, object: DatabaseObject, keyValues: Record<string, unknown>) {
    await rpc("object.deleteRow", { profileId, ...object, keyValues });
    setStatus("已删除 1 行数据");
    setSaveNotice("已删除 1 行数据");
    window.setTimeout(() => setSaveNotice(""), 2400);
  }

  async function exportTableInsert(profileId: string, object: DatabaseObject, scope: "CURRENT_PAGE" | "ALL", page: number, filter: { column: string; operator: "=" | "LIKE"; value: string }[] | null) {
    const path = await window.dmConnect.saveSql();
    if (!path) return;
    const result = await rpc<{ rows: number }>("table.exportInsert", { profileId, ...object, path, scope, page, pageSize: 100, ...(filter ? { filters: filter } : {}) });
    setStatus(`已导出 ${result.rows} 条 INSERT 语句`);
  }
  async function exportTableCsv(profileId: string, object: DatabaseObject, scope: "CURRENT_PAGE" | "ALL", page: number, filter: { column: string; operator: "=" | "LIKE"; value: string }[] | null) {
    const path = await window.dmConnect.saveCsv(`${object.name}.csv`);
    if (!path) return;
    const result = await rpc<{ rows: number }>("table.exportCsv", { profileId, ...object, path, scope, page, pageSize: 100, ...(filter ? { filters: filter } : {}) });
    setStatus(`已导出 ${result.rows} 行 CSV`);
  }

  async function newSql(profileId?: string, initialSql = "") {
    const requestedProfile = profileId ? data?.profiles.find(item => item.id === profileId) : undefined;
    const selectedJdbcProfile = !profileId && selectedProfile?.connected && isJdbcDatabase(selectedProfile.databaseType) ? selectedProfile : undefined;
    const profile = requestedProfile ?? selectedJdbcProfile
      ?? data?.profiles.find(item => item.connected && isJdbcDatabase(item.databaseType));
    if (!profile?.connected) { setStatus("请先连接数据库，再打开 SQL 工作台"); return; }
    if (isNativeDatabase(profile.databaseType)) { setStatus(`${profile.name} 使用原生工作台，不支持 SQL 会话`); return; }
    setStatus(`正在为 ${profile.name} 创建独立 SQL 会话…`);
    try {
      const opened = await rpc<QueryOpenResult>("query.open", { profileId: profile.id });
      const id = `sql:${opened.sessionId}`;
      setSqlByTabId(value => ({ ...value, [id]: initialSql }));
      setTabs(value => [...value, { id, type: "sql", title: `SQL · ${profile.name}`, profileId: profile.id, profileName: profile.name, databaseType: profile.databaseType, sessionId: opened.sessionId, initialSql }]);
      setActiveTabId(id);
      setStatus(`SQL 会话已创建 · ${profile.name}`);
    } catch (cause) {
      setStatus(`创建 SQL 会话失败：${errorMessage(cause)}`);
    }
  }

  async function closeTab(tab: WorkspaceTab) {
    if (tab.type === "welcome") return;
    if (tab.type === "sql") {
      try {
        const query = await rpc<QueryStatus>("query.status", { sessionId: tab.sessionId });
        if (query.pendingTransaction) { setPendingClose({ tab }); return; }
        await rpc("query.close", { sessionId: tab.sessionId });
      } catch (cause) {
        if (asRpcError(cause).code !== "SESSION_NOT_FOUND") setStatus(errorMessage(cause));
      }
    }
    removeTab(tab.id);
  }

  function removeTab(tabId: string) {
    setTabs(value => value.filter(tab => tab.id !== tabId));
    setSqlByTabId(value => {
      if (!(tabId in value)) return value;
      const next = { ...value };
      delete next[tabId];
      return next;
    });
    if (activeTabId === tabId) setActiveTabId("welcome");
  }

  async function saveSqlTab(tab: Extract<WorkspaceTab, { type: "sql" }>) {
    const content = sqlByTabId[tab.id] ?? tab.initialSql ?? "";
    if (!content.trim()) {
      setStatus("当前 SQL 为空，暂时无法保存");
      return;
    }
    try {
      const path = await window.dmConnect.saveLocalSql(tab.title, content);
      if (path) setStatus(`SQL 已保存：${path}`);
    } catch (cause) {
      setStatus(`保存 SQL 失败：${errorMessage(cause)}`);
    }
  }

  function beginTabRename(tab: WorkspaceTab) {
    if (tab.type === "welcome") return;
    setEditingTabId(tab.id);
    setEditingTabTitle(tab.title);
  }

  function finishTabRename(tabId: string) {
    const title = editingTabTitle.trim();
    if (title) setTabs(value => value.map(tab => tab.id === tabId ? { ...tab, title } : tab));
    setEditingTabId(null);
  }

  function closeTabs(targetTabs: WorkspaceTab[]) {
    setTabContextMenu(null);
    void Promise.all(targetTabs.filter(tab => tab.type !== "welcome").map(tab => closeTab(tab)));
  }

  async function closePending(action: "COMMIT" | "ROLLBACK") {
    if (!pendingClose) return;
    const { tab } = pendingClose;
    setPendingClose(null);
    try {
      await rpc("query.close", { sessionId: tab.sessionId, transactionAction: action });
      removeTab(tab.id);
    } catch (cause) {
      setStatus(`关闭 SQL 标签失败：${errorMessage(cause)}`);
    }
  }

  function updateDisplayVersion(update: AppUpdateInfo): string {
    return update.build && update.build !== update.version ? `${update.version} (${update.build})` : update.version;
  }

  async function checkForAppUpdate(silent = false) {
    const manifestUrl = updateManifestUrl.trim();
    if (!manifestUrl) {
      if (!silent) setStatus("请先在设置中填写更新清单地址");
      return;
    }
    if (checkingUpdate || installingUpdate) return;
    setCheckingUpdate(true);
    if (!silent) setStatus("正在检查新版本…");
    try {
      const update = await window.dmConnect.checkForUpdate(manifestUrl);
      setAvailableUpdate(update);
      if (update) {
        setStatus(`发现新版本：${updateDisplayVersion(update)}`);
        setUpdateDialog(update);
      } else if (!silent) {
        setStatus("当前已是最新版本");
      }
    } catch (cause) {
      if (!silent) setStatus(`检查更新失败：${errorMessage(cause)}`);
    } finally {
      setCheckingUpdate(false);
    }
  }

  async function installAvailableUpdate() {
    if (!updateDialog || installingUpdate) return;
    setInstallingUpdate(true);
    setUpdateDownloadProgress(0);
    try {
      await window.dmConnect.installUpdate(updateManifestUrl.trim());
    } catch (cause) {
      setInstallingUpdate(false);
      setUpdateDownloadProgress(null);
      setStatus(`更新失败：${errorMessage(cause)}`);
    }
  }

  async function copyProfile(profile: ConnectionProfile) {
    await rpc("profile.copy", { profileId: profile.id });
    await loadBootstrap();
    setStatus(`已复制连接 ${profile.name}`);
  }

  async function deleteProfile() {
    if (!deleteTarget) return;
    const profile = deleteTarget;
    setDeleteTarget(null);
    await rpc("profile.delete", { profileId: profile.id });
    await loadBootstrap();
    setStatus(`已删除连接 ${profile.name}`);
  }

  async function resetLocalData() {
    setResetConfirm(false);
    setSettingsOpen(false);
    await rpc("storage.reset");
    setTabs([welcomeTab]);
    setActiveTabId("welcome");
    setSchemasByProfile({});
    setObjectsByKey({});
    await loadBootstrap();
    setStatus("已清除保存的数据库密码和 SQL 历史");
  }

  async function openHistory(entry: HistoryEntry) {
    setHistoryOpen(false);
    const profile = data?.profiles.find(item => item.id === entry.profileId);
    if (!profile?.connected) { setStatus(`请先连接 ${entry.profileName}，再恢复这条 SQL 历史`); return; }
    await newSql(entry.profileId, entry.sql);
  }

  if (loading) return <div className="boot-screen"><LoaderCircle className="spin" size={30} /><strong>数据库连接工具</strong><span>{status}</span></div>;
  if (fatal || !data) return <div className="boot-screen failed"><CircleHelp size={32} /><strong>应用启动失败</strong><span>{fatal || "无法读取后端状态"}</span><button className="button primary" onClick={() => location.reload()}>重新加载</button></div>;

  return (
    <div className={`app-shell${resizingSidebar ? " sidebar-resizing" : ""}`}>
      <Sidebar
        width={sidebarWidth}
        profiles={data.profiles}
        selectedProfileId={selectedProfileId}
        schemasByProfile={schemasByProfile}
        objectsByKey={objectsByKey}
        loadingKeys={loadingKeys}
        onSelectProfile={selectProfile}
        onNewConnection={() => setConnectionModal("new")}
        onEditConnection={setConnectionModal}
        onCopyConnection={profile => void copyProfile(profile)}
        onDeleteConnection={setDeleteTarget}
        onConnect={connect}
        onDisconnect={disconnect}
        onRefresh={refreshSchemas}
        onLoadObjects={loadObjects}
        onOpenObject={openObject}
        onNewTable={(profileId, schema) => {
          const profile = data.profiles.find(item => item.id === profileId);
          if (profile && supportsTableDesigner(profile.databaseType)) setTableDesigner({ profileId, databaseType: profile.databaseType, schema });
        }}
        onEditTable={(profileId, object) => void editTable(profileId, object)}
        onGetLongRowStatus={getLongRowStatus}
        onSetLongRow={(profileId, object, enabled) => void setLongRow(profileId, object, enabled)}
      />
      <div className="sidebar-resizer" role="separator" aria-orientation="vertical" aria-label="调整数据库资源栏宽度" onPointerDown={event => { event.preventDefault(); setResizingSidebar(true); }} />
      <main className="workspace-shell">
        <header className="workspace-toolbar">
          <div className="toolbar-context">
            <span className={`context-icon${selectedProfile?.connected ? " online" : ""}`}><Database size={17} /></span>
            <div><span className="connection-name"><strong>{selectedProfile?.name ?? "数据库连接工作台"}</strong>{selectedProfile && <em className={`database-type-badge ${selectedProfile.databaseType}`}>{databaseTypeLabel(selectedProfile.databaseType)}</em>}</span><small>{selectedProfile ? connectionSummary(selectedProfile) : "选择或创建数据库连接"}</small></div>
          </div>
          <span className="app-status">{status}</span>
          <div className="workspace-actions">
            {selectedProfile && !selectedProfile.connected && <button className="button primary compact" onClick={() => connect(selectedProfile)}>连接</button>}
            {selectedProfile?.connected && <button className="button secondary compact" onClick={() => disconnect(selectedProfile)}><Unplug size={14} />断开</button>}
            {showSqlEntrypoints && <button className="button primary compact" onClick={() => newSql()} disabled={!hasConnectedJdbcProfile}><Code2 size={14} />新建 SQL</button>}
            <button className="icon-button toolbar-icon" onClick={() => selectedProfile?.connected && refreshSchemas(selectedProfile)} title="刷新对象"><RefreshCw size={16} /></button>
            <button className="icon-button toolbar-icon" onClick={() => setHistoryOpen(true)} title="SQL 历史"><History size={16} /></button>
            <button className="icon-button toolbar-icon" onClick={() => setSettingsOpen(true)} title="设置"><Settings size={16} /></button>
          </div>
        </header>

        <div className="workspace-tabbar" onMouseDown={() => tabContextMenu && setTabContextMenu(null)}>
          <div className="workspace-tabs">{tabs.map(tab => <button key={tab.id} className={activeTabId === tab.id ? "active" : ""} onClick={() => setActiveTabId(tab.id)} onContextMenu={event => { event.preventDefault(); event.stopPropagation(); setTabContextMenu({ tab, x: event.clientX, y: event.clientY }); }}>
            {tab.type === "welcome" ? <Database size={14} /> : tab.type === "sql" ? <Code2 size={14} /> : <Database size={14} />}
            {editingTabId === tab.id ? <input className="workspace-tab-rename" value={editingTabTitle} autoFocus onChange={event => setEditingTabTitle(event.target.value)} onBlur={() => finishTabRename(tab.id)} onKeyDown={event => { if (event.key === "Enter") finishTabRename(tab.id); if (event.key === "Escape") setEditingTabId(null); }} onClick={event => event.stopPropagation()} /> : <span title={tab.title}>{tab.title}</span>}
            {tab.type !== "welcome" && <i role="button" aria-label={`关闭 ${tab.title}`} onClick={event => { event.stopPropagation(); void closeTab(tab); }}><X size={12} /></i>}
          </button>)}</div>
          {showSqlEntrypoints && <button className="new-tab-button" onClick={() => newSql()} disabled={!hasConnectedJdbcProfile} title="新建 SQL 标签"><Plus size={15} /></button>}
          {tabContextMenu && <div className="tab-context-menu" style={{ left: tabContextMenu.x, top: tabContextMenu.y }} onMouseDown={event => event.stopPropagation()}>
            {tabContextMenu.tab.type === "sql" && <button onClick={() => { const tab = tabContextMenu.tab; setTabContextMenu(null); if (tab.type === "sql") void saveSqlTab(tab); }}><Save size={14} />保存 SQL</button>}
            {tabContextMenu.tab.type !== "welcome" && <button onClick={() => { const tab = tabContextMenu.tab; setTabContextMenu(null); beginTabRename(tab); }}>重命名</button>}
            <button onClick={() => closeTabs([tabContextMenu.tab])} disabled={tabContextMenu.tab.type === "welcome"}>关闭</button>
            <button onClick={() => closeTabs(tabs.filter(tab => tab.id !== tabContextMenu.tab.id && tab.type !== "welcome"))} disabled={tabs.filter(tab => tab.type !== "welcome" && tab.id !== tabContextMenu.tab.id).length === 0}>关闭其他</button>
            <button onClick={() => closeTabs(tabs.slice(tabs.findIndex(tab => tab.id === tabContextMenu.tab.id) + 1))} disabled={tabs.slice(tabs.findIndex(tab => tab.id === tabContextMenu.tab.id) + 1).every(tab => tab.type === "welcome")}>关闭右侧</button>
          </div>}
        </div>

        <div className="workspace-content">
          {tabs.map(tab => <div key={tab.id} className={`workspace-panel${activeTabId === tab.id ? " active" : ""}`}>
            {tab.type === "welcome" && selectedProfile?.connected && isNativeDatabase(selectedProfile.databaseType) ? <NativeWorkspace key={selectedProfile.id} profile={selectedProfile} namespaces={schemasByProfile[selectedProfile.id] ?? []} /> : tab.type === "welcome" && <WelcomePanel data={data} showSqlAction={showSqlEntrypoints} onNewConnection={() => setConnectionModal("new")} onNewSql={() => newSql()} onImportDriver={() => setConnectionModal("new")} onShowHistory={() => setHistoryOpen(true)} onOpenHistory={entry => void openHistory(entry)} historyRevision={historyRevision} />}
            {tab.type === "object" && <ObjectView result={tab.result} onLoadPreview={(page, filter) => loadObjectPreview(tab.profileId, tab.result.object, page, filter)} onSaveChanges={changes => saveObjectChanges(tab.profileId, tab.result.object, changes)} onDeleteRow={keyValues => deleteObjectRow(tab.profileId, tab.result.object, keyValues)} onEditTable={supportsTableDesigner(data.profiles.find(item => item.id === tab.profileId)?.databaseType ?? "sqlite") ? columnName => void editTable(tab.profileId, tab.result.object, columnName) : undefined} onExportInsert={(scope, page, filter) => exportTableInsert(tab.profileId, tab.result.object, scope, page, filter)} onExportCsv={(scope, page, filter) => exportTableCsv(tab.profileId, tab.result.object, scope, page, filter)} />}
            {tab.type === "sql" && activeTabId === tab.id && <SqlWorkspace sessionId={tab.sessionId} profileName={tab.profileName} profileId={tab.profileId} databaseType={tab.databaseType} schemas={schemasByProfile[tab.profileId] ?? []} tableSuggestions={Object.entries(objectsByKey).filter(([key]) => key.startsWith(`${tab.profileId}:`) && key.endsWith(":TABLE")).flatMap(([, objects]) => objects)} initialSql={sqlByTabId[tab.id] ?? tab.initialSql} onSqlChange={sql => setSqlByTabId(value => ({ ...value, [tab.id]: sql }))} onHistoryChanged={() => setHistoryRevision(value => value + 1)} />}
          </div>)}
        </div>
      </main>

      {saveNotice && <div className="save-notice" role="status"><CheckCircle2 size={18} /><span>{saveNotice}</span></div>}

      {connectionModal && <ConnectionModal
        key={connectionModal === "new" ? "new" : connectionModal.id}
        profile={connectionModal === "new" ? undefined : connectionModal}
        drivers={data.drivers}
        onDriversChanged={drivers => setData(value => value ? { ...value, drivers } : value)}
        onClose={() => setConnectionModal(null)}
        onSaved={() => { setConnectionModal(null); void loadBootstrap(); setStatus("连接配置已保存"); }}
      />}
      {tableDesigner && <TableDesignerModal
        databaseType={tableDesigner.databaseType}
        object={tableDesigner.object}
        details={tableDesigner.details}
        schema={tableDesigner.schema}
        focusColumnName={tableDesigner.focusColumnName}
        onClose={() => setTableDesigner(null)}
        onCreate={definition => createTable(tableDesigner.profileId, definition)}
        onAlter={(original, target) => alterTable(tableDesigner.profileId, tableDesigner.object!, original, target)}
      />}
      {historyOpen && <HistoryModal onClose={() => setHistoryOpen(false)} onOpen={entry => void openHistory(entry)} onHistoryChanged={() => setHistoryRevision(value => value + 1)} />}
      {passwordPrompt && <Modal title={`连接到 ${passwordPrompt.profile.name}`} description={connectionSummary(passwordPrompt.profile)} onClose={() => setPasswordPrompt(null)} footer={<><button className="button secondary" onClick={() => setPasswordPrompt(null)}>取消</button><button className="button primary" disabled={passwordPrompt.busy || !passwordPrompt.password} onClick={() => { setPasswordPrompt({ ...passwordPrompt, busy: true, error: "" }); void connect(passwordPrompt.profile, passwordPrompt.password); }}>{passwordPrompt.busy && <LoaderCircle className="spin" size={15} />}连接</button></>}>
        <label className="field-label">数据库密码<input autoFocus className="field-input" type="password" value={passwordPrompt.password} onChange={event => setPasswordPrompt({ ...passwordPrompt, password: event.target.value })} onKeyDown={event => { if (event.key === "Enter" && passwordPrompt.password) { setPasswordPrompt({ ...passwordPrompt, busy: true, error: "" }); void connect(passwordPrompt.profile, passwordPrompt.password); } }} /></label>{passwordPrompt.error && <div className="form-error">{passwordPrompt.error}</div>}
      </Modal>}
      {settingsOpen && <Modal title="数据库连接工具设置" description={`桌面客户端 ${data.version}`} onClose={() => setSettingsOpen(false)} footer={<button className="button primary" onClick={() => setSettingsOpen(false)}>完成</button>}>
        <div className="settings-list"><section><span><FileUp size={18} /></span><div><strong>JDBC 驱动</strong><small>MySQL、PostgreSQL、SQL Server、SQLite 已内置；DM、Oracle 或替代版本可从连接窗口导入</small></div><button className="button secondary compact" onClick={() => { setSettingsOpen(false); setConnectionModal("new"); }}>管理驱动</button></section><section className="update-settings"><span><RefreshCw size={18} /></span><div><strong>应用更新</strong><small>更新清单地址</small><input className="field-input" value={updateManifestUrl} placeholder="http://服务器地址/dm-connect-updates/update.json" onChange={event => setUpdateManifestUrl(event.target.value)} /></div><button className="button secondary compact" disabled={checkingUpdate || installingUpdate} onClick={() => void checkForAppUpdate(false)}>{checkingUpdate ? "检查中" : "检查更新"}</button></section><section><span><LockKeyhole size={18} /></span><div><strong>本地数据</strong><small>密码和 SQL 历史仅保存在本机，应用自动管理加密密钥</small></div><button className="button danger-text compact" onClick={() => setResetConfirm(true)}>清除</button></section></div>
      </Modal>}
      {updateDialog && <Modal title={`发现新版本 ${updateDisplayVersion(updateDialog)}`} description="下载完成后，应用会自动退出、替换并重启。" onClose={() => !installingUpdate && setUpdateDialog(null)} footer={<><button className="button secondary" disabled={installingUpdate} onClick={() => setUpdateDialog(null)}>稍后</button><button className="button primary" disabled={installingUpdate} onClick={() => void installAvailableUpdate()}>{installingUpdate && <LoaderCircle className="spin" size={15} />}{installingUpdate ? `正在下载${updateDownloadProgress == null ? "" : ` ${updateDownloadProgress}%`}` : "立即更新"}</button></>}><p className="confirm-message">{updateDialog.notes?.trim() || "本次更新已准备好，可以立即安装。"}</p>{installingUpdate && <div className="update-download-progress" aria-label="更新下载进度"><div><span>正在下载更新包</span><strong>{updateDownloadProgress == null ? "准备中" : `${updateDownloadProgress}%`}</strong></div><i><b style={{ width: `${updateDownloadProgress ?? 0}%` }} /></i></div>}</Modal>}
      {deleteTarget && <ConfirmModal title="删除数据库连接" message={`确认删除“${deleteTarget.name}”吗？保存的密码也会一并删除。`} confirmText="删除连接" danger onCancel={() => setDeleteTarget(null)} onConfirm={() => void deleteProfile()} />}
      {resetConfirm && <ConfirmModal title="清除本地数据" message="这会删除所有已保存的数据库密码和 SQL 历史，但保留连接配置。" confirmText="确认清除" danger onCancel={() => setResetConfirm(false)} onConfirm={() => void resetLocalData()} />}
      {pendingClose && <Modal title="存在未提交事务" description={pendingClose.tab.title} onClose={() => setPendingClose(null)} footer={<><button className="button secondary" onClick={() => setPendingClose(null)}>取消</button><button className="button secondary" onClick={() => closePending("ROLLBACK")}>回滚并关闭</button><button className="button primary" onClick={() => closePending("COMMIT")}>提交并关闭</button></>}><p className="confirm-message">此 SQL 标签存在未提交事务，请先选择提交或回滚。</p></Modal>}
    </div>
  );
}
