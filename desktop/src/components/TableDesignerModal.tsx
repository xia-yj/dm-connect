import { ArrowDown, ArrowUp, LoaderCircle, Plus, Trash2 } from "lucide-react";
import { useMemo, useState } from "react";
import type { DatabaseObject, DatabaseType, TableDefinitionDraft, TableDetails, TableColumnDraft } from "../types";
import { Modal } from "./Modal";

interface TypeGroup {
  label: string;
  types: readonly string[];
}

interface TypeRules {
  label: string;
  schemaLabel: string;
  groups: readonly TypeGroup[];
  types: ReadonlySet<string>;
  lengthTypes: ReadonlySet<string>;
  precisionTypes: ReadonlySet<string>;
  timeScaleTypes: ReadonlySet<string>;
  autoIncrementTypes: ReadonlySet<string>;
}

function rules(
  label: string,
  schemaLabel: string,
  groups: readonly TypeGroup[],
  lengthTypes: readonly string[],
  precisionTypes: readonly string[],
  timeScaleTypes: readonly string[],
  autoIncrementTypes: readonly string[]
): TypeRules {
  return {
    label,
    schemaLabel,
    groups,
    types: new Set(groups.flatMap(group => group.types)),
    lengthTypes: new Set(lengthTypes),
    precisionTypes: new Set(precisionTypes),
    timeScaleTypes: new Set(timeScaleTypes),
    autoIncrementTypes: new Set(autoIncrementTypes)
  };
}

const DM_TYPE_RULES = rules("达梦", "所属模式", [
  { label: "字符", types: ["CHAR", "VARCHAR"] },
  { label: "精确数值", types: ["TINYINT", "BYTE", "SMALLINT", "INT", "INTEGER", "BIGINT", "NUMERIC", "DECIMAL", "DEC", "NUMBER"] },
  { label: "近似数值", types: ["REAL", "FLOAT", "DOUBLE", "DOUBLE PRECISION"] },
  { label: "二进制与位串", types: ["BIT", "BINARY", "VARBINARY"] },
  { label: "日期时间", types: ["DATE", "TIME", "TIMESTAMP", "DATETIME", "TIME WITH TIME ZONE", "TIMESTAMP WITH TIME ZONE"] },
  { label: "时间间隔", types: ["INTERVAL YEAR", "INTERVAL MONTH", "INTERVAL YEAR TO MONTH", "INTERVAL DAY", "INTERVAL HOUR", "INTERVAL MINUTE", "INTERVAL SECOND", "INTERVAL DAY TO HOUR", "INTERVAL DAY TO MINUTE", "INTERVAL DAY TO SECOND", "INTERVAL HOUR TO MINUTE", "INTERVAL HOUR TO SECOND", "INTERVAL MINUTE TO SECOND"] },
  { label: "多媒体与大对象", types: ["TEXT", "LONG", "LONGVARCHAR", "IMAGE", "LONGVARBINARY", "BLOB", "CLOB", "BFILE"] }
], ["CHAR", "VARCHAR", "BINARY", "VARBINARY"], ["NUMERIC", "DECIMAL", "DEC", "NUMBER"], ["TIME", "TIMESTAMP", "TIME WITH TIME ZONE", "TIMESTAMP WITH TIME ZONE"], ["INT", "BIGINT"]);

const MYSQL_TYPE_RULES = rules("MySQL", "所属数据库", [
  { label: "整数", types: ["TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER", "BIGINT"] },
  { label: "精确数值", types: ["DECIMAL", "DEC", "NUMERIC", "FIXED"] },
  { label: "近似数值", types: ["FLOAT", "DOUBLE", "DOUBLE PRECISION", "REAL"] },
  { label: "位与布尔", types: ["BIT", "BOOL", "BOOLEAN"] },
  { label: "字符", types: ["CHAR", "VARCHAR"] },
  { label: "二进制", types: ["BINARY", "VARBINARY"] },
  { label: "日期时间", types: ["DATE", "TIME", "DATETIME", "TIMESTAMP", "YEAR"] },
  { label: "文本", types: ["TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT"] },
  { label: "二进制大对象", types: ["TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB"] },
  { label: "JSON", types: ["JSON"] },
  { label: "空间数据", types: ["GEOMETRY", "POINT", "LINESTRING", "POLYGON", "MULTIPOINT", "MULTILINESTRING", "MULTIPOLYGON", "GEOMETRYCOLLECTION"] }
], ["CHAR", "VARCHAR", "BINARY", "VARBINARY", "BIT"], ["DECIMAL", "DEC", "NUMERIC", "FIXED"], ["TIME", "DATETIME", "TIMESTAMP"], ["TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER", "BIGINT"]);

