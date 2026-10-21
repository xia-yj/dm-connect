import { useEffect, useId, useRef, useState } from "react";
import { Braces, ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight, Columns3, Download, FileCode2, KeyRound, LoaderCircle, Search, Table2, View, X, RefreshCw, Plus, Minus, Undo2, Redo2, Save, Check, RotateCcw } from "lucide-react";
import type { ColumnInfo, ObjectLoadResult, PagedResultTable, PendingTableChange, PreviewFilter, TableSessionStatus } from "../types";
import { DataGrid } from "./DataGrid";
import { FilterSyntaxInput } from "./FilterSyntaxInput";

type DetailTab = "preview" | "columns" | "constraints" | "indexes" | "ddl";
type PendingCellEdit = { rowIndex: number; columnIndex: number; column: string; value: string | null; originalValue: unknown; keyValues: Record<string, unknown> };
type DraftRow = { clientId: string; values: unknown[] };
type DraftHistory = {
  changes: PendingTableChange[];
  rows: DraftRow[];
  preview: PagedResultTable | null;
  edits: Record<string, PendingCellEdit>;
  selectedRows: number[];
  pendingDeleteRows: number[];
};
function ErrorPanel({ message }: { message: string }) {
  return <div className="content-error"><FileCode2 size={24} /><strong>无法读取此内容</strong><span>{message || "没有可显示的信息"}</span></div>;
}

function displayColumnType(column: ColumnInfo) {
  const type = column.typeName.toUpperCase();
  if (type === "TIMESTAMP" || type === "TIME") return `${column.typeName}(${column.size || (type === "TIMESTAMP" ? 36 : 8)},${column.scale})`;
  return `${column.typeName}${column.size > 0 ? `(${column.size}${column.scale > 0 ? `,${column.scale}` : ""})` : ""}`;
}

const nonComparableTypes = /^(?:BINARY|VARBINARY|LONGVARBINARY|TINYBLOB|BLOB|MEDIUMBLOB|LONGBLOB|CLOB|NCLOB|BFILE|IMAGE|TEXT|TINYTEXT|MEDIUMTEXT|LONGTEXT|LONGVARCHAR|LONG|JSON|GEOMETRY|POINT|LINESTRING|POLYGON|MULTIPOINT|MULTILINESTRING|MULTIPOLYGON|GEOMETRYCOLLECTION)$/;

function previewColumnEditable(column: ColumnInfo | undefined): boolean {
  if (!column || column.autoIncrement) return false;
  if (column.safelyEditable === false && /生成列|不可见列/.test(column.editWarning ?? "")) return false;
  return !nonComparableTypes.test(column.typeName.toUpperCase());
}

/** Whether a column's preview value can be compared with `=` to find a row in a table without a primary key. */
function rowMatchColumn(column: ColumnInfo | undefined): boolean {
  if (!column) return false;
  const type = column.typeName.toUpperCase();
  return !nonComparableTypes.test(type) && !/^(?:FLOAT|REAL|DOUBLE|BINARY_FLOAT|BINARY_DOUBLE|DATE|TIME|TIMESTAMP|DATETIME|SMALLDATETIME|INTERVAL|YEAR)/.test(type);
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
  onSaveChanges: (changes: PendingTableChange[]) => Promise<TableSessionStatus | undefined>;
  onDeleteRow: (keyValues: Record<string, unknown>) => Promise<void>;
  compact?: boolean;
  onEditTable?: (columnName?: string) => void;
  onExportInsert?: (scope: "CURRENT_PAGE" | "ALL", page: number, filter: PreviewFilter) => Promise<void>;
  onExportCsv?: (scope: "CURRENT_PAGE" | "ALL", page: number, filter: PreviewFilter) => Promise<void>;
  tableSession?: TableSessionStatus;
  onTableSessionStatus?: (status: TableSessionStatus) => void;
  onCommitTable?: () => Promise<TableSessionStatus | undefined>;
  onRollbackTable?: () => Promise<TableSessionStatus | undefined>;
  onAutoCommitTable?: (autoCommit: boolean) => Promise<TableSessionStatus | undefined>;
  onDirtyChange?: (dirty: boolean) => void;
}

