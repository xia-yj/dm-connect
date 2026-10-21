import { AlertTriangle, Filter, Rows3, Trash2 } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { ColumnMetadata, ResultTable } from "../types";

/** Soft cap for what we paint into each cell; full text stays in title/edit. */
export const CELL_DISPLAY_MAX_CHARS = 200;

export function displayValue(value: unknown): string {
  if (value === null || value === undefined) return "NULL";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value).replace(/^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}:\d{2}(?:\.\d+)?)(Z|[+-]\d{2}:?\d{2})?$/, "$1 $2$3");
}

/** Short label for grid cells so wide/long results do not flood the DOM. */
export function displayCellValue(value: unknown, maxChars = CELL_DISPLAY_MAX_CHARS): string {
  const full = displayValue(value);
  if (full.length <= maxChars) return full;
  return `${full.slice(0, maxChars)}…`;
}

function editorValue(value: unknown): string {
  if (value === null || value === undefined) return "";
  return typeof value === "object" ? JSON.stringify(value) : String(value);
}

interface DataGridProps {
  table: ResultTable;
  rowOffset?: number;
  onEditCell?: (rowIndex: number, column: ColumnMetadata, value: string | null) => void;
  isColumnEditable?: (column: ColumnMetadata, rowIndex: number) => boolean;
  editedCellKeys?: Set<string>;
  onDeleteRow?: (rowIndex: number) => void;
  canDeleteRow?: (rowIndex: number) => boolean;
  deleteRowLabel?: (rowIndex: number) => string;
  draftRows?: unknown[][];
  selectedRows?: ReadonlySet<number>;
  pendingDeleteRows?: ReadonlySet<number>;
  onToggleRow?: (rowIndex: number, additive: boolean) => void;
  onColumnFilter?: (column: ColumnMetadata) => void;
  filteredColumns?: Set<string>;
}

