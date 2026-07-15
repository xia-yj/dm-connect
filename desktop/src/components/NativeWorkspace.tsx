import { useEffect, useState } from "react";
import { Database, Eye, Search, Terminal } from "lucide-react";
import { rpc, errorMessage } from "../api";
import type { ConnectionProfile } from "../types";

interface MongoPage {
  documents: unknown[];
  totalRows: number;
  page: number;
  pageSize: number;
}

interface RedisScanPage {
  keys: string[];
  cursor: string;
  finished: boolean;
}

interface RedisCommandResult {
  command: string;
  risk: string;
  dangerous: boolean;
  executed: boolean;
  requiresConfirmation: boolean;
  result?: unknown;
}

export function NativeWorkspace({ profile, namespaces }: { profile: ConnectionProfile; namespaces: string[] }) {
  const [database, setDatabase] = useState(namespaces[0] ?? profile.database ?? "");
  const [collections, setCollections] = useState<string[]>([]);
  const [collection, setCollection] = useState("");
  const [filter, setFilter] = useState("{}");
  const [mongoPage, setMongoPage] = useState(1);
  const [mongoTotal, setMongoTotal] = useState(0);
  const [output, setOutput] = useState("请选择资源后开始浏览。");
  const [command, setCommand] = useState("PING");
  const [document, setDocument] = useState("{\n  \n}");
  const [documentId, setDocumentId] = useState("");
  const [key, setKey] = useState("");
  const [pattern, setPattern] = useState("*");
  const [redisKeys, setRedisKeys] = useState<string[]>([]);
  const [scanStarts, setScanStarts] = useState<string[]>(["0"]);
  const [nextCursor, setNextCursor] = useState("0");
  const [scanFinished, setScanFinished] = useState(true);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setDatabase(namespaces[0] ?? profile.database ?? "");
    setCollections([]);
    setCollection("");
    setMongoPage(1);
    setMongoTotal(0);
    setOutput("请选择资源后开始浏览。");
    setDocumentId("");
    setKey("");
    setRedisKeys([]);
    setScanStarts(["0"]);
    setNextCursor("0");
    setScanFinished(true);
  }, [profile.id, namespaces]);

  useEffect(() => {
    if (profile.databaseType !== "mongo" || !database) return;
    let current = true;
    setCollections([]);
    setCollection("");
    setMongoPage(1);
    setMongoTotal(0);
    setOutput("正在读取集合…");
    void rpc<string[]>("mongo.collections", { profileId: profile.id, database })
      .then(value => { if (current) { setCollections(value); setCollection(value[0] ?? ""); setOutput(value.length ? "请选择集合后查询文档。" : "当前数据库没有集合。"); } })
      .catch(cause => { if (current) setOutput(errorMessage(cause)); });
    return () => { current = false; };
  }, [profile.id, profile.databaseType, database]);

  async function loadDocuments(page = mongoPage) {
    setBusy(true);
    try {
      const value = await rpc<MongoPage>("mongo.documents", { profileId: profile.id, database, collection, filter, page, pageSize: 50 });
      const lastPage = Math.max(1, Math.ceil(value.totalRows / value.pageSize));
      if (value.page > lastPage) {
        await loadDocuments(lastPage);
        return;
      }
      setMongoPage(value.page);
      setMongoTotal(value.totalRows);
      setOutput(JSON.stringify(value.documents, null, 2));
    } catch (cause) { setOutput(errorMessage(cause)); }
    finally { setBusy(false); }
  }

  async function runCommand() {
    if (busy) return;
    setBusy(true);
    try {
      let value = await rpc<RedisCommandResult>("redis.command", { profileId: profile.id, command });
      if (value.requiresConfirmation) {
        const label = value.risk === "admin" ? "管理命令" : value.risk === "blocking" ? "可能阻塞服务的命令" : "写命令";
        if (!window.confirm(`${label} ${value.command} 可能修改数据或影响服务，确认执行？`)) return;
        value = await rpc<RedisCommandResult>("redis.command", { profileId: profile.id, command, confirmed: true });
      }
      setOutput(JSON.stringify(value.result ?? value, null, 2));
    } catch (cause) { setOutput(errorMessage(cause)); }
    finally { setBusy(false); }
  }

  async function scan(cursor: string, starts: string[] = scanStarts) {
    if (busy) return;
    setBusy(true);
    try {
      const value = await rpc<RedisScanPage>("redis.keys", { profileId: profile.id, pattern, cursor, count: 200 });
      setRedisKeys(value.keys);
      setNextCursor(value.cursor);
      setScanFinished(value.finished);
      setScanStarts(starts);
      setOutput(`本页 ${value.keys.length} 个键${value.finished ? " · 已到末页" : ""}`);
    } catch (cause) { setOutput(errorMessage(cause)); }
    finally { setBusy(false); }
  }

  async function nextScanPage() {
    if (scanFinished) return;
    const starts = [...scanStarts, nextCursor];
    await scan(nextCursor, starts);
  }

  async function previousScanPage() {
    if (scanStarts.length <= 1) return;
    const starts = scanStarts.slice(0, -1);
    await scan(starts[starts.length - 1], starts);
  }

  async function viewKey(selected = key) {
    if (!selected || busy) return;
    setBusy(true);
    setKey(selected);
    try {
      const value = await rpc<unknown>("redis.key", { profileId: profile.id, key: selected });
      setOutput(JSON.stringify(value, null, 2));
    } catch (cause) { setOutput(errorMessage(cause)); }
    finally { setBusy(false); }
  }

  async function mutate(action: "mongo.insert" | "mongo.replace" | "mongo.delete") {
    if (busy || !window.confirm(action === "mongo.delete" ? "确认永久删除这条文档？" : "确认写入 MongoDB 文档？")) return;
    setBusy(true);
    try {
      const value = await rpc<unknown>(action, { profileId: profile.id, database, collection, id: documentId, document });
      setOutput(JSON.stringify(value, null, 2));
      await loadDocuments();
    } catch (cause) { setOutput(errorMessage(cause)); }
    finally { setBusy(false); }
  }

  if (profile.databaseType === "redis") return (
    <section className="native-workspace redis-workspace">
      <header className="redis-hero"><div className="redis-hero-icon"><Database size={22} /></div><div><div className="eyebrow">REDIS WORKSPACE <span>STANDALONE</span></div><h2>{profile.name}</h2><p>{profile.host}:{profile.port} · {namespaces[0] ?? "db0"}</p></div><div className="redis-hero-state"><i />已连接</div></header>
      <div className="redis-scanbar"><div className="redis-section-title"><Search size={16} /><div><strong>键空间</strong><small>使用 SCAN 安全分页，不阻塞服务</small></div></div><div className="redis-pattern"><input className="field-input mono" aria-label="键匹配模式" value={pattern} onChange={event => { setPattern(event.target.value); setRedisKeys([]); setScanStarts(["0"]); setNextCursor("0"); setScanFinished(true); }} placeholder="输入匹配模式，例如 user:*" /><button className="button primary" disabled={busy} onClick={() => void scan("0", ["0"])}>扫描键</button></div></div>
      <div className="redis-body"><section className="redis-panel redis-keys-panel"><div className="redis-panel-head"><div><strong>扫描结果</strong><small>{redisKeys.length ? `${redisKeys.length} 个键` : "尚未扫描"}</small></div><div className="redis-pager"><button className="button secondary compact" disabled={busy || scanStarts.length <= 1} onClick={() => void previousScanPage()}>上一页</button><button className="button secondary compact" disabled={busy || scanFinished} onClick={() => void nextScanPage()}>下一页</button></div></div><div className="native-key-list">{redisKeys.length ? redisKeys.map(item => <button className={item === key ? "selected" : ""} key={item} onClick={() => void viewKey(item)}>{item}</button>) : <div className="redis-empty"><Search size={24} /><span>输入模式并点击“扫描键”</span><small>支持通配符，例如 <code>cache:*</code></small></div>}</div></section><section className="redis-panel redis-inspector"><div className="redis-panel-head"><div><strong>键值查看器</strong><small>{key || "选择左侧键，查看 TTL 与值"}</small></div><button className="button secondary compact" disabled={busy || !key} onClick={() => void viewKey()}><Eye size={14} />刷新</button></div><div className="redis-key-input"><input className="field-input mono" placeholder="输入完整键名" value={key} onChange={event => setKey(event.target.value)} /><button className="button secondary" disabled={busy || !key} onClick={() => void viewKey()}>查看键</button></div><pre className="native-output">{output}</pre></section></div>
      <section className="redis-command-panel"><div className="redis-panel-head"><div><strong><Terminal size={15} />命令台</strong><small>只读命令直接执行，写入与管理命令需要确认</small></div><span className="redis-command-hint">Enter 执行</span></div><div className="redis-command-row"><span className="redis-prompt">$</span><input className="field-input mono" aria-label="Redis 命令" value={command} onChange={event => setCommand(event.target.value)} onKeyDown={event => { if (event.key === "Enter") void runCommand(); }} /><button className="button primary" disabled={busy || !command.trim()} onClick={() => void runCommand()}>执行命令</button></div></section>
    </section>
  );

  const lastMongoPage = Math.max(1, Math.ceil(mongoTotal / 50));
  return (
    <section className="native-workspace">
      <h2>MongoDB 工作台</h2>
      <div className="native-actions"><select className="field-input" value={database} onChange={event => setDatabase(event.target.value)}>{namespaces.map(item => <option key={item}>{item}</option>)}</select><select className="field-input" value={collection} onChange={event => { setCollection(event.target.value); setMongoPage(1); setMongoTotal(0); setOutput("请选择查询条件后查询文档。"); }}>{collections.map(item => <option key={item}>{item}</option>)}</select></div>
      <label>查询条件（Extended JSON）</label><textarea className="field-textarea mono" value={filter} onChange={event => setFilter(event.target.value)} />
      <div className="native-actions"><button className="button primary" disabled={busy || !collection} onClick={() => void loadDocuments(1)}>查询文档</button><button className="button secondary" disabled={busy || mongoPage <= 1} onClick={() => void loadDocuments(mongoPage - 1)}>上一页</button><button className="button secondary" disabled={busy || mongoPage >= lastMongoPage} onClick={() => void loadDocuments(mongoPage + 1)}>下一页</button><span>第 {mongoPage}/{lastMongoPage} 页 · {mongoTotal} 条</span></div>
      <label>文档 Extended JSON</label><textarea className="field-textarea mono" value={document} onChange={event => setDocument(event.target.value)} />
      <div className="native-actions"><input className="field-input mono" placeholder={'_id Extended JSON，例如 {"$oid":"…"} 或 "文本ID"'} value={documentId} onChange={event => setDocumentId(event.target.value)} /><button className="button secondary" disabled={busy || !collection} onClick={() => void mutate("mongo.insert")}>新增</button><button className="button secondary" disabled={busy || !collection || !documentId} onClick={() => void mutate("mongo.replace")}>更新</button><button className="button danger" disabled={busy || !collection || !documentId} onClick={() => void mutate("mongo.delete")}>删除</button></div>
      <pre className="native-output">{output}</pre>
    </section>
  );
}
