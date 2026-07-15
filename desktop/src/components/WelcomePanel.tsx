import { ArrowRight, Code2, Database, FileUp, Layers3, Plus, ShieldCheck } from "lucide-react";
import type { BootstrapData, ConnectionProfile } from "../types";

interface WelcomePanelProps {
  data: BootstrapData;
  onNewConnection: () => void;
  onNewSql: () => void;
  onImportDriver: () => void;
  onConnect: (profile: ConnectionProfile) => void;
}

export function WelcomePanel({ data, onNewConnection, onNewSql, onImportDriver, onConnect }: WelcomePanelProps) {
  const connected = data.profiles.filter(profile => profile.connected);
  return (
    <section className="welcome-panel">
      <div className="welcome-hero">
        <div>
          <span className="eyebrow"><span /> DM CONNECT 2.0</span>
          <h1>把数据库工作，<br /><em>变得清晰而高效。</em></h1>
          <p>连接达梦数据库、浏览对象、编写 SQL，并在独立事务会话中安全完成每一次操作。</p>
          <div className="hero-actions">
            <button className="button primary large" onClick={onNewConnection}><Plus size={17} />新建数据库连接</button>
            <button className="button secondary large" onClick={onNewSql}><Code2 size={17} />打开 SQL 工作台</button>
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

      <div className="welcome-lower">
        <section className="recent-connections">
          <header><div><h2>数据库连接</h2><p>快速继续最近的工作</p></div><button className="button text" onClick={onNewConnection}><Plus size={14} />新建</button></header>
          <div className="connection-cards">
            {data.profiles.slice(0, 5).map(profile => <button key={profile.id} onClick={() => onConnect(profile)}>
              <span className={`database-avatar${profile.connected ? " online" : ""}`}><Database size={18} /></span>
              <span><strong>{profile.name}</strong><small>{profile.username}@{profile.host}:{profile.port}</small></span>
              <em className={profile.connected ? "online" : ""}>{profile.connected ? "已连接" : "连接"}</em>
            </button>)}
            {data.profiles.length === 0 && <div className="connection-zero"><Database size={23} /><span><strong>尚未创建连接</strong><small>导入 JDBC 驱动后即可开始</small></span><button onClick={onImportDriver}><FileUp size={14} />导入驱动</button></div>}
          </div>
        </section>
        <aside className="security-card"><span><ShieldCheck size={22} /></span><h3>本地优先的安全设计</h3><p>密码与 SQL 历史由 Java 后端加密处理，React 页面无法直接访问文件系统或 JDBC 驱动。</p><div><i />Node.js 隔离<i />私有进程管道<i />内容安全策略</div></aside>
      </div>
    </section>
  );
}