export function DataGrid({ table, rowOffset = 0, onEditCell, isColumnEditable = () => true, editedCellKeys = new Set(), onDeleteRow, canDeleteRow, deleteRowLabel, draftRows = [], selectedRows, pendingDeleteRows = new Set(), onToggleRow, onColumnFilter, filteredColumns = new Set() }: DataGridProps) {
  const [editing, setEditing] = useState<{ rowIndex: number; columnIndex: number; value: string } | null>(null);
  const [internalSelectedRows, setInternalSelectedRows] = useState<Set<number>>(new Set());
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; rowIndex: number } | null>(null);
  const contextMenuRef = useRef<HTMLDivElement | null>(null);
  const selectedCellRef = useRef<HTMLTableCellElement | null>(null);
  const activeSelectedRows = selectedRows ?? internalSelectedRows;

  useEffect(() => {
    setInternalSelectedRows(new Set());
    setContextMenu(null);
  }, [table.rows]);

  useEffect(() => {
    if (!contextMenu) return;
    const close = (event: MouseEvent) => {
      if (event.button !== 0 || contextMenuRef.current?.contains(event.target as Node)) return;
      setContextMenu(null);
    };
    const closeOnBlur = () => setContextMenu(null);
    window.addEventListener("mousedown", close);
    window.addEventListener("blur", closeOnBlur);
    return () => { window.removeEventListener("mousedown", close); window.removeEventListener("blur", closeOnBlur); };
  }, [contextMenu]);

  function selectCell(cell: HTMLTableCellElement) {
    if (selectedCellRef.current === cell) return;
    selectedCellRef.current?.classList.remove("selected-cell");
    cell.classList.add("selected-cell");
    selectedCellRef.current = cell;
  }
  function commitEdit() {
    if (!editing || !onEditCell) return;
    onEditCell(editing.rowIndex, table.columns[editing.columnIndex], editing.value.trim().toUpperCase() === "NULL" ? null : editing.value);
    setEditing(null);
  }
  function toggleRow(rowIndex: number, additive: boolean) {
    if (onToggleRow) {
      onToggleRow(rowIndex, additive);
      return;
    }
    setInternalSelectedRows(current => {
      if (!additive) return new Set([rowIndex]);
      const next = new Set(current);
      if (next.has(rowIndex)) next.delete(rowIndex);
      else next.add(rowIndex);
      return next;
    });
  }
  const hasRemarks = table.columns.some(column => Boolean(column.remarks?.trim()));

  return (
    <div className="data-grid-shell">
      <div className="data-grid-scroll">
        <table className="data-grid">
          <thead><tr><th className="row-number">#</th>{table.columns.map((column, index) => <th key={`${column.label}-${index}`} title={`${column.remarks ? `${column.remarks} · ` : ""}${column.typeName}${column.nullable ? " · 可空" : " · 非空"}`}>{hasRemarks && <small className="column-remark">{column.remarks?.trim() || "—"}</small>}<span className="grid-column-heading"><span>{column.label}</span>{onColumnFilter && <button type="button" className={filteredColumns.has(column.name) || filteredColumns.has(column.label) ? "active" : ""} title={`筛选 ${column.label}`} aria-label={`筛选 ${column.label}`} onClick={event => { event.stopPropagation(); onColumnFilter(column); }}><Filter size={12} /></button>}</span><small>{column.typeName}</small></th>)}</tr></thead>
          <tbody>
            {[...table.rows, ...draftRows].map((row, rowIndex) => {
              const isDraftRow = rowIndex >= table.rows.length;
              const isPendingDelete = pendingDeleteRows.has(rowIndex);
              const isSelected = activeSelectedRows.has(rowIndex);
              const rowClassName = [isSelected ? "selected-row" : "", isDraftRow ? "draft-row" : "", isPendingDelete ? "pending-delete-row" : ""].filter(Boolean).join(" ");
              return <tr key={rowIndex} className={rowClassName} aria-selected={isSelected} onClick={event => { toggleRow(rowIndex, event.metaKey || event.ctrlKey); setContextMenu(null); }} onContextMenu={event => { event.preventDefault(); toggleRow(rowIndex, activeSelectedRows.has(rowIndex) || event.metaKey || event.ctrlKey); setContextMenu({ x: event.clientX, y: event.clientY, rowIndex }); }}><td className="row-number">{rowOffset + rowIndex + 1}</td>{row.map((value, columnIndex) => {
              const isEditing = editing?.rowIndex === rowIndex && editing.columnIndex === columnIndex;
              const cellKey = `${rowIndex}:${columnIndex}`;
              const editable = Boolean(onEditCell) && !isPendingDelete && isColumnEditable(table.columns[columnIndex], rowIndex);
              const fullText = value == null ? "NULL" : displayValue(value);
              const title = isPendingDelete ? "已标记删除，保存后生效" : (editable ? (fullText === "NULL" ? "双击编辑" : `双击编辑\n${fullText}`) : fullText);
              return <td key={columnIndex} className={`${editable ? "editable-cell" : ""}${editedCellKeys.has(cellKey) ? " edited-cell" : ""}`} title={title} tabIndex={editable ? -1 : undefined} onClick={event => {
                selectCell(event.currentTarget);
                if (editable && isDraftRow && !isEditing) setEditing({ rowIndex, columnIndex, value: editorValue(value) });
              }} onDoubleClick={() => {
                if (editable && !isEditing) setEditing({ rowIndex, columnIndex, value: editorValue(value) });
              }} onKeyDown={event => {
                if (!editable || isEditing || event.target !== event.currentTarget || event.metaKey || event.ctrlKey || event.altKey) return;
                if (event.key === "Enter" || event.key === "F2") { event.preventDefault(); setEditing({ rowIndex, columnIndex, value: editorValue(value) }); }
                else if (event.key.length === 1) { event.preventDefault(); setEditing({ rowIndex, columnIndex, value: event.key }); }
              }}>
                {isEditing
                  ? <input className="grid-cell-editor" autoFocus value={editing.value} onChange={event => setEditing({ ...editing, value: event.target.value })} onKeyDown={event => { if (event.key === "Enter") commitEdit(); if (event.key === "Escape") setEditing(null); }} onBlur={commitEdit} />
                  : <span className={value == null ? "null-value" : ""}>{isDraftRow && (value == null || value === "") ? "" : displayCellValue(value)}</span>}
              </td>;
            })}</tr>;
            })}
          </tbody>
        </table>
        {table.rows.length === 0 && draftRows.length === 0 && <div className="empty-grid"><Rows3 size={24} /><span>查询成功，没有返回数据行</span></div>}
      </div>
      {contextMenu && <div ref={contextMenuRef} className="grid-context-menu" style={{ left: contextMenu.x, top: contextMenu.y }} onMouseDown={event => event.stopPropagation()}>
        <button disabled={!onDeleteRow || (canDeleteRow != null && !canDeleteRow(contextMenu.rowIndex))} onClick={() => { onDeleteRow?.(contextMenu.rowIndex); setContextMenu(null); }}><Trash2 size={13} />{onDeleteRow && (canDeleteRow == null || canDeleteRow(contextMenu.rowIndex)) ? (deleteRowLabel?.(contextMenu.rowIndex) ?? "删除此行") : "无主键，无法删除"}</button>
      </div>}
      <div className={`grid-status${table.truncated ? " warning" : ""}`}>
        {table.truncated ? <AlertTriangle size={13} /> : <Rows3 size={13} />}
        <span>{table.rows.length + draftRows.length} 行{table.truncated ? " · 已达到结果上限，数据已截断" : ""}</span>
        {pendingDeleteRows.size > 0
          ? <small className="pending-delete-hint">已标记删除 {pendingDeleteRows.size} 行，保存后生效</small>
          : onEditCell && <small>双击编辑 · ⌘/Ctrl+单击多选 · ⌘S / Ctrl+S 保存</small>}
      </div>
    </div>
  );
}
