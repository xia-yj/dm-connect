import {
  Braces, ChevronDown, CircleDot, Code2, Copy, Database, Download, FileCode2, FunctionSquare,
  Layers3, ListFilter, Plus, RefreshCw, Search, Table2, Trash2, Unplug, View
} from "lucide-react";
import { useEffect, useRef, useState, type MouseEvent, type WheelEvent } from "react";
import type { ConnectionProfile, DatabaseObject, DatabaseObjectKind, DatabaseType } from "../types";
import { databaseTypeLabel } from "../database";
import { Brand } from "./Brand";

export const OBJECT_KINDS: { kind: DatabaseObjectKind; label: string; icon: typeof Table2 }[] = [
  { kind: "TABLE", label: "表", icon: Table2 },
  { kind: "VIEW", label: "视图", icon: View },
  { kind: "SEQUENCE", label: "序列", icon: CircleDot },
  { kind: "PROCEDURE", label: "存储过程", icon: Braces },
  { kind: "FUNCTION", label: "函数", icon: FunctionSquare },
  { kind: "TRIGGER", label: "触发器", icon: FileCode2 }
];

interface SidebarProps {
  width: number;
  profiles: ConnectionProfile[];
  selectedProfileId: string | null;
  schemasByProfile: Record<string, string[]>;
  objectsByKey: Record<string, DatabaseObject[]>;
  loadingKeys: Set<string>;
  onSelectProfile: (profileId: string) => void;
  onNewConnection: () => void;
  onEditConnection: (profile: ConnectionProfile) => void;
  onCopyConnection: (profile: ConnectionProfile) => void;
  onDeleteConnection: (profile: ConnectionProfile) => void;
  onConnect: (profile: ConnectionProfile) => void;
  onDisconnect: (profile: ConnectionProfile) => void;
  onRefresh: (profile: ConnectionProfile) => void;
  onLoadObjects: (profileId: string, schema: string, kind: DatabaseObjectKind) => void;
  onOpenObject: (profileId: string, object: DatabaseObject) => void;
  onNewTable: (profileId: string, schema: string) => void;
  onEditTable: (profileId: string, object: DatabaseObject) => void;
  onGetLongRowStatus: (profileId: string, object: DatabaseObject) => Promise<boolean>;
  onSetLongRow: (profileId: string, object: DatabaseObject, enabled: boolean) => void;
  onCheckUpdate: () => void;
  updateAvailable: boolean;
  checkingUpdate: boolean;
  installingUpdate: boolean;
}

function objectKey(profileId: string, schema: string, kind: DatabaseObjectKind) {
  return `${profileId}:${schema}:${kind}`;
}

const SCHEMA_VISIBILITY_STORAGE_KEY = "dm-connect.hidden-schemas";

function readHiddenSchemas(): Record<string, string[]> {
  try {
    return JSON.parse(localStorage.getItem(SCHEMA_VISIBILITY_STORAGE_KEY) ?? "{}") as Record<string, string[]>;
  } catch {
    return {};
  }
}