export function ObjectView({ result, onLoadPreview, onSaveChanges, onDeleteRow, compact = false, onEditTable, onExportInsert, onExportCsv, tableSession, onTableSessionStatus, onCommitTable, onRollbackTable, onAutoCommitTable, onDirtyChange }: ObjectViewProps) {
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
  const [sessionStatus, setSessionStatus] = useState<TableSessionStatus | undefined>(tableSession);
  const [draftRows, setDraftRows] = useState<DraftRow[]>([]);
  const [pendingChanges, setPendingChanges] = useState<PendingTableChange[]>([]);
  const [history, setHistory] = useState<DraftHistory[]>([]);
  const [future, setFuture] = useState<DraftHistory[]>([]);
  const [selectedRows, setSelectedRows] = useState<Set<number>>(new Set());
  const [pendingDeleteRows, setPendingDeleteRows] = useState<Set<number>>(new Set());
  const reportedDirty = useRef(false);
  const [pendingEdits, setPendingEdits] = useState<Record<string, PendingCellEdit>>({});
  const [saveError, setSaveError] = useState("");
  const [savingChanges, setSavingChanges] = useState(false);
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
    setSessionStatus(tableSession);
    setDraftRows([]);
    setPendingChanges([]);
    setHistory([]);
    setFuture([]);
    setSelectedRows(new Set());
    setPendingDeleteRows(new Set());
    setPendingEdits({});
    setSaveError("");
    setSelectedColumnName("");
  }, [result]);

  async function goToPage(page: number) {
    if (!preview || previewLoading) return;
    if (hasLocalChanges) { setPreviewError("存在未保存修改，请先保存或撤销后再翻页"); return; }
    const lastPage = Math.max(1, Math.ceil(preview.totalRows / preview.pageSize));
    const target = Math.max(1, Math.min(lastPage, page));
    if (target === preview.page) return;
    setPreviewLoading(true);
    setPreviewError("");
    try {
      const next = await onLoadPreview(target, previewFilter);
      setPreview(next);
      setPageInput(next.page.toString());
      setSelectedRows(new Set());
    } catch (cause) {
      setPreviewError(cause instanceof Error ? cause.message : "读取数据预览失败");
    } finally {
      setPreviewLoading(false);
    }
  }

  function parseFilter(): PreviewFilter {
    const input = filterInput.trim();
    if (!input) return null;
    const parts = input.split(/\s+AND\s+/i);
    const filters = parts.map(part => {
      const match = part.trim().match(/^([^=\s]+)\s*(=|LIKE)\s*(?:'((?:''|[^'])*)'|([^\s]+))\s*$/i);
      if (!match) throw new Error("筛选格式应为：列名 = 1、列名 = 'xxx' 或 列名 LIKE '%xxx%'，多个条件用 AND 连接");
      return { column: match[1], operator: match[2].toUpperCase() === "LIKE" ? "LIKE" as const : "=" as const, value: (match[3] ?? match[4]).replace(/''/g, "'") };
    });
    return filters;
  }

  function historySnapshot(): DraftHistory {
    return {
      changes: pendingChanges,
      rows: draftRows,
      preview,
      edits: pendingEdits,
      selectedRows: [...selectedRows],
      pendingDeleteRows: [...pendingDeleteRows]
    };
  }

  function recordChanges(next: PendingTableChange[]) {
    setHistory(current => [...current, historySnapshot()]);
    setFuture([]);
    setPendingChanges(next);
  }

  function addDraftRow() {
    if (!preview) return;
    const clientId = crypto.randomUUID();
    const values = preview.columns.map(() => "");
    setDraftRows([...draftRows, { clientId, values }]);
    recordChanges([...pendingChanges, { type: "INSERT", values: {}, useDefaults: [], clientId }]);
  }

  function undoDraft() {
    if (history.length === 0) return;
    const previous = history[history.length - 1];
    setFuture(current => [historySnapshot(), ...current]);
    setHistory(current => current.slice(0, -1));
    setPendingChanges(previous.changes);
    setDraftRows(previous.rows);
    setPreview(previous.preview);
    setPendingEdits(previous.edits);
    setSelectedRows(new Set(previous.selectedRows));
    setPendingDeleteRows(new Set(previous.pendingDeleteRows));
  }

  function redoDraft() {
    if (future.length === 0) return;
    const next = future[0];
    setHistory(current => [...current, historySnapshot()]);
    setFuture(current => current.slice(1));
    setPendingChanges(next.changes);
    setDraftRows(next.rows);
    setPreview(next.preview);
    setPendingEdits(next.edits);
    setSelectedRows(new Set(next.selectedRows));
    setPendingDeleteRows(new Set(next.pendingDeleteRows));
  }

  async function applyFilter(clear = false) {
    if (!preview || previewLoading) return;
    if (hasLocalChanges) { setPreviewError("存在未保存修改，请先保存或撤销后再刷新筛选"); return; }
    setPreviewLoading(true);
    setPreviewError("");
    try {
      const filter = clear ? null : parseFilter();
      const next = await onLoadPreview(1, filter);
      setPreview(next);
      setPageInput("1");
      setPreviewFilter(filter);
      setSelectedRows(new Set());
      if (clear) setFilterInput("");
    } catch (cause) {
      setPreviewError(cause instanceof Error ? cause.message : "筛选数据预览失败");
    } finally {
      setPreviewLoading(false);
    }
  }

  const primaryKeyColumns = result.details?.constraints.find(item => item.type.trim().toUpperCase() === "PRIMARY KEY")?.columns ?? [];
  function keySignature(keyValues: Record<string, unknown>) {
    return JSON.stringify(keyValues);
  }
  const uniqueKeyColumns = [
    ...(result.details?.constraints.filter(item => item.type.trim().toUpperCase() === "UNIQUE").map(item => item.columns) ?? []),
    ...(result.details?.indexes.filter(item => item.unique).map(item => item.columns) ?? [])
  ].filter(columns => columns.length > 0);
  function previewColumnIndex(name: string) {
    return preview?.columns.findIndex(item => item.name.toLowerCase() === name.toLowerCase() || item.label.toLowerCase() === name.toLowerCase()) ?? -1;
  }
  function keyValuesForRow(rowIndex: number): Record<string, unknown> {
    if (!preview) throw new Error("未找到要操作的数据行");
    const row = preview.rows[rowIndex];
    if (!row) throw new Error("未找到要操作的数据行");
    if (primaryKeyColumns.length > 0) {
      return Object.fromEntries(primaryKeyColumns.map(key => {
        const keyIndex = previewColumnIndex(key);
        if (keyIndex < 0 || row[keyIndex] == null) throw new Error(`当前预览未包含有效主键列：${key}`);
        return [key, row[keyIndex]];
      }));
    }
    // Without a primary key the row is found by the values it had before any local edit: a non-null unique key
    // if there is one, otherwise every comparable column. The backend rejects the change unless exactly one row matches.
    const original = row.map((cell, columnIndex) => { const edit = pendingEdits[`${rowIndex}:${columnIndex}`]; return edit ? edit.originalValue : cell; });
    const unique = uniqueKeyColumns.find(columns => columns.every(name => { const index = previewColumnIndex(name); return index >= 0 && original[index] != null; }));
    const columns = unique ?? preview.columns.map(column => column.name)
      .filter(name => rowMatchColumn(result.details?.columns.find(item => item.name.toLowerCase() === name.toLowerCase())));
    if (columns.length === 0) throw new Error("该表没有主键，也没有可用于定位记录的列，请使用 SQL 工作台修改");
    return Object.fromEntries(columns.map(name => [name, original[previewColumnIndex(name)]]));
  }
  function toggleRowSelection(rowIndex: number, additive: boolean) {
    setSelectedRows(current => {
      if (!additive) return new Set([rowIndex]);
      const next = new Set(current);
      if (next.has(rowIndex)) next.delete(rowIndex);
      else next.add(rowIndex);
      return next;
    });
  }
  function canDeleteRows(rowIndexes: Iterable<number>) {
    if (!preview) return false;
    const indexes = [...new Set(rowIndexes)];
    return indexes.length > 0;
  }
  function updateCell(rowIndex: number, columnIndex: number, column: string, value: string | null) {
    if (pendingDeleteRows.has(rowIndex)) return;
    if (preview && rowIndex >= preview.rows.length) {
      const draftIndex = rowIndex - preview.rows.length;
      const draft = draftRows[draftIndex];
      if (!draft) return;
      setDraftRows(current => current.map((item, index) => index === draftIndex ? { ...item, values: item.values.map((cell, index) => index === columnIndex ? value : cell) } : item));
      const insert = pendingChanges.find(change => change.type === "INSERT" && change.clientId === draft.clientId);
      const values = { ...(insert?.type === "INSERT" ? insert.values : {}) };
      const metadata = result.details?.columns.find(item => item.name.toLowerCase() === column.toLowerCase());
      const useDefaults = new Set(insert?.type === "INSERT" ? insert.useDefaults : []);
      if (value === "" && metadata?.defaultValue != null) {
        delete values[column];
        useDefaults.add(column);
      } else {
        values[column] = value === "" && metadata?.nullable ? null : value;
        useDefaults.delete(column);
      }
      const next = pendingChanges.map(change => change.type === "INSERT" && change.clientId === draft.clientId ? { ...change, values, useDefaults: [...useDefaults] } : change);
      recordChanges(next);
      return;
    }
    if (!preview) return;
    const row = preview.rows[rowIndex];
    if (!row) return;
    let keyValues: Record<string, unknown>;
    try { keyValues = keyValuesForRow(rowIndex); }
    catch (cause) { setSaveError(cause instanceof Error ? cause.message : "无法定位要修改的记录"); return; }
    const key = `${rowIndex}:${columnIndex}`;
    const original = pendingEdits[key]?.keyValues ?? keyValues;
    const originalValue = pendingEdits[key]?.originalValue ?? row[columnIndex];
    const changed = originalValue == null ? value != null : String(originalValue) !== String(value);
    setPendingEdits(current => {
      const next = { ...current };
      if (!changed) delete next[key];
      else next[key] = { rowIndex, columnIndex, column, value, originalValue, keyValues: original };
      return next;
    });
    setPreview(current => current && ({ ...current, rows: current.rows.map((item, index) => index === rowIndex
      ? item.map((cell, cellIndex) => cellIndex === columnIndex ? value : cell) : item) }));
    const nextChange: PendingTableChange = { type: "UPDATE", column, value, keyValues };
    const nextChanges = pendingChanges.filter(change => !(change.type === "UPDATE" && keySignature(change.keyValues) === keySignature(keyValues) && change.column === column));
    if (changed) nextChanges.push(nextChange);
    recordChanges(nextChanges);
  }

  const pendingList = Object.values(pendingEdits);
  const hasLocalChanges = pendingChanges.length > 0 || pendingList.length > 0;
  useEffect(() => {
    const dirty = hasLocalChanges;
    if (reportedDirty.current === dirty) return;
    reportedDirty.current = dirty;
    onDirtyChange?.(dirty);
  }, [hasLocalChanges, onDirtyChange]);
  function markRowsForDeletion(rowIndexes: Iterable<number>) {
    if (!preview) return;
    const indexes = [...new Set(rowIndexes)].filter(rowIndex => rowIndex >= 0 && rowIndex < preview.rows.length + draftRows.length);
    if (indexes.length === 0 || !canDeleteRows(indexes)) return;
    try {
      const persisted = indexes
        .filter(rowIndex => rowIndex < preview.rows.length)
        .map(rowIndex => ({ rowIndex, keyValues: keyValuesForRow(rowIndex) }));
      const newlyMarked = persisted.filter(item => !pendingDeleteRows.has(item.rowIndex));
      const draftClientIds = indexes
        .filter(rowIndex => rowIndex >= preview.rows.length)
        .map(rowIndex => draftRows[rowIndex - preview.rows.length]?.clientId)
        .filter((clientId): clientId is string => Boolean(clientId));
      const toggledDeleteKeys = new Set(persisted.filter(item => pendingDeleteRows.has(item.rowIndex)).map(item => keySignature(item.keyValues)));
      const nextChanges = pendingChanges
        .filter(change => !(change.type === "INSERT" && draftClientIds.includes(change.clientId)))
        .filter(change => !(change.type === "DELETE" && toggledDeleteKeys.has(keySignature(change.keyValues))));
      for (const item of newlyMarked) nextChanges.push({ type: "DELETE", keyValues: item.keyValues });
      recordChanges(nextChanges);
      if (draftClientIds.length > 0) setDraftRows(current => current.filter(item => !draftClientIds.includes(item.clientId)));
      setPendingDeleteRows(current => {
        const next = new Set(current);
        for (const item of persisted) {
          if (next.has(item.rowIndex)) next.delete(item.rowIndex);
          else next.add(item.rowIndex);
        }
        return next;
      });
      setSelectedRows(new Set());
      setSaveError("");
    } catch (cause) {
      setSaveError(cause instanceof Error ? cause.message : "标记删除失败");
    }
  }
  async function saveChanges() {
    if (!hasLocalChanges || savingChanges) return;
    setSavingChanges(true);
    setSaveError("");
    try {
      const deletingKeys = new Set(pendingChanges
        .filter((change): change is Extract<PendingTableChange, { type: "DELETE" }> => change.type === "DELETE")
        .map(change => keySignature(change.keyValues)));
      // Updates run one after another, so a later edit of the same row must match the values earlier ones wrote.
      const rowLocators = new Map<number, Record<string, unknown>>();
      const changes: PendingTableChange[] = [
        ...pendingChanges.filter(change => change.type !== "UPDATE"),
        ...pendingList
          .filter(edit => !deletingKeys.has(keySignature(edit.keyValues)))
          .map(({ rowIndex, column, value, keyValues }) => {
            const locator = rowLocators.get(rowIndex) ?? keyValues;
            const matchedColumn = Object.keys(locator).find(key => key.toLowerCase() === column.toLowerCase());
            if (matchedColumn) rowLocators.set(rowIndex, { ...locator, [matchedColumn]: value });
            return { type: "UPDATE" as const, column, value, keyValues: locator };
          })
      ];
      const nextStatus = await onSaveChanges(changes);
      if (nextStatus) { setSessionStatus(nextStatus); onTableSessionStatus?.(nextStatus); }
      setPendingEdits({});
      setPendingChanges([]);
      setDraftRows([]);
      setHistory([]);
      setFuture([]);
      setSelectedRows(new Set());
      setPendingDeleteRows(new Set());
      if (preview) {
        setPreviewLoading(true);
        setPreviewError("");
        try {
          const refreshed = await onLoadPreview(preview.page, previewFilter);
          setPreview(refreshed);
          setPageInput(refreshed.page.toString());
        } catch (cause) {
          const message = cause instanceof Error ? cause.message : "刷新失败";
          setPreviewError(`修改已保存，但重新读取数据失败：${message}`);
        } finally {
          setPreviewLoading(false);
        }
      }
    } catch (cause) {
      setSaveError(cause instanceof Error ? cause.message : "保存失败");
    } finally {
      setSavingChanges(false);
    }
  }
  async function refreshTable() {
    if (!preview || previewLoading) return;
    if (hasLocalChanges) { setPreviewError("存在未保存修改，请先保存或撤销后再刷新"); return; }
    setPreviewLoading(true);
    try { const next = await onLoadPreview(1, previewFilter); setPreview(next); setPageInput("1"); setSelectedRows(new Set()); }
    catch (cause) { setPreviewError(cause instanceof Error ? cause.message : "刷新失败"); }
    finally { setPreviewLoading(false); }
  }
  async function changeAutoCommit() {
    if (!onAutoCommitTable || !sessionStatus) return;
    try { const next = await onAutoCommitTable(!sessionStatus.autoCommit); if (next) { setSessionStatus(next); onTableSessionStatus?.(next); } }
    catch (cause) { setPreviewError(cause instanceof Error ? cause.message : "切换提交模式失败"); }
  }
  async function commitTable() {
    if (!onCommitTable) return;
    try { const next = await onCommitTable(); if (next) { setSessionStatus(next); onTableSessionStatus?.(next); await refreshTable(); } }
    catch (cause) { setPreviewError(cause instanceof Error ? cause.message : "提交事务失败"); }
  }
  async function rollbackTable() {
    if (!onRollbackTable) return;
    try { const next = await onRollbackTable(); if (next) { setSessionStatus(next); onTableSessionStatus?.(next); setPendingChanges([]); setPendingEdits({}); setDraftRows([]); setHistory([]); setFuture([]); setSelectedRows(new Set()); setPendingDeleteRows(new Set()); await refreshTable(); } }
    catch (cause) { setPreviewError(cause instanceof Error ? cause.message : "回滚事务失败"); }
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
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "z") {
        event.preventDefault();
        if (event.shiftKey) redoDraft(); else undoDraft();
      }
      if (event.ctrlKey && event.key.toLowerCase() === "y") {
        event.preventDefault();
        redoDraft();
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [pendingList, savingChanges, pendingChanges, history, future]);
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
  async function filterColumn(columnName: string) {
    if (hasLocalChanges) { setPreviewError("存在未保存修改，请先保存或撤销后再筛选"); return; }
    const input = window.prompt(`请输入 ${columnName} 的筛选条件（例如 = 1 或 LIKE '%关键字%'）`, "= ");
    if (input == null) return;
    const match = input.trim().match(/^(=|LIKE)\s*(.*)$/i);
    if (!match) { setPreviewError("列筛选格式应为：= 值或 LIKE '%值%'"); return; }
    const value = match[2].trim().replace(/^'(.*)'$/, "$1").replace(/''/g, "'");
    const next = [...(previewFilter ?? []).filter(item => item.column.toLowerCase() !== columnName.toLowerCase()), { column: columnName, operator: match[1].toUpperCase() as "=" | "LIKE", value }];
    setPreviewLoading(true);
    try { const loaded = await onLoadPreview(1, next); setPreview(loaded); setPageInput("1"); setPreviewFilter(next); setFilterInput(next.map(item => `${item.column} ${item.operator} '${item.value.replace(/'/g, "''")}'`).join(" AND ")); setSelectedRows(new Set()); }
    catch (cause) { setPreviewError(cause instanceof Error ? cause.message : "列筛选失败"); }
    finally { setPreviewLoading(false); }
  }
  const filteredColumns = new Set((previewFilter ?? []).map(item => item.column));
  const selectedRowIndexes = [...selectedRows];
  const pendingSaveCount = pendingChanges.filter(change => change.type !== "UPDATE").length + pendingList.length;
  const previewPanel = preview ? <div className="preview-with-pagination">
    <div className="table-editor-toolbar" role="toolbar" aria-label="数据表工具栏">
      <button title="刷新数据" aria-label="刷新数据" onClick={() => void refreshTable()} disabled={previewLoading}><RefreshCw size={14} /></button>
      <button title="撤销草稿" aria-label="撤销草稿" onClick={undoDraft} disabled={history.length === 0}><Undo2 size={14} /></button>
      <button title="重做草稿" aria-label="重做草稿" onClick={redoDraft} disabled={future.length === 0}><Redo2 size={14} /></button>
      <span className="toolbar-separator" />
      <button title="新增行" aria-label="新增行" onClick={addDraftRow} disabled={previewLoading}><Plus size={14} /></button>
      <button title="标记或取消删除选中行（保存后生效）" aria-label="删除选中行" onClick={() => markRowsForDeletion(selectedRowIndexes)} disabled={selectedRowIndexes.length === 0 || previewLoading || !canDeleteRows(selectedRowIndexes)}><Minus size={14} /></button>
      <button title="保存修改" aria-label="保存修改" onClick={() => void saveChanges()} disabled={savingChanges || !hasLocalChanges}><Save size={14} />{savingChanges ? "保存中" : "保存"}</button>
      {sessionStatus && <button title="切换自动提交" aria-label="切换自动提交" onClick={() => void changeAutoCommit()} disabled={savingChanges}>Tx：{sessionStatus.autoCommit ? "自动" : "手动"}</button>}
      {sessionStatus && <button title="提交事务" aria-label="提交事务" onClick={() => void commitTable()} disabled={sessionStatus.autoCommit || !sessionStatus.pendingTransaction}><Check size={14} /></button>}
      {sessionStatus && <button title="回滚事务" aria-label="回滚事务" onClick={() => void rollbackTable()} disabled={sessionStatus.autoCommit || !sessionStatus.pendingTransaction}><RotateCcw size={14} /></button>}
      <button title="查看 DDL" aria-label="查看 DDL" onClick={() => setActive("ddl")}><FileCode2 size={14} /></button>
      <span className="toolbar-spacer" />
      {(onExportInsert || onExportCsv) && <span className="toolbar-export"><select value={insertScope} onChange={event => setInsertScope(event.target.value as "CURRENT_PAGE" | "ALL")} aria-label="导出范围"><option value="CURRENT_PAGE">当前页</option><option value="ALL">全部数据</option></select>{onExportCsv && <button title="导出 CSV" aria-label="导出 CSV" disabled={exportingInsert} onClick={() => void exportCsv()}><Download size={14} /></button>}{onExportInsert && <button title="导出 INSERT" aria-label="导出 INSERT" disabled={exportingInsert} onClick={() => void exportInsert()}><Download size={14} /></button>}</span>}
    </div>
    <div className="preview-filter">
      <Search size={15} />
      <div className="preview-filter-input-wrap">
        <FilterSyntaxInput
          id={filterColumnListId}
          value={filterInput}
          placeholder="筛选：列名 = 1，可用 AND 连接多个条件"
          aria-label="数据预览筛选条件"
          onChange={next => { setFilterInput(next); setFilterSuggestionsOpen(true); setActiveFilterSuggestion(0); }}
          onFocus={() => setFilterSuggestionsOpen(filterInput.trim().length > 0)}
          onKeyDown={event => {
            if (event.key === "Escape") { setFilterSuggestionsOpen(false); return; }
            if (filterSuggestionsOpen && filterSuggestions.length > 0 && event.key === "ArrowDown") { event.preventDefault(); setActiveFilterSuggestion(index => Math.min(index + 1, filterSuggestions.length - 1)); return; }
            if (filterSuggestionsOpen && filterSuggestions.length > 0 && event.key === "ArrowUp") { event.preventDefault(); setActiveFilterSuggestion(index => Math.max(index - 1, 0)); return; }
            if (event.key === "Enter") {
              if (filterSuggestionsOpen && filterSuggestions.length > 0) { event.preventDefault(); chooseFilterColumn(filterSuggestions[activeFilterSuggestion]?.name ?? filterSuggestions[0].name); return; }
              setFilterSuggestionsOpen(false); void applyFilter();
            }
          }}
        />
        {filterSuggestionsOpen && filterSuggestions.length > 0 && <div className="preview-filter-suggestions">{filterSuggestions.map((column, index) => <button type="button" key={column.name} className={index === activeFilterSuggestion ? "active" : ""} onMouseDown={event => event.preventDefault()} onMouseEnter={() => setActiveFilterSuggestion(index)} onClick={() => chooseFilterColumn(column.name)}>{column.name}</button>)}</div>}
      </div>
      {previewFilter && <button className="clear-preview-filter" onClick={() => void applyFilter(true)} disabled={previewLoading} title="清除筛选"><X size={15} />清除</button>}
      <button className="apply-preview-filter" onClick={() => void applyFilter()} disabled={previewLoading}>筛选</button>
    </div>
    {previewError && <div className="preview-error">{previewError}</div>}
    <DataGrid table={{ ...previewWithRemarks!, truncated: false }} draftRows={draftRows.map(row => row.values)} rowOffset={(preview.page - 1) * preview.pageSize} selectedRows={selectedRows} pendingDeleteRows={pendingDeleteRows} onToggleRow={toggleRowSelection} onColumnFilter={column => void filterColumn(column.name)} filteredColumns={filteredColumns} onEditCell={(rowIndex, column, value) => updateCell(rowIndex, preview.columns.findIndex(item => item.name === column.name || item.label === column.label), column.name, value)} onDeleteRow={rowIndex => markRowsForDeletion(selectedRows.has(rowIndex) ? selectedRows : [rowIndex])} canDeleteRow={rowIndex => canDeleteRows([rowIndex])} deleteRowLabel={rowIndex => rowIndex >= preview.rows.length ? "移除新增行" : pendingDeleteRows.has(rowIndex) ? "取消删除标记" : "标记删除（保存后生效）"} isColumnEditable={(column, rowIndex) => {
      const columnEditable = previewColumnEditable(result.details?.columns.find(item => item.name.toLowerCase() === column.name.toLowerCase() || item.name.toLowerCase() === column.label.toLowerCase()));
      // New rows are inserted, so they may fill key columns themselves.
      if (rowIndex >= preview.rows.length) return columnEditable;
      return !primaryKeyColumns.some(key => key.toLowerCase() === column.name.toLowerCase() || key.toLowerCase() === column.label.toLowerCase()) && columnEditable;
    }} editedCellKeys={new Set(Object.keys(pendingEdits))} />
    {saveError && <div className="grid-edit-error">{saveError}</div>}
    <div className="preview-pagination">
      <span>共 {preview.totalRows} 条 · 每页 {preview.pageSize} 条</span>
      <div className="preview-pagination-actions">
        <button onClick={() => void goToPage(1)} disabled={previewLoading || hasLocalChanges || preview.page === 1} title="首页"><ChevronsLeft size={15} /></button>
        <button onClick={() => void goToPage(preview.page - 1)} disabled={previewLoading || hasLocalChanges || preview.page === 1} title="上一页"><ChevronLeft size={15} /></button>
        <label>第 <input value={pageInput} inputMode="numeric" disabled={hasLocalChanges} onChange={event => setPageInput(event.target.value.replace(/[^0-9]/g, ""))} onKeyDown={event => { if (event.key === "Enter") void goToPage(Number(pageInput)); }} /> / {Math.max(1, Math.ceil(preview.totalRows / preview.pageSize))} 页</label>
        <button onClick={() => void goToPage(preview.page + 1)} disabled={previewLoading || hasLocalChanges || preview.page >= Math.max(1, Math.ceil(preview.totalRows / preview.pageSize))} title="下一页"><ChevronRight size={15} /></button>
        <button onClick={() => void goToPage(Math.ceil(preview.totalRows / preview.pageSize))} disabled={previewLoading || hasLocalChanges || preview.page >= Math.max(1, Math.ceil(preview.totalRows / preview.pageSize))} title="末页"><ChevronsRight size={15} /></button>
        {hasLocalChanges && <button className="save-grid-changes" onClick={() => void saveChanges()} disabled={savingChanges}>{savingChanges ? "保存中…" : `保存 ${pendingSaveCount} 项`}</button>}
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