function typeRules(databaseType: DatabaseType): TypeRules {
  return databaseType === "mysql" ? MYSQL_TYPE_RULES : DM_TYPE_RULES;
}

export function supportedTableTypes(databaseType: DatabaseType): ReadonlySet<string> {
  return typeRules(databaseType).types;
}

function maxLength(databaseType: DatabaseType, type: string): number {
  if (databaseType === "dm") return DM_TYPE_RULES.precisionTypes.has(type) ? 38 : 32767;
  if (MYSQL_TYPE_RULES.precisionTypes.has(type)) return 65;
  if (type === "BIT") return 64;
  if (type === "CHAR" || type === "BINARY") return 255;
  return 65535;
}

function maxScale(databaseType: DatabaseType, column: TableColumnDraft): number {
  if (typeRules(databaseType).timeScaleTypes.has(column.type)) return 6;
  const precision = column.length ?? maxLength(databaseType, column.type);
  return databaseType === "mysql" ? Math.min(30, precision) : precision;
}

function newColumn(): TableColumnDraft {
  return { originalName: null, name: "", type: "VARCHAR", length: 100, scale: null, nullable: true, primaryKey: false, autoIncrement: false, defaultExpression: null, onUpdateExpression: null, remark: null };
}

function fromDetails(databaseType: DatabaseType, object: DatabaseObject, details: TableDetails): TableDefinitionDraft {
  const config = typeRules(databaseType);
  const primary = details.constraints.find(item => item.type === "PRIMARY KEY");
  const primaryNames = new Set(primary?.columns.map(name => name.toUpperCase()) ?? []);
  return {
    schema: object.schema,
    name: object.name,
    primaryKeyName: primary?.name ?? null,
    columns: details.columns.map(column => {
      const type = column.typeName.toUpperCase();
      const supportedType = config.types.has(type) ? type : "VARCHAR";
      return {
        originalName: column.name,
        name: column.name,
        type: supportedType,
        length: config.lengthTypes.has(supportedType) || config.precisionTypes.has(supportedType) ? column.size || null : null,
        scale: config.precisionTypes.has(supportedType) || config.timeScaleTypes.has(supportedType) ? column.scale : null,
        nullable: column.nullable,
        primaryKey: primaryNames.has(column.name.toUpperCase()),
        autoIncrement: column.autoIncrement,
        defaultExpression: column.defaultValue,
        onUpdateExpression: column.onUpdateExpression,
        remark: column.remarks
      };
    })
  };
}

interface TableDesignerModalProps {
  databaseType: DatabaseType;
  object?: DatabaseObject;
  details?: TableDetails;
  schema: string;
  focusColumnName?: string;
  onClose: () => void;
  onCreate: (definition: TableDefinitionDraft) => Promise<void>;
  onAlter: (original: TableDefinitionDraft, target: TableDefinitionDraft) => Promise<void>;
}

