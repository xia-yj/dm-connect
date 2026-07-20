import { useEffect, useId, useState } from "react";
import { Braces, ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight, Columns3, Download, FileCode2, KeyRound, LoaderCircle, Search, Table2, View, X } from "lucide-react";
import type { ColumnInfo, ObjectLoadResult, PagedResultTable } from "../types";
import { DataGrid } from "./DataGrid";

type DetailTab = "preview" | "columns" | "constraints" | "indexes" | "ddl";
type PreviewFilter = { column: string; operator: "=" | "LIKE"; value: string }[] | null;

function ErrorPanel({ message }: { message: string }) {
  return <div className="content-error"><FileCode2 size={24} /><strong>无法读取此内容</strong><span>{message || "没有可显示的信息"}</span></div>;
}

function displayColumnType(column: ColumnInfo) {
  const type = column.typeName.toUpperCase();
  if (type === "TIMESTAMP" || type === "TIME") return `${column.typeName}(${column.size || (type === "TIMESTAMP" ? 36 : 8)},${column.scale})`;
  return `${column.typeName}${column.size > 0 ? `(${column.size}${column.scale > 0 ? `,${column.scale}` : ""})` : ""}`;
}

function previewColumnEditable(column: ColumnInfo | undefined): boolean {
  if (!column || column.autoIncrement) return false;
  if (column.safelyEditable === false && /生成列|不可见列/.test(column.editWarning ?? "")) return false;
  return !/^(?:BINARY|VARBINARY|LONGVARBINARY|TINYBLOB|BLOB|MEDIUMBLOB|LONGBLOB|CLOB|NCLOB|BFILE|IMAGE|TEXT|TINYTEXT|MEDIUMTEXT|LONGTEXT|LONGVARCHAR|LONG|JSON|GEOMETRY|POINT|LINESTRING|POLYGON|MULTIPOINT|MULTILINESTRING|MULTIPOLYGON|GEOMETRYCOLLECTION)$/.test(column.typeName.toUpperCase());
}

function filterColumnSuggestions(input: string, columns: { name: string }[]): { name: string }[] {
  const match = input.match(/(^|\s)([^\s]*)$/);
  const token = match?.[2] ?? "";
  const normalizedToken = token.toLowerCase();
  if (!normalizedToken || normalizedToken === "and" || normalizedToken === "or") return [];
  return columns
    .filter(column => !normalizedToken || column.name.toLowerCase().startsWith(normalizedToken))
    .map(column => ({ name: column.name }));
}

interface ObjectViewProps {
  result: ObjectLoadResult;
  onLoadPreview: (page: number, filter: PreviewFilter) => Promise<PagedResultTable>;
  onSaveChanges: (changes: { column: string; value: string | null; keyValues: Record<string, unknown> }[]) => Promise<void>;
  onDeleteRow: (keyValues: Record<string, unknown>) => Promise<void>;
  compact?: boolean;
  onEditTable?: (columnName?: string) => void;
  onExportInsert?: (scope: "CURRENT_PAGE" | "ALL", page: number, filter: PreviewFilter) => Promise<void>;
  onExportCsv?: (scope: "CURRENT_PAGE" | "ALL", page: number, filter: PreviewFilter) => Promise<void>;
}

