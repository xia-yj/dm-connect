import { ArrowDown, ArrowUp, LoaderCircle, Plus, Trash2 } from "lucide-react";
import { useMemo, useState } from "react";
import type { DatabaseObject, TableDefinitionDraft, TableDetails, TableColumnDraft } from "../types";
import { Modal } from "./Modal";

const TYPES: TableColumnDraft["type"][] = ["VARCHAR", "CHAR", "INT", "BIGINT", "DECIMAL", "DATE", "TIME", "TIMESTAMP", "CLOB", "BLOB"];
const lengthTypes = new Set<TableColumnDraft["type"]>(["VARCHAR", "CHAR", "DECIMAL", "TIME", "TIMESTAMP"]);

function newColumn(): TableColumnDraft {
  return { originalName: null, name: "", type: "VARCHAR", length: 100, scale: null, nullable: true, primaryKey: false, autoIncrement: false, defaultExpression: null, remark: null };
}

function fromDetails(object: DatabaseObject, details: TableDetails): TableDefinitionDraft {
  const primary = details.constraints.find(item => item.type === "PRIMARY KEY");
  const primaryNames = new Set(primary?.columns.map(name => name.toUpperCase()) ?? []);
  return {
    schema: object.schema,
    name: object.name,
    primaryKeyName: primary?.name ?? null,
    columns: details.columns.map(column => ({
      originalName: column.name, name: column.name, type: TYPES.includes(column.typeName.toUpperCase() as TableColumnDraft["type"])
        ? column.typeName.toUpperCase() as TableColumnDraft["type"] : "VARCHAR",
      length: ["VARCHAR", "CHAR", "DECIMAL"].includes(column.typeName.toUpperCase()) ? column.size || null
        : ["TIME", "TIMESTAMP"].includes(column.typeName.toUpperCase()) ? column.size || (column.typeName.toUpperCase() === "TIMESTAMP" ? 36 : 8) : null,
      scale: ["DECIMAL", "TIME", "TIMESTAMP"].includes(column.typeName.toUpperCase()) ? column.scale : null,
      nullable: column.nullable,
      primaryKey: primaryNames.has(column.name.toUpperCase()),
      autoIncrement: column.autoIncrement,
      defaultExpression: column.defaultValue,
      remark: column.remarks
    }))
  };
}

interface TableDesignerModalProps {
  object?: DatabaseObject;
  details?: TableDetails;
  schema: string;
  focusColumnName?: string;
  onClose: () => void;
  onCreate: (definition: TableDefinitionDraft) => Promise<void>;
  onAlter: (original: TableDefinitionDraft, target: TableDefinitionDraft) => Promise<void>;
}

