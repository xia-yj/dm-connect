import { useEffect, useState } from "react";
import { CheckCircle2, Clock3, LoaderCircle, Search, Trash2, XCircle } from "lucide-react";
import { errorMessage, rpc } from "../api";
import type { HistoryEntry } from "../types";
import { ConfirmModal, Modal } from "./Modal";

interface HistoryModalProps {
  onClose: () => void;
  onOpen: (entry: HistoryEntry) => void;
  onHistoryChanged?: () => void;
}

export function HistoryModal({ onClose, onOpen, onHistoryChanged }: HistoryModalProps) {
  const [items, setItems] = useState<HistoryEntry[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [filter, setFilter] = useState("");
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState("");
  const [confirmClear, setConfirmClear] = useState(false);

  async function load() {
    setBusy(true);
    try {
      const history = await rpc<HistoryEntry[]>("history.list");
      setItems(history);
      setSelectedId(value => value && history.some(item => item.id === value) ? value : history[0]?.id ?? null);
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  }

  useEffect(() => { void load(); }, []);
  const lowered = filter.trim().toLowerCase();
  const filtered = items.filter(item => !lowered || item.profileName.toLowerCase().includes(lowered) || item.sql.toLowerCase().includes(lowered));
  const selected = items.find(item => item.id === selectedId) ?? null;

  async function remove(entry: HistoryEntry) {
    await rpc("history.delete", { historyId: entry.id });
    onHistoryChanged?.();
    await load();
  }

  async function clear() {
    setConfirmClear(false);
    await rpc("history.clear");
    onHistoryChanged?.();
    await load();
  }

  return (
    <>
      <Modal title="SQL 执行历史" description="最近 1000 条记录在本地加密保存。" onClose={onClose} wide footer={
        <>
          <button className="button danger-text" disabled={items.length === 0} onClick={() => setConfirmClear(true)}><Trash2 size={15} />清空全部</button>
          <span className="modal-footer-spacer" />
          <button className="button secondary" onClick={onClose}>关闭</button>
          <button className="button primary" disabled={!selected} onClick={() => selected && onOpen(selected)}>在 SQL 标签中打开</button>
        </>
      }>
        <div className="history-layout">
          <div className="history-list-panel">
            <label className="history-search"><Search size={14} /><input value={filter} onChange={event => setFilter(event.target.value)} placeholder="搜索连接或 SQL" /></label>
            <div className="history-list">
              {busy && <div className="panel-loading"><LoaderCircle className="spin" size={20} />正在读取加密历史…</div>}
              {!busy && filtered.map(entry => <button key={entry.id} className={selectedId === entry.id ? "selected" : ""} onClick={() => setSelectedId(entry.id)}>
                <span className={`history-result ${entry.success ? "success" : "failed"}`}>{entry.success ? <CheckCircle2 size={14} /> : <XCircle size={14} />}</span>
                <span className="history-copy"><strong>{entry.profileName}</strong><small>{new Date(entry.executedAt).toLocaleString("zh-CN", { hour12: false })}</small><code>{entry.sql.replace(/\s+/g, " ").slice(0, 90)}</code></span>
                <span className="history-duration"><Clock3 size={11} />{entry.durationMillis} ms</span>
              </button>)}
              {!busy && filtered.length === 0 && <div className="history-empty">{error || "没有匹配的 SQL 历史"}</div>}
            </div>
          </div>
          <div className="history-preview">
            {selected ? <><header><div><strong>{selected.profileName}</strong><span>{selected.success ? "执行成功" : "执行失败"} · {selected.durationMillis} ms</span></div><button className="icon-button ghost danger-icon" onClick={() => remove(selected)} title="删除这条历史"><Trash2 size={16} /></button></header><pre><code>{selected.sql}</code></pre></> : <div className="history-empty">选择一条历史记录查看 SQL</div>}
          </div>
        </div>
      </Modal>
      {confirmClear && <ConfirmModal title="清空 SQL 历史" message="全部加密 SQL 历史将被永久删除，此操作不能撤销。" confirmText="清空全部" danger onCancel={() => setConfirmClear(false)} onConfirm={() => void clear()} />}
    </>
  );
}