export function TableDesignerModal({ databaseType, object, details, schema, focusColumnName, onClose, onCreate, onAlter }: TableDesignerModalProps) {
  const config = typeRules(databaseType);
  const editing = !!object;
  const initial = useMemo<TableDefinitionDraft>(() => object && details ? fromDetails(databaseType, object, details) : {
    schema, name: "", primaryKeyName: null, columns: [{ ...newColumn(), name: "ID", type: "BIGINT", length: null, nullable: false, primaryKey: true, autoIncrement: true }]
  }, [databaseType, object, details, schema]);
  const [definition, setDefinition] = useState<TableDefinitionDraft>(initial);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  function changeColumn(index: number, change: Partial<TableColumnDraft>) {
    setDefinition(current => ({ ...current, columns: current.columns.map((column, item) => item === index ? { ...column, ...change } : column) }));
  }

  function changeColumnType(index: number, column: TableColumnDraft, type: string) {
    const length = config.lengthTypes.has(type) ? (type === "BIT" ? column.length ?? 1 : column.length ?? 100)
      : config.precisionTypes.has(type) ? column.length ?? 18 : null;
    const scale = config.precisionTypes.has(type) || config.timeScaleTypes.has(type) ? column.scale ?? 0 : null;
    changeColumn(index, { type, length, scale, autoIncrement: config.autoIncrementTypes.has(type) ? column.autoIncrement : false,
      onUpdateExpression: databaseType === "mysql" && (type === "DATETIME" || type === "TIMESTAMP") ? column.onUpdateExpression : null });
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
    for (const column of definition.columns) {
      if (!config.types.has(column.type)) return `不支持的 ${config.label} 字段类型：${column.type}`;
      const lengthRequired = config.precisionTypes.has(column.type) || config.lengthTypes.has(column.type);
      if (lengthRequired
        && (!column.length || column.length < 1 || column.length > maxLength(databaseType, column.type))) {
        return `${column.type} 的长度或精度必须为 1 到 ${maxLength(databaseType, column.type)}`;
      }
      if (column.length != null && config.lengthTypes.has(column.type) && (column.length < 1 || column.length > maxLength(databaseType, column.type))) {
        return `${column.type} 的长度必须为 1 到 ${maxLength(databaseType, column.type)}`;
      }
      const scaleRequired = databaseType === "dm" && config.timeScaleTypes.has(column.type);
      if ((scaleRequired && column.scale == null) || (column.scale != null
        && (config.precisionTypes.has(column.type) || config.timeScaleTypes.has(column.type))
        && (column.scale < 0 || column.scale > maxScale(databaseType, column)))) {
        return `${column.type} 的小数位必须为 0 到 ${maxScale(databaseType, column)}`;
      }
      if (column.autoIncrement && !config.autoIncrementTypes.has(column.type)) return `${config.label} 的 ${column.type} 类型不支持自增`;
      if (databaseType === "mysql" && column.autoIncrement && !column.primaryKey) return "MySQL 自增字段必须同时设为主键";
      if (databaseType === "mysql" && column.onUpdateExpression
        && (!(column.type === "DATETIME" || column.type === "TIMESTAMP")
          || !/^CURRENT_TIMESTAMP(?:\([0-6]?\))?$/i.test(column.onUpdateExpression.trim()))) {
        return "ON UPDATE 仅支持 DATETIME/TIMESTAMP 的 CURRENT_TIMESTAMP 及 0 到 6 位精度";
      }
    }
    return "";
  }

  async function submit() {
    const message = validate();
    if (message) { setError(message); return; }
    const removed = editing && initial.columns.some(old => !definition.columns.some(column => column.originalName === old.originalName));
    const modified = editing && initial.columns.some(old => {
      const next = definition.columns.find(column => column.originalName === old.originalName);
      return next && (next.type !== old.type || next.length !== old.length || next.scale !== old.scale
        || next.primaryKey !== old.primaryKey || next.onUpdateExpression !== old.onUpdateExpression);
    });
    if ((removed || modified) && !window.confirm(`该操作会执行 ${config.label} DDL，可能影响已有数据，且无法自动回滚。是否继续？`)) return;
    setBusy(true); setError("");
    try {
      const target = { ...definition, name: definition.name.trim(), columns: definition.columns.map(column => ({ ...column, name: column.name.trim(), defaultExpression: column.defaultExpression?.trim() || null, onUpdateExpression: column.onUpdateExpression?.trim() || null, remark: column.remark?.trim() || null })) };
      if (editing) await onAlter(initial, target); else await onCreate(target);
    } catch (cause) { setError(cause instanceof Error ? cause.message : String(cause)); }
    finally { setBusy(false); }
  }

  return <Modal wide title={editing ? `编辑表 · ${object!.name}` : "新建表"} description={editing ? `使用 ${config.label} ALTER TABLE 语法保存结构变更；DDL 会隐式提交。` : `将使用 ${config.label} CREATE TABLE 语法创建数据表。`} onClose={busy ? undefined : onClose} footer={<><button className="button secondary" disabled={busy} onClick={onClose}>取消</button><button className="button primary" disabled={busy} onClick={() => void submit()}>{busy && <LoaderCircle className="spin" size={15} />}{editing ? "保存结构" : "创建表"}</button></>}>
    <div className="table-designer">
      <label className="field-label">{config.schemaLabel}<input className="field-input" value={definition.schema} readOnly /></label>
      <label className="field-label">表名<input className="field-input mono" autoFocus={!editing} value={definition.name} readOnly={editing} onChange={event => setDefinition({ ...definition, name: event.target.value })} /></label>
      <section className="table-columns-section"><header><div><strong>字段定义</strong><small>当前按 {config.label} 数据类型和参数规则校验。</small></div><button className="button secondary compact" onClick={() => setDefinition(current => ({ ...current, columns: [...current.columns, newColumn()] }))}><Plus size={15} />添加字段</button></header>
        <div className="table-columns-grid table-columns-heading"><span>字段名</span><span>类型</span><span>长度/精度</span><span>小数位</span><span>默认值</span><span>自动更新</span><span>备注</span><span>可空</span><span>主键</span><span>自增</span><span>操作</span></div>
        {definition.columns.map((column, index) => <div className="table-columns-grid" key={`${column.originalName ?? "new"}-${index}`}>
          <input className="field-input mono" autoFocus={editing && column.name === focusColumnName} value={column.name} onChange={event => changeColumn(index, { name: event.target.value })} />
          <select className="field-input" value={column.type} onChange={event => changeColumnType(index, column, event.target.value)}>{config.groups.map(group => <optgroup key={group.label} label={group.label}>{group.types.map(type => <option key={type}>{type}</option>)}</optgroup>)}</select>
          <input aria-label={`${column.name || index + 1}长度`} className="field-input" type="number" min="1" max={config.lengthTypes.has(column.type) || config.precisionTypes.has(column.type) ? maxLength(databaseType, column.type) : undefined} disabled={!(config.lengthTypes.has(column.type) || config.precisionTypes.has(column.type))} value={column.length ?? ""} onChange={event => changeColumn(index, { length: event.target.value === "" ? null : Number(event.target.value) })} />
          <input aria-label={`${column.name || index + 1}小数位`} className="field-input" type="number" min="0" max={config.precisionTypes.has(column.type) || config.timeScaleTypes.has(column.type) ? maxScale(databaseType, column) : undefined} disabled={!(config.precisionTypes.has(column.type) || config.timeScaleTypes.has(column.type))} value={column.scale ?? ""} onChange={event => changeColumn(index, { scale: event.target.value === "" ? null : Number(event.target.value) })} />
          <input aria-label={`${column.name || index + 1}默认值`} className="field-input mono" value={column.defaultExpression ?? ""} onChange={event => changeColumn(index, { defaultExpression: event.target.value || null })} placeholder="例如 0 或 '文本'" />
          <input aria-label={`${column.name || index + 1}自动更新`} className="field-input mono" value={column.onUpdateExpression ?? ""} disabled={databaseType !== "mysql" || !(column.type === "DATETIME" || column.type === "TIMESTAMP")} onChange={event => changeColumn(index, { onUpdateExpression: event.target.value || null })} placeholder="CURRENT_TIMESTAMP" />
          <input aria-label={`${column.name || index + 1}备注`} className="field-input" value={column.remark ?? ""} onChange={event => changeColumn(index, { remark: event.target.value || null })} placeholder="中文备注" />
          <input aria-label={`${column.name || index + 1}可空`} type="checkbox" checked={column.primaryKey ? false : column.nullable} disabled={column.primaryKey} onChange={event => changeColumn(index, { nullable: event.target.checked })} />
          <input aria-label={`${column.name || index + 1}主键`} type="checkbox" checked={column.primaryKey} onChange={event => changeColumn(index, { primaryKey: event.target.checked, nullable: event.target.checked ? false : column.nullable })} />
          <input aria-label={`${column.name || index + 1}自增`} type="checkbox" checked={column.autoIncrement} disabled={!config.autoIncrementTypes.has(column.type) || !!column.originalName} onChange={event => changeColumn(index, databaseType === "mysql" && event.target.checked ? { autoIncrement: true, primaryKey: true, nullable: false } : { autoIncrement: event.target.checked })} />
          <span className="table-column-actions"><button className="icon-button ghost" title="上移" onClick={() => move(index, -1)} disabled={index === 0}><ArrowUp size={15} /></button><button className="icon-button ghost" title="下移" onClick={() => move(index, 1)} disabled={index === definition.columns.length - 1}><ArrowDown size={15} /></button><button className="icon-button ghost danger-text" title="删除字段" onClick={() => setDefinition(current => ({ ...current, columns: current.columns.filter((_, item) => item !== index) }))} disabled={definition.columns.length === 1}><Trash2 size={15} /></button></span>
        </div>)}
      </section>
      {editing && <p className="table-designer-warning">删列、修改字段类型或主键可能影响已有数据，且 {config.label} DDL 执行后无法自动回滚。</p>}
      {error && <div className="form-error">{error}</div>}
    </div>
  </Modal>;
}