export function TableDesignerModal({ object, details, schema, focusColumnName, onClose, onCreate, onAlter }: TableDesignerModalProps) {
  const editing = !!object;
  const initial = useMemo<TableDefinitionDraft>(() => object && details ? fromDetails(object, details) : {
    schema, name: "", primaryKeyName: null, columns: [{ ...newColumn(), name: "ID", type: "BIGINT", length: null, nullable: false, primaryKey: true, autoIncrement: true }]
  }, [object, details, schema]);
  const [definition, setDefinition] = useState<TableDefinitionDraft>(initial);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  function changeColumn(index: number, change: Partial<TableColumnDraft>) {
    setDefinition(current => ({ ...current, columns: current.columns.map((column, item) => item === index ? { ...column, ...change } : column) }));
  }
  function move(index: number, delta: number) {
    const destination = index + delta;
    if (destination < 0 || destination >= definition.columns.length) return;
    setDefinition(current => { const columns = [...current.columns]; [columns[index], columns[destination]] = [columns[destination], columns[index]]; return { ...current, columns }; });
  }
  function validate() {
    if (!definition.name.trim()) return "请输入表名";
    if (!definition.columns.length) return "表至少需要一个字段";
    const names = definition.columns.map(column => column.name.trim().toUpperCase());
    if (names.some(name => !name)) return "字段名不能为空";
    if (new Set(names).size !== names.length) return "字段名不能重复";
    if (definition.columns.some(column => ["VARCHAR", "CHAR", "DECIMAL"].includes(column.type) && (!column.length || column.length < 1))) return "字符类型和 DECIMAL 必须填写有效长度或精度";
    if (definition.columns.some(column => ["TIME", "TIMESTAMP"].includes(column.type) && (column.scale == null || column.scale < 0 || column.scale > 6))) return "TIME 和 TIMESTAMP 小数秒精度必须为 0 到 6";
    if (definition.columns.some(column => column.type === "DECIMAL" && column.scale != null && column.scale > (column.length ?? 0))) return "DECIMAL 小数位不能大于精度";
    return "";
  }
  async function submit() {
    const message = validate();
    if (message) { setError(message); return; }
    const removed = editing && initial.columns.some(old => !definition.columns.some(column => column.originalName === old.originalName));
    const modified = editing && initial.columns.some(old => {
      const next = definition.columns.find(column => column.originalName === old.originalName);
      return next && (next.type !== old.type || next.length !== old.length || next.scale !== old.scale || next.primaryKey !== old.primaryKey);
    });
    if ((removed || modified) && !window.confirm("该操作会执行达梦 DDL，可能影响已有数据，且无法自动回滚。是否继续？")) return;
    setBusy(true); setError("");
    try {
      const target = { ...definition, name: definition.name.trim(), columns: definition.columns.map(column => ({ ...column, name: column.name.trim(), defaultExpression: column.defaultExpression?.trim() || null, remark: column.remark?.trim() || null })) };
      if (editing) await onAlter(initial, target); else await onCreate(target);
    } catch (cause) { setError(cause instanceof Error ? cause.message : String(cause)); }
    finally { setBusy(false); }
  }
  return <Modal wide title={editing ? `编辑表 · ${object!.name}` : "新建表"} description={editing ? "使用达梦 ALTER TABLE 语法保存结构变更；DDL 会隐式提交。" : "将使用达梦 CREATE TABLE 语法创建数据表。"} onClose={busy ? undefined : onClose} footer={<><button className="button secondary" disabled={busy} onClick={onClose}>取消</button><button className="button primary" disabled={busy} onClick={() => void submit()}>{busy && <LoaderCircle className="spin" size={15} />}{editing ? "保存结构" : "创建表"}</button></>}>
    <div className="table-designer">
      <label className="field-label">所属模式<input className="field-input" value={definition.schema} readOnly /></label>
      <label className="field-label">表名<input className="field-input mono" autoFocus={!editing} value={definition.name} readOnly={editing} onChange={event => setDefinition({ ...definition, name: event.target.value })} /></label>
      <section className="table-columns-section"><header><div><strong>字段定义</strong><small>仅支持达梦数据库字段类型和 DDL。</small></div><button className="button secondary compact" onClick={() => setDefinition(current => ({ ...current, columns: [...current.columns, newColumn()] }))}><Plus size={15} />添加字段</button></header>
        <div className="table-columns-grid table-columns-heading"><span>字段名</span><span>类型</span><span>长度/精度</span><span>小数位</span><span>默认值</span><span>备注</span><span>可空</span><span>主键</span><span>自增</span><span>操作</span></div>
        {definition.columns.map((column, index) => <div className="table-columns-grid" key={`${column.originalName ?? "new"}-${index}`}>
          <input className="field-input mono" autoFocus={editing && column.name === focusColumnName} value={column.name} onChange={event => changeColumn(index, { name: event.target.value })} />
          <select className="field-input" value={column.type} onChange={event => { const type = event.target.value as TableColumnDraft["type"]; changeColumn(index, { type, length: ["VARCHAR", "CHAR"].includes(type) ? (column.length ?? 100) : type === "DECIMAL" ? (column.length ?? 18) : type === "TIMESTAMP" ? 36 : type === "TIME" ? 8 : null, scale: ["DECIMAL", "TIME", "TIMESTAMP"].includes(type) ? (column.scale ?? 0) : null, autoIncrement: ["INT", "BIGINT"].includes(type) ? column.autoIncrement : false }); }}>{TYPES.map(type => <option key={type}>{type}</option>)}</select>
          <input aria-label={`${column.name || index + 1}长度`} className="field-input" type="number" min="1" disabled={!lengthTypes.has(column.type) || ["TIME", "TIMESTAMP"].includes(column.type)} value={column.length ?? ""} onChange={event => changeColumn(index, { length: event.target.value === "" ? null : Number(event.target.value) })} />
          <input aria-label={`${column.name || index + 1}小数位`} className="field-input" type="number" min="0" max={["TIME", "TIMESTAMP"].includes(column.type) ? "6" : undefined} disabled={!(["DECIMAL", "TIME", "TIMESTAMP"].includes(column.type))} value={column.scale ?? ""} onChange={event => changeColumn(index, { scale: event.target.value ? Number(event.target.value) : 0 })} />
          <input aria-label={`${column.name || index + 1}默认值`} className="field-input mono" value={column.defaultExpression ?? ""} onChange={event => changeColumn(index, { defaultExpression: event.target.value || null })} placeholder="例如 0 或 '文本'" />
          <input aria-label={`${column.name || index + 1}备注`} className="field-input" value={column.remark ?? ""} onChange={event => changeColumn(index, { remark: event.target.value || null })} placeholder="中文备注" />
          <input aria-label={`${column.name || index + 1}可空`} type="checkbox" checked={column.primaryKey ? false : column.nullable} disabled={column.primaryKey} onChange={event => changeColumn(index, { nullable: event.target.checked })} />
          <input aria-label={`${column.name || index + 1}主键`} type="checkbox" checked={column.primaryKey} onChange={event => changeColumn(index, { primaryKey: event.target.checked, nullable: event.target.checked ? false : column.nullable })} />
          <input aria-label={`${column.name || index + 1}自增`} type="checkbox" checked={column.autoIncrement} disabled={!(["INT", "BIGINT"].includes(column.type)) || !!column.originalName} onChange={event => changeColumn(index, { autoIncrement: event.target.checked })} />
          <span className="table-column-actions"><button className="icon-button ghost" title="上移" onClick={() => move(index, -1)} disabled={index === 0}><ArrowUp size={15} /></button><button className="icon-button ghost" title="下移" onClick={() => move(index, 1)} disabled={index === definition.columns.length - 1}><ArrowDown size={15} /></button><button className="icon-button ghost danger-text" title="删除字段" onClick={() => setDefinition(current => ({ ...current, columns: current.columns.filter((_, item) => item !== index) }))} disabled={definition.columns.length === 1}><Trash2 size={15} /></button></span>
        </div>)}
      </section>
      {editing && <p className="table-designer-warning">删列、修改字段类型或主键可能影响已有数据，且达梦 DDL 执行后无法自动回滚。</p>}
      {error && <div className="form-error">{error}</div>}
    </div>
  </Modal>;
}