export function ObjectView({ result, onLoadPreview, onSaveChanges, onDeleteRow, compact = false, onEditTable, onExportInsert, onExportCsv }: ObjectViewProps) {
  const filterColumnListId = useId();
  const table = result.object.kind === "TABLE";
  const [active, setActive] = useState<DetailTab>(table ? "preview" : "ddl");
  const [preview, setPreview] = useState(result.preview);
  const [previewError, setPreviewError] = useState(result.previewError);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [pageInput, setPageInput] = useState(result.preview?.page?.toString() ?? "1");
  const [filterInput, setFilterInput] = useState("");
  const [filterSuggestionsOpen, setFilterSuggestionsOpen] = useState(false);
  const [activeFilterSuggestion, setActiveFilterSuggestion] = useState(0);
  const [previewFilter, setPreviewFilter] = useState<PreviewFilter>(null);
  const [pendingEdits, setPendingEdits] = useState<Record<string, { rowIndex: number; columnIndex: number; column: string; value: string | null; originalValue: unknown; keyValues: Record<string, unknown> }>>({});
  const [saveError, setSaveError] = useState("");
  const [savingChanges, setSavingChanges] = useState(false);
  const [deletingRow, setDeletingRow] = useState(false);
  const [selectedColumnName, setSelectedColumnName] = useState("");
  const [insertScope, setInsertScope] = useState<"CURRENT_PAGE" | "ALL">("CURRENT_PAGE");
  const [exportingInsert, setExportingInsert] = useState(false);
  useEffect(() => {
    setPreview(result.preview);
    setPreviewError(result.previewError);
    setPageInput(result.preview?.page?.toString() ?? "1");
    setFilterInput("");
    setFilterSuggestionsOpen(false);
    setActiveFilterSuggestion(0);
    setPreviewFilter(null);
    setPendingEdits({});
    setSaveError("");
    setSelectedColumnName("");
  }, [result]);

  async function goToPage(page: number) {
    if (!preview || previewLoading) return;
    const lastPage = Math.max(1, Math.ceil(preview.totalRows / preview.pageSize));
    const target = Math.max(1, Math.min(lastPage, page));
    if (target === preview.page) return;
    setPreviewLoading(true);
    setPreviewError("");
    try {
      const next = await onLoadPreview(target, previewFilter);
      setPreview(next);
      setPageInput(next.page.toString());
    } catch (cause) {
      setPreviewError(cause instanceof Error ? cause.message : "读取数据预览失败");
    } finally {
      setPreviewLoading(false);
    }
  }

  function parseFilter(): PreviewFilter {
    const parts = filterInput.trim().split(/\s+AND\s+/i);
    const filters = parts.map(part => {
      const match = part.trim().match(/^([^=\s]+)\s*(=|LIKE)\s*(?:'((?:''|[^'])*)'|([^\s]+))\s*$/i);
      if (!match) throw new Error("筛选格式应为：列名 = 1、列名 = 'xxx' 或 列名 LIKE '%xxx%'，多个条件用 AND 连接");
      return { column: match[1], operator: match[2].toUpperCase() === "LIKE" ? "LIKE" as const : "=" as const, value: (match[3] ?? match[4]).replace(/''/g, "'") };
    });
    return filters;
  }

  async function applyFilter(clear = false) {
    if (!preview || previewLoading) return;
    setPreviewLoading(true);
    setPreviewError("");
    try {
      const filter = clear ? null : parseFilter();
      const next = await onLoadPreview(1, filter);
      setPreview(next);
      setPageInput("1");
      setPreviewFilter(filter);
      if (clear) setFilterInput("");
    } catch (cause) {
      setPreviewError(cause instanceof Error ? cause.message : "筛选数据预览失败");
    } finally {
      setPreviewLoading(false);
    }
  }

  const primaryKeyColumns = result.details?.constraints.find(item => item.type.trim().toUpperCase() === "PRIMARY KEY")?.columns ?? [];
  function updateCell(rowIndex: number, columnIndex: number, column: string, value: string | null) {
    if (!preview || primaryKeyColumns.length === 0) throw new Error("该表没有主键，无法安全地直接编辑数据");
    const row = preview.rows[rowIndex];
    const keyValues = Object.fromEntries(primaryKeyColumns.map(key => {
      const keyIndex = preview.columns.findIndex(item => item.name === key || item.label === key);
      if (keyIndex < 0) throw new Error(`当前预览未包含主键列：${key}`);
      return [key, row[keyIndex]];
    }));
    const key = `${rowIndex}:${columnIndex}`;
    const original = pendingEdits[key]?.keyValues ?? keyValues;
    const originalValue = pendingEdits[key]?.originalValue ?? row[columnIndex];
    setPendingEdits(current => {
      const next = { ...current };
      if (originalValue == null ? value == null : String(originalValue) === String(value)) delete next[key];
      else next[key] = { rowIndex, columnIndex, column, value, originalValue, keyValues: original };
      return next;
    });
    setPreview(current => current && ({ ...current, rows: current.rows.map((item, index) => index === rowIndex
      ? item.map((cell, cellIndex) => cellIndex === columnIndex ? value : cell) : item) }));
  }

  const pendingList = Object.values(pendingEdits);
  async function deleteRow(rowIndex: number) {
    if (!preview || deletingRow || pendingList.length > 0) return;
    if (primaryKeyColumns.length === 0) return;
    const row = preview.rows[rowIndex];
    const keyValues = Object.fromEntries(primaryKeyColumns.map(key => {
      const keyIndex = preview.columns.findIndex(item => item.name === key || item.label === key);
      if (keyIndex < 0 || row[keyIndex] == null) throw new Error(`当前预览未包含主键列：${key}`);
      return [key, row[keyIndex]];
    }));
    if (!window.confirm("确认删除选中的数据行？此操作不可撤销。")) return;
    setDeletingRow(true);
    setSaveError("");
    try {
      await onDeleteRow(keyValues);
      const remainingRows = Math.max(0, preview.totalRows - 1);
      const lastPage = Math.max(1, Math.ceil(remainingRows / preview.pageSize));
      const next = await onLoadPreview(Math.min(preview.page, lastPage), previewFilter);
      setPreview(next);
      setPageInput(next.page.toString());
    } catch (cause) {
      setSaveError(cause instanceof Error ? cause.message : "删除失败");
    } finally {
      setDeletingRow(false);
    }
  }
  async function saveChanges() {
    if (pendingList.length === 0 || savingChanges) return;
    setSavingChanges(true);
    setSaveError("");
    try {
      await onSaveChanges(pendingList.map(({ column, value, keyValues }) => ({ column, value, keyValues })));
      setPendingEdits({});
    } catch (cause) {
      setSaveError(cause instanceof Error ? cause.message : "保存失败");
    } finally {
      setSavingChanges(false);
    }
  }
  async function exportInsert() {
    if (!preview || !onExportInsert || exportingInsert) return;
    setExportingInsert(true);
    try { await onExportInsert(insertScope, preview.page, previewFilter); }
    finally { setExportingInsert(false); }
  }
  async function exportCsv() {
    if (!preview || !onExportCsv || exportingInsert) return;
    setExportingInsert(true);
    try { await onExportCsv(insertScope, preview.page, previewFilter); }
    finally { setExportingInsert(false); }
  }
  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "s") {
        event.preventDefault();
        void saveChanges();
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [pendingList, savingChanges]);
  const previewWithRemarks = preview ? {
    ...preview,
    columns: preview.columns.map(column => ({
      ...column,
      remarks: result.details?.columns.find(item => item.name.toUpperCase() === column.name.toUpperCase() || item.name.toUpperCase() === column.label.toUpperCase())?.remarks ?? null
    }))
  } : null;
  const filterSuggestions = filterColumnSuggestions(filterInput, preview?.columns ?? []);
  function chooseFilterColumn(columnName: string) {
    const match = filterInput.match(/(^|\s)([^\s]*)$/);
    const token = match?.[2] ?? "";
    setFilterInput(`${filterInput.slice(0, filterInput.length - token.length)}${columnName}`);
    setFilterSuggestionsOpen(false);
    setActiveFilterSuggestion(0);
  }
  const previewPanel = preview ? <div className="preview-with-pagination">
    <div className="preview-filter">
      <Search size={15} />
      <div className="preview-filter-input-wrap">
        <input id={filterColumnListId} value={filterInput} onChange={event => { setFilterInput(event.target.value); setFilterSuggestionsOpen(true); setActiveFilterSuggestion(0); }} onFocus={() => setFilterSuggestionsOpen(filterInput.trim().length > 0)} onKeyDown={event => {
          if (event.key === "Escape") { setFilterSuggestionsOpen(false); return; }
          if (filterSuggestionsOpen && filterSuggestions.length > 0 && event.key === "ArrowDown") { event.preventDefault(); setActiveFilterSuggestion(index => Math.min(index + 1, filterSuggestions.length - 1)); return; }
          if (filterSuggestionsOpen && filterSuggestions.length > 0 && event.key === "ArrowUp") { event.preventDefault(); setActiveFilterSuggestion(index => Math.max(index - 1, 0)); return; }
          if (event.key === "Enter") {
            if (filterSuggestionsOpen && filterSuggestions.length > 0) { event.preventDefault(); chooseFilterColumn(filterSuggestions[activeFilterSuggestion]?.name ?? filterSuggestions[0].name); return; }
            setFilterSuggestionsOpen(false); void applyFilter();
          }
        }} placeholder="筛选：列名 = 1，可用 AND 连接多个条件" aria-label="数据预览筛选条件" />
        {filterSuggestionsOpen && filterSuggestions.length > 0 && <div className="preview-filter-suggestions">{filterSuggestions.map((column, index) => <button type="button" key={column.name} className={index === activeFilterSuggestion ? "active" : ""} onMouseDown={event => event.preventDefault()} onMouseEnter={() => setActiveFilterSuggestion(index)} onClick={() => chooseFilterColumn(column.name)}>{column.name}</button>)}</div>}
      </div>
      {previewFilter && <button className="clear-preview-filter" onClick={() => void applyFilter(true)} disabled={previewLoading} title="清除筛选"><X size={15} />清除</button>}
      <button className="apply-preview-filter" onClick={() => void applyFilter()} disabled={previewLoading || !filterInput.trim()}>筛选</button>
      {(onExportInsert || onExportCsv) && <span className="insert-export-actions"><select value={insertScope} onChange={event => setInsertScope(event.target.value as "CURRENT_PAGE" | "ALL")} aria-label="导出范围"><option value="CURRENT_PAGE">当前页</option><option value="ALL">全部数据</option></select>{onExportCsv && <button className="button secondary compact" disabled={exportingInsert} onClick={() => void exportCsv()}><Download size={14} />导出 CSV</button>}{onExportInsert && <button className="button secondary compact" disabled={exportingInsert} onClick={() => void exportInsert()}><Download size={14} />{exportingInsert ? "导出中…" : "导出 INSERT"}</button>}</span>}
    </div>
    {previewError && <div className="preview-error">{previewError}</div>}
    <DataGrid table={{ ...previewWithRemarks!, truncated: false }} rowOffset={(preview.page - 1) * preview.pageSize} onEditCell={primaryKeyColumns.length > 0 ? (rowIndex, column, value) => updateCell(rowIndex, preview.columns.findIndex(item => item.name === column.name || item.label === column.label), column.name, value) : undefined} onDeleteRow={primaryKeyColumns.length > 0 && !deletingRow && pendingList.length === 0 ? rowIndex => void deleteRow(rowIndex) : undefined} isColumnEditable={column => !primaryKeyColumns.some(key => key.toLowerCase() === column.name.toLowerCase() || key.toLowerCase() === column.label.toLowerCase()) && previewColumnEditable(result.details?.columns.find(item => item.name.toLowerCase() === column.name.toLowerCase() || item.name.toLowerCase() === column.label.toLowerCase()))} editedCellKeys={new Set(Object.keys(pendingEdits))} />
    {saveError && <div className="grid-edit-error">{saveError}</div>}
    <div className="preview-pagination">
      <span>共 {preview.totalRows} 条 · 每页 {preview.pageSize} 条</span>
      <div className="preview-pagination-actions">
        <button onClick={() => void goToPage(1)} disabled={previewLoading || pendingList.length > 0 || preview.page === 1} title="首页"><ChevronsLeft size={15} /></button>
        <button onClick={() => void goToPage(preview.page - 1)} disabled={previewLoading || pendingList.length > 0 || preview.page === 1} title="上一页"><ChevronLeft size={15} /></button>
        <label>第 <input value={pageInput} inputMode="numeric" onChange={event => setPageInput(event.target.value.replace(/[^0-9]/g, ""))} onKeyDown={event => { if (event.key === "Enter") void goToPage(Number(pageInput)); }} /> / {Math.max(1, Math.ceil(preview.totalRows / preview.pageSize))} 页</label>
        <button onClick={() => void goToPage(preview.page + 1)} disabled={previewLoading || pendingList.length > 0 || preview.page >= Math.max(1, Math.ceil(preview.totalRows / preview.pageSize))} title="下一页"><ChevronRight size={15} /></button>
        <button onClick={() => void goToPage(Math.ceil(preview.totalRows / preview.pageSize))} disabled={previewLoading || pendingList.length > 0 || preview.page >= Math.max(1, Math.ceil(preview.totalRows / preview.pageSize))} title="末页"><ChevronsRight size={15} /></button>
        {pendingList.length > 0 && <button className="save-grid-changes" onClick={() => void saveChanges()} disabled={savingChanges}>{savingChanges ? "保存中…" : `保存 ${pendingList.length} 项`}</button>}
        {previewLoading && <LoaderCircle className="spin" size={15} />}
      </div>
    </div>
  </div> : <ErrorPanel message={previewError} />;
  if (compact) return <section className="object-view compact-preview">{previewPanel}</section>;
  const tabs: { id: DetailTab; label: string; icon: typeof Table2 }[] = table
    ? [
      { id: "preview", label: "数据预览", icon: Table2 },
      { id: "columns", label: "列", icon: Columns3 },
      { id: "constraints", label: "约束", icon: KeyRound },
      { id: "indexes", label: "索引", icon: Braces },
      { id: "ddl", label: "DDL", icon: FileCode2 }
    ]
    : [{ id: "ddl", label: "DDL", icon: FileCode2 }];

  return (
    <section className="object-view">
      <header className="object-header">
        <span className="object-icon">{result.object.kind === "VIEW" ? <View size={21} /> : <Table2 size={21} />}</span>
        <div><div className="object-kicker">{result.object.kind} · {result.object.schema}</div><h2>{result.object.name}</h2><p>{result.object.remarks || "数据库对象详情与元数据"}</p></div>
      </header>
      <nav className="sub-tabs">{tabs.map(({ id, label, icon: Icon }) => <button key={id} className={active === id ? "active" : ""} onClick={() => setActive(id)}><Icon size={14} />{label}</button>)}</nav>
      <div className="object-content">
        {active === "preview" && previewPanel}
        {active === "columns" && (result.details ? <div className="metadata-table-wrap"><div className="metadata-actions"><span>选中字段后可编辑其定义</span>{onEditTable && <button className="button secondary compact" disabled={!selectedColumnName} onClick={() => onEditTable(selectedColumnName)}>编辑所选列</button>}</div><table className="metadata-table"><thead><tr><th>#</th><th>列名</th><th>数据类型</th><th>可空</th><th>自增</th><th>默认值</th><th>备注</th></tr></thead><tbody>{result.details.columns.map(column => <tr key={column.name} className={selectedColumnName === column.name ? "selected" : ""} onClick={() => setSelectedColumnName(column.name)} onDoubleClick={() => onEditTable?.(column.name)}><td>{column.ordinal}</td><td className="strong mono">{column.name}</td><td><span className="type-pill">{displayColumnType(column)}</span></td><td>{column.nullable ? "是" : "否"}</td><td>{column.autoIncrement ? "是" : "否"}</td><td className="mono muted">{column.defaultValue ?? "—"}</td><td className="muted">{column.remarks || "—"}</td></tr>)}</tbody></table></div> : <ErrorPanel message={result.detailsError} />)}
        {active === "constraints" && (result.details ? <div className="metadata-table-wrap"><table className="metadata-table"><thead><tr><th>名称</th><th>类型</th><th>列</th><th>引用对象</th><th>引用列</th></tr></thead><tbody>{result.details.constraints.map((item, index) => <tr key={`${item.name}-${index}`}><td className="strong mono">{item.name}</td><td><span className="type-pill">{item.type}</span></td><td className="mono">{item.columns.join(", ")}</td><td className="mono">{item.referencedTable ? `${item.referencedSchema}.${item.referencedTable}` : "—"}</td><td className="mono">{item.referencedColumns.join(", ") || "—"}</td></tr>)}</tbody></table>{result.details.constraints.length === 0 && <div className="empty-metadata">没有读取到约束信息</div>}</div> : <ErrorPanel message={result.detailsError} />)}
        {active === "indexes" && (result.details ? <div className="metadata-table-wrap"><table className="metadata-table"><thead><tr><th>索引名称</th><th>唯一</th><th>列</th></tr></thead><tbody>{result.details.indexes.map(item => <tr key={item.name}><td className="strong mono">{item.name}</td><td>{item.unique ? "是" : "否"}</td><td className="mono">{item.columns.join(", ")}</td></tr>)}</tbody></table>{result.details.indexes.length === 0 && <div className="empty-metadata">没有读取到索引信息</div>}</div> : <ErrorPanel message={result.detailsError} />)}
        {active === "ddl" && (result.ddl != null ? <pre className="ddl-view"><code>{result.ddl || "-- 数据库未返回 DDL"}</code></pre> : <ErrorPanel message={result.ddlError} />)}
      </div>
    </section>
  );
}
