import { useEffect, useState } from "react";
import { ArrowRight, CheckCircle2, Clock3, Code2, Database, History, Layers3, LoaderCircle, Plus, ShieldCheck, XCircle } from "lucide-react";
import { errorMessage, rpc } from "../api";
import type { BootstrapData, HistoryEntry } from "../types";

interface WelcomePanelProps {
  data: BootstrapData;
  showSqlAction?: boolean;
  onNewConnection: () => void;
  onNewSql: () => void;
  onImportDriver: () => void;
  onShowHistory: () => void;
  onOpenHistory: (entry: HistoryEntry) => void;
  historyRevision: number;
}

export function WelcomePanel({ data, showSqlAction = true, onNewConnection, onNewSql, onImportDriver, onShowHistory, onOpenHistory, historyRevision }: WelcomePanelProps) {
  const connected = data.profiles.filter(profile => profile.connected);
  const [history, setHistory] = useState<HistoryEntry[]>([]);
  const [historyBusy, setHistoryBusy] = useState(true);
  const [historyError, setHistoryError] = useState("");

  useEffect(() => {
    let active = true;
    async function loadHistory() {
      try {
        const items = await rpc<HistoryEntry[]>("history.list");
        if (active) { setHistory(items.slice(0, 6)); setHistoryError(""); }
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
  return (
    <section className="welcome-panel">
      <div className="welcome-hero">
        <div>
          <span className="eyebrow"><span /> 数据库连接工具 2.0</span>
          <h1>把数据库工作，<br /><em>变得清晰而高效。</em></h1>
          <p>统一管理关系型与原生数据库连接，浏览数据对象，并在对应工作台中安全完成操作。</p>
          <div className="hero-actions">
            <button className="button primary large" onClick={onNewConnection}><Plus size={17} />新建数据库连接</button>
            {showSqlAction && <button className="button secondary large" onClick={onNewSql}><Code2 size={17} />打开 SQL 工作台</button>}
          </div>
        </div>
        <div className="hero-visual" aria-hidden="true">
          <div className="visual-orbit orbit-one" />
          <div className="visual-orbit orbit-two" />
          <span className="visual-core"><Database size={38} /></span>
          <span className="visual-node node-a"><Layers3 size={18} /></span>
          <span className="visual-node node-b"><Code2 size={18} /></span>
          <span className="visual-node node-c"><ShieldCheck size={18} /></span>
        </div>
      </div>

      <div className="dashboard-grid">
        <article className="metric-card"><span className="metric-icon blue"><Database size={18} /></span><div><strong>{data.profiles.length}</strong><span>数据库连接</span></div><small>{connected.length} 个在线</small></article>
        <article className="metric-card"><span className="metric-icon violet"><Layers3 size={18} /></span><div><strong>{data.drivers.length}</strong><span>JDBC 驱动</span></div><button onClick={onImportDriver}>导入 <ArrowRight size={12} /></button></article>
        <article className="metric-card"><span className="metric-icon green"><ShieldCheck size={18} /></span><div><strong>AES-256</strong><span>本地加密存储</span></div><small>自动就绪</small></article>
      </div>

      <section className="recent-sql">
        <header>
          <div><h2>最近 SQL</h2><p>快速恢复最近执行的数据库工作</p></div>
          <button className="button text" onClick={onShowHistory}><History size={15} />查看全部</button>
        </header>
        <div className="recent-sql-list">
          {historyBusy && <div className="recent-sql-empty"><LoaderCircle className="spin" size={18} />正在读取 SQL 历史…</div>}
          {!historyBusy && history.map(entry => <button key={entry.id} onClick={() => onOpenHistory(entry)} title="在 SQL 标签中打开">
            <span className={`recent-sql-result${entry.success ? " success" : " failed"}`}>{entry.success ? <CheckCircle2 size={17} /> : <XCircle size={17} />}</span>
            <span className="recent-sql-copy"><strong>{entry.profileName}</strong><code>{entry.sql.replace(/\s+/g, " ").slice(0, 110)}</code></span>
            <span className="recent-sql-meta"><time>{new Date(entry.executedAt).toLocaleString("zh-CN", { hour12: false })}</time><small><Clock3 size={11} />{entry.durationMillis} ms</small></span>
          </button>)}
          {!historyBusy && history.length === 0 && <div className="recent-sql-empty"><History size={21} /><span>{historyError || "还没有 SQL 执行历史"}</span><small>执行 SQL 后，可以从这里快速恢复工作</small></div>}
        </div>
      </section>
    </section>
  );
}