export function Sidebar(props: SidebarProps) {
  const [filter, setFilter] = useState("");
  const [hiddenSchemas, setHiddenSchemas] = useState<Record<string, string[]>>(readHiddenSchemas);
  const active = props.profiles.find(profile => profile.id === props.selectedProfileId) ?? null;
  const needle = filter.trim().toLowerCase();
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; profileId: string; databaseType: DatabaseType; schema: string; profile?: ConnectionProfile; object?: DatabaseObject; longRowEnabled?: boolean; loadingLongRow?: boolean; longRowStatusError?: boolean } | null>(null);
  const [expandedProfiles, setExpandedProfiles] = useState<Set<string>>(new Set());

  useEffect(() => {
    if (!props.selectedProfileId) return;
    setExpandedProfiles(value => new Set(value).add(props.selectedProfileId!));
  }, [props.selectedProfileId]);
  const schemaVisibilityRef = useRef<HTMLDetailsElement | null>(null);

  useEffect(() => {
    if (!contextMenu) return;
    const close = () => setContextMenu(null);
    const escape = (event: KeyboardEvent) => { if (event.key === "Escape") close(); };
    window.addEventListener("mousedown", close);
    window.addEventListener("keydown", escape);
    return () => { window.removeEventListener("mousedown", close); window.removeEventListener("keydown", escape); };
  }, [contextMenu]);

  useEffect(() => {
    const closeSchemaVisibility = (event: globalThis.MouseEvent) => {
      const panel = schemaVisibilityRef.current;
      if (panel?.open && event.target instanceof Node && !panel.contains(event.target)) panel.open = false;
    };
    window.addEventListener("mousedown", closeSchemaVisibility);
    return () => window.removeEventListener("mousedown", closeSchemaVisibility);
  }, []);

  function openContextMenu(event: MouseEvent, profileId: string, schema: string, object?: DatabaseObject) {
    event.preventDefault();
    event.stopPropagation();
    const databaseType = props.profiles.find(profile => profile.id === profileId)?.databaseType ?? "dm";
    const menu = { x: event.clientX, y: event.clientY, profileId, databaseType, schema, object, loadingLongRow: Boolean(object && databaseType === "dm") };
    setContextMenu(menu);
    if (object && databaseType === "dm") {
      void props.onGetLongRowStatus(profileId, object).then(enabled => {
        setContextMenu(current => current && current.object === object ? { ...current, longRowEnabled: enabled, loadingLongRow: false } : current);
      }).catch(() => {
        setContextMenu(current => current && current.object === object ? { ...current, loadingLongRow: false, longRowStatusError: true } : current);
      });
    }
  }

  function openProfileContextMenu(event: MouseEvent, profile: ConnectionProfile) {
    event.preventDefault();
    event.stopPropagation();
    setContextMenu({ x: event.clientX, y: event.clientY, profileId: profile.id, databaseType: profile.databaseType, schema: "", profile });
  }

  function scrollTree(event: WheelEvent<HTMLDivElement>) {
    const tree = event.currentTarget;
    const innerList = (event.target as HTMLElement).closest<HTMLElement>(".schema-visibility-options");
    if (innerList && tree.contains(innerList)) {
      event.preventDefault();
      innerList.scrollTop += event.deltaY;
      return;
    }
    if (tree.scrollHeight <= tree.clientHeight) return;
    event.preventDefault();
    tree.scrollTop += event.deltaY;
  }

  useEffect(() => {
    if (!needle || !active?.connected) return;
    const hidden = new Set(hiddenSchemas[active.id] ?? []);
    for (const schema of (props.schemasByProfile[active.id] ?? []).filter(item => !hidden.has(item))) {
      const key = objectKey(active.id, schema, "TABLE");
      if (!props.objectsByKey[key] && !props.loadingKeys.has(key)) {
        props.onLoadObjects(active.id, schema, "TABLE");
      }
    }
  }, [needle, active, hiddenSchemas, props.schemasByProfile, props.objectsByKey, props.loadingKeys, props.onLoadObjects]);

  function updateHiddenSchemas(profileId: string, next: string[]) {
    setHiddenSchemas(current => {
      const updated = { ...current, [profileId]: next };
      localStorage.setItem(SCHEMA_VISIBILITY_STORAGE_KEY, JSON.stringify(updated));
      return updated;
    });
  }

  return (
    <aside className="sidebar" style={{ width: props.width, minWidth: props.width, maxWidth: props.width }}>
      <div className="sidebar-drag" aria-label="窗口拖拽区域">
        <Brand compact />
        <button className={`sidebar-update-button${props.updateAvailable ? " ready" : ""}`} onClick={props.onCheckUpdate} disabled={props.checkingUpdate || props.installingUpdate} title={props.updateAvailable ? "发现新版本，点击更新" : "检查更新"}><Download className={props.checkingUpdate ? "spin" : ""} size={15} />{props.updateAvailable ? "下载更新" : "检查更新"}</button>
      </div>
      <div className="sidebar-heading">
        <div className="sidebar-heading-copy">
          <span>数据库资源</span>
          <small><i />{props.profiles.filter(item => item.connected).length} 个连接在线</small>
        </div>
        <button className="icon-button sidebar-add" onClick={props.onNewConnection} aria-label="新建连接" title="新建连接"><Plus size={17} /></button>
      </div>
      <label className="sidebar-search"><Search size={15} /><input value={filter} onChange={event => setFilter(event.target.value)} placeholder="筛选表名、连接或对象" /></label>

      <div className="sidebar-tree" onWheel={scrollTree}>
        {props.profiles.length === 0 && <div className="sidebar-empty"><Database size={27} /><strong>还没有数据库连接</strong><span>创建连接后从这里浏览对象</span><button className="button sidebar-primary" onClick={props.onNewConnection}><Plus size={15} />新建连接</button></div>}
        {props.profiles.map(profile => {
          const selected = profile.id === props.selectedProfileId;
          const schemas = props.schemasByProfile[profile.id] ?? [];
          const hidden = new Set(hiddenSchemas[profile.id] ?? []);
          const visibleSchemas = schemas.filter(schema => !hidden.has(schema));
          const tableResults = visibleSchemas.flatMap(schema => (props.objectsByKey[objectKey(profile.id, schema, "TABLE")] ?? [])
            .filter(object => object.name.toLowerCase().includes(needle)));
          const loadingTables = visibleSchemas.some(schema => props.loadingKeys.has(objectKey(profile.id, schema, "TABLE")));
          return <div className={`connection-node${selected ? " selected" : ""}`} key={profile.id}>
            <button
              className="connection-row"
              onClick={() => {
                if (selected) {
                    setExpandedProfiles(value => {
                    const next = new Set(value);
                    if (next.has(profile.id)) next.delete(profile.id); else next.add(profile.id);
                    return next;
                  });
                } else {
                  props.onSelectProfile(profile.id);
                  setExpandedProfiles(value => new Set(value).add(profile.id));
                }
              }}
              onDoubleClick={() => profile.connected ? props.onRefresh(profile) : props.onConnect(profile)}
              onContextMenu={event => openProfileContextMenu(event, profile)}
            >
              <span className={`connection-icon${profile.connected ? " online" : ""}`}><Database size={16} /></span>
              <span className="connection-copy"><span className="connection-name"><strong>{profile.name}</strong><em className={`database-type-badge ${profile.databaseType}`}>{databaseTypeLabel(profile.databaseType)}</em></span><small>{profile.username}@{profile.host}:{profile.port}</small></span>
              <span className={`connection-dot${profile.connected ? " online" : ""}`} />
            </button>

            {profile.connected && expandedProfiles.has(profile.id) && <div className="schema-list">
              <details className="schema-visibility" ref={schemaVisibilityRef}>
                <summary><ListFilter size={14} /><span>库展示</span><em>{visibleSchemas.length}/{schemas.length}</em></summary>
                <div className="schema-visibility-panel">
                  <div className="schema-visibility-actions">
                    <button type="button" onClick={() => updateHiddenSchemas(profile.id, [])}>全选</button>
                    <button type="button" onClick={() => updateHiddenSchemas(profile.id, schemas.filter(schema => !hidden.has(schema)))}>反选</button>
                  </div>
                  <div className="schema-visibility-options">
                    {schemas.map(schema => <label key={schema}>
                      <input
                        type="checkbox"
                        checked={!hidden.has(schema)}
                        onChange={() => updateHiddenSchemas(profile.id, hidden.has(schema)
                          ? [...hidden].filter(item => item !== schema)
                          : [...hidden, schema])}
                      />
                      <span>{schema}</span>
                    </label>)}
                  </div>
                </div>
              </details>
              {needle ? <div className="table-filter-results">
                <div className="table-filter-heading"><span>表筛选结果</span>{loadingTables ? <em>正在检索…</em> : <em>{tableResults.length} 个</em>}</div>
                {tableResults.map(object => <button key={`${object.schema}.${object.name}`} onContextMenu={event => openContextMenu(event, profile.id, object.schema, object)} onDoubleClick={() => props.onOpenObject(profile.id, object)} title="右键编辑，双击打开">
                  <Table2 size={13} /><span>{object.name}</span><small>{object.schema}</small>
                </button>)}
                {!loadingTables && tableResults.length === 0 && <span className="tree-message">没有匹配的表</span>}
              </div> : visibleSchemas.map(schema => <details className="schema-node" key={schema}>
                <summary><ChevronDown size={13} /><Layers3 size={14} /><span>{schema}</span></summary>
                <div className="category-list">
                  {OBJECT_KINDS.filter(item => profile.databaseType !== "mysql" || item.kind !== "SEQUENCE").map(({ kind, label, icon: KindIcon }) => {
                    const key = objectKey(profile.id, schema, kind);
                    const objects = props.objectsByKey[key];
                    const filtered = (objects ?? []).filter(object => !needle || object.name.toLowerCase().includes(needle));
                    return <details className="category-node" key={kind} onToggle={event => {
                      if (event.currentTarget.open && !objects) props.onLoadObjects(profile.id, schema, kind);
                    }}>
                      <summary onContextMenu={kind === "TABLE" ? event => openContextMenu(event, profile.id, schema) : undefined}><ChevronDown size={12} /><KindIcon size={13} /><span>{label}</span>{objects && <em>{filtered.length}</em>}</summary>
                      <div className="object-list">
                        {props.loadingKeys.has(key) && <span className="tree-message">正在加载…</span>}
                        {!props.loadingKeys.has(key) && objects && filtered.length === 0 && <span className="tree-message">没有匹配对象</span>}
                        {filtered.map(object => <button key={`${object.schema}.${object.name}`} onContextMenu={kind === "TABLE" ? event => openContextMenu(event, profile.id, schema, object) : undefined} onDoubleClick={() => props.onOpenObject(profile.id, object)} title="双击打开">
                          {kind === "TABLE" ? <Table2 size={12} /> : kind === "VIEW" ? <View size={12} /> : <Code2 size={12} />}
                          <span>{object.name}</span>
                        </button>)}
                      </div>
                    </details>;
                  })}
                </div>
              </details>)}
            </div>}
          </div>;
        })}
      </div>

      {contextMenu && <div className="tree-context-menu" style={{ left: contextMenu.x, top: contextMenu.y }} onMouseDown={event => event.stopPropagation()}>
        {contextMenu.profile
          ? <>
            <button onClick={() => { props.onEditConnection(contextMenu.profile!); setContextMenu(null); }}>编辑</button>
            <button onClick={() => { props.onCopyConnection(contextMenu.profile!); setContextMenu(null); }}><Copy size={12} />复制</button>
            <button onClick={() => { props.onDeleteConnection(contextMenu.profile!); setContextMenu(null); }}><Trash2 size={12} />删除</button>
            {contextMenu.profile.connected
              ? <><button onClick={() => { props.onRefresh(contextMenu.profile!); setContextMenu(null); }}><RefreshCw size={12} />刷新</button><button onClick={() => { props.onDisconnect(contextMenu.profile!); setContextMenu(null); }}><Unplug size={12} />断开</button></>
              : <button className="connect-action" onClick={() => { props.onConnect(contextMenu.profile!); setContextMenu(null); }}>连接</button>}
          </>
          : contextMenu.object
          ? <><button onClick={() => { props.onEditTable(contextMenu.profileId, contextMenu.object!); setContextMenu(null); }}>编辑表</button>{contextMenu.databaseType === "dm" && <button disabled={contextMenu.loadingLongRow || contextMenu.longRowStatusError} onClick={() => { props.onSetLongRow(contextMenu.profileId, contextMenu.object!, !contextMenu.longRowEnabled); setContextMenu(null); }}>{contextMenu.loadingLongRow ? "正在读取超长记录状态…" : contextMenu.longRowStatusError ? "无法读取超长记录状态" : contextMenu.longRowEnabled ? "关闭超长记录" : "启用超长记录"}</button>}</>
          : <button onClick={() => { props.onNewTable(contextMenu.profileId, contextMenu.schema); setContextMenu(null); }}>新建表</button>}
      </div>}

      <footer className="sidebar-footer">
        {active && <div className="active-connection"><span className={`connection-dot${active.connected ? " online" : ""}`} /><span>{active.name}</span><em className={`database-type-badge ${active.databaseType}`}>{databaseTypeLabel(active.databaseType)}</em><small>{active.connected ? "已连接" : "未连接"}</small></div>}
      </footer>
    </aside>
  );
}
