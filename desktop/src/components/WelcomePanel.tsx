import { useEffect, useState } from "react";
import { CheckCircle2, Clock3, Code2, Database, History, LoaderCircle, Plus, XCircle } from "lucide-react";
import { errorMessage, rpc } from "../api";
import { connectionSummary, databaseTypeLabel, isJdbcDatabase } from "../database";
import type { BootstrapData, ConnectionProfile, HistoryEntry } from "../types";

interface WelcomePanelProps {
  data: BootstrapData;
  selectedProfileId: string | null;
  showSqlAction?: boolean;
  onSelectProfile: (profileId: string) => void;
  onConnect: (profile: ConnectionProfile) => void;
  onNewConnection: () => void;
  onNewSql: (profileId?: string) => void;
  onImportDriver: () => void;
  onShowHistory: () => void;
  onOpenHistory: (entry: HistoryEntry) => void;
  historyRevision: number;
}

export function WelcomePanel({ data, selectedProfileId, showSqlAction = true, onSelectProfile, onConnect, onNewConnection, onNewSql, onImportDriver, onShowHistory, onOpenHistory, historyRevision }: WelcomePanelProps) {
  const connected = data.profiles.filter(profile => profile.connected);
  const hasConnectedJdbc = connected.some(profile => isJdbcDatabase(profile.databaseType));
  const [history, setHistory] = useState<HistoryEntry[]>([]);
  const [historyBusy, setHistoryBusy] = useState(true);
  const [historyError, setHistoryError] = useState("");

  useEffect(() => {
    let active = true;
    async function loadHistory() {
      try {
        const items = await rpc<HistoryEntry[]>("history.list");
        if (active) { setHistory(items.slice(0, 8)); setHistoryError(""); }
      } catch (cause) {
        if (active) setHistoryError(errorMessage(cause));
      } finally {
        if (active) setHistoryBusy(false);
      }
    }
    void loadHistory();
    window.addEventListener("focus", loadHistory);
    return () => { active = false; window.removeEventListener("focus", loadHistory); };
  }, [historyRevision]);

  function profileAction(profile: ConnectionProfile) {
    if (!profile.connected) return <button className="button compact" onClick={event => { event.stopPropagation(); onConnect(profile); }}>连接</button>;
    if (isJdbcDatabase(profile.databaseType)) return <button className="button compact" onClick={event => { event.stopPropagation(); onNewSql(profile.id); }}><Code2 size={13} />新建 SQL</button>;
    return <button className="button compact" onClick={event => { event.stopPropagation(); onSelectProfile(profile.id); }}>打开</button>;
  }

  return (
    <section className="overview">
      <div className="overview-inner">
        <header className="overview-header">
          <div>
            <h1>概览</h1>
            <p>管理数据库连接，恢复最近的 SQL 工作</p>
          </div>
          <div className="overview-actions">
            {showSqlAction && <button className="button" onClick={() => onNewSql()} disabled={!hasConnectedJdbc}><Code2 size={14} />新建 SQL</button>}
            <button className="button primary" onClick={onNewConnection}><Plus size={14} />新建连接</button>
          </div>
        </header>

        <div className="overview-stats">
          <div className="overview-stat"><span>数据库连接</span><strong>{data.profiles.length}</strong></div>
          <div className="overview-stat"><span>在线</span><strong>{connected.length}</strong></div>
          <div className="overview-stat"><span>JDBC 驱动</span><strong>{data.drivers.length}<button onClick={onImportDriver}>导入</button></strong></div>
          <div className="overview-stat"><span>本地存储</span><strong>AES-256</strong></div>
        </div>

        <section className="overview-section">
          <header><h2>连接<small>{data.profiles.length}</small></h2></header>
          {data.profiles.length === 0
            ? <div className="overview-empty"><Database size={22} /><strong>还没有数据库连接</strong><span>创建连接后，可以在左侧浏览库表对象</span><button className="button primary compact" onClick={onNewConnection}><Plus size={13} />新建连接</button></div>
            : <table className="overview-table">
              <colgroup><col /><col className="col-type" /><col /><col className="col-state" /><col className="col-action" /></colgroup>
              <thead><tr><th>名称</th><th>类型</th><th>地址</th><th>状态</th><th /></tr></thead>
              <tbody>
                {data.profiles.map(profile => <tr key={profile.id} className={profile.id === selectedProfileId ? "selected" : ""} onClick={() => onSelectProfile(profile.id)} onDoubleClick={() => { if (!profile.connected) onConnect(profile); }}>
                  <td><span className="cell-name"><i className={`connection-dot${profile.connected ? " online" : ""}`} /><span title={profile.name}>{profile.name}</span></span></td>
                  <td><em className={`database-type-badge ${profile.databaseType}`}>{databaseTypeLabel(profile.databaseType)}</em></td>
                  <td className="cell-address" title={connectionSummary(profile)}>{connectionSummary(profile)}</td>
                  <td className={`cell-state${profile.connected ? " online" : ""}`}>{profile.connected ? "已连接" : "未连接"}</td>
                  <td className="col-action">{profileAction(profile)}</td>
                </tr>)}
              </tbody>
            </table>}
        </section>

        <section className="overview-section">
          <header><h2>最近 SQL</h2><button className="button text" onClick={onShowHistory}><History size={14} />查看全部</button></header>
          <div className="recent-sql-list">
            {historyBusy && <div className="recent-sql-empty"><LoaderCircle className="spin" size={15} />正在读取 SQL 历史…</div>}
            {!historyBusy && history.map(entry => <button key={entry.id} onClick={() => onOpenHistory(entry)} title="在 SQL 标签中打开">
              <span className={`recent-sql-result${entry.success ? " success" : " failed"}`}>{entry.success ? <CheckCircle2 size={15} /> : <XCircle size={15} />}</span>
              <strong>{entry.profileName}</strong>
              <code>{entry.sql.replace(/\s+/g, " ").slice(0, 160)}</code>
              <time>{new Date(entry.executedAt).toLocaleString("zh-CN", { hour12: false })}</time>
              <small><Clock3 size={11} />{entry.durationMillis} ms</small>
            </button>)}
            {!historyBusy && history.length === 0 && <div className="recent-sql-empty">{historyError || "还没有 SQL 执行历史，执行 SQL 后会显示在这里"}</div>}
          </div>
        </section>
      </div>
    </section>
  );
}
