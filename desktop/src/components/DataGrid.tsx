import { AlertTriangle, Rows3, Trash2 } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { ColumnMetadata, ResultTable } from "../types";

export function displayValue(value: unknown): string {
  if (value === null || value === undefined) return "NULL";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value).replace(/^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}:\d{2}(?:\.\d+)?)(Z|[+-]\d{2}:?\d{2})?$/, "$1 $2$3");
}

function editorValue(value: unknown): string {
  if (value === null || value === undefined) return "";
  return typeof value === "object" ? JSON.stringify(value) : String(value);
}

interface DataGridProps {
  table: ResultTable;
  rowOffset?: number;
  onEditCell?: (rowIndex: number, column: ColumnMetadata, value: string | null) => void;
  isColumnEditable?: (column: ColumnMetadata) => boolean;
  editedCellKeys?: Set<string>;
  onDeleteRow?: (rowIndex: number) => void;
}

export function DataGrid({ table, rowOffset = 0, onEditCell, isColumnEditable = () => true, editedCellKeys = new Set(), onDeleteRow }: DataGridProps) {
  const [editing, setEditing] = useState<{ rowIndex: number; columnIndex: number; value: string } | null>(null);
  const [selectedRowIndex, setSelectedRowIndex] = useState<number | null>(null);
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; rowIndex: number } | null>(null);
  const selectedCellRef = useRef<HTMLTableCellElement | null>(null);

  useEffect(() => {
    setSelectedRowIndex(null);
    setContextMenu(null);
  }, [table]);

  useEffect(() => {
    if (!contextMenu) return;
    const close = () => setContextMenu(null);
    window.addEventListener("mousedown", close);
    window.addEventListener("blur", close);
    return () => { window.removeEventListener("mousedown", close); window.removeEventListener("blur", close); };
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
  const hasRemarks = table.columns.some(column => Boolean(column.remarks?.trim()));

  return (
    <div className="data-grid-shell">
      <div className="data-grid-scroll">
        <table className="data-grid">
          <thead><tr><th className="row-number">#</th>{table.columns.map((column, index) => <th key={`${column.label}-${index}`} title={`${column.remarks ? `${column.remarks} · ` : ""}${column.typeName}${column.nullable ? " · 可空" : " · 非空"}`}>{hasRemarks && <small className="column-remark">{column.remarks?.trim() || "—"}</small>}<span>{column.label}</span><small>{column.typeName}</small></th>)}</tr></thead>
          <tbody>
            {table.rows.map((row, rowIndex) => <tr key={rowIndex} className={selectedRowIndex === rowIndex ? "selected-row" : ""} onClick={() => { setSelectedRowIndex(rowIndex); setContextMenu(null); }} onContextMenu={event => { event.preventDefault(); setSelectedRowIndex(rowIndex); setContextMenu({ x: event.clientX, y: event.clientY, rowIndex }); }}><td className="row-number">{rowOffset + rowIndex + 1}</td>{row.map((value, columnIndex) => {
              const isEditing = editing?.rowIndex === rowIndex && editing.columnIndex === columnIndex;
              const cellKey = `${rowIndex}:${columnIndex}`;
              const editable = Boolean(onEditCell) && isColumnEditable(table.columns[columnIndex]);
              return <td key={columnIndex} className={`${editable ? "editable-cell" : ""}${editedCellKeys.has(cellKey) ? " edited-cell" : ""}`} title={editable ? "双击编辑" : (value == null ? "NULL" : displayValue(value))} onClick={event => selectCell(event.currentTarget)} onDoubleClick={() => {
                if (editable) setEditing({ rowIndex, columnIndex, value: editorValue(value) });
              }}>
                {isEditing
                  ? <input className="grid-cell-editor" autoFocus value={editing.value} onChange={event => setEditing({ ...editing, value: event.target.value })} onKeyDown={event => { if (event.key === "Enter") commitEdit(); if (event.key === "Escape") setEditing(null); }} onBlur={commitEdit} />
                  : <span className={value == null ? "null-value" : ""}>{displayValue(value)}</span>}
              </td>;
            })}</tr>)}
          </tbody>
        </table>
        {table.rows.length === 0 && <div className="empty-grid"><Rows3 size={24} /><span>查询成功，没有返回数据行</span></div>}
      </div>
      {contextMenu && <div className="grid-context-menu" style={{ left: contextMenu.x, top: contextMenu.y }} onMouseDown={event => event.stopPropagation()}>
        <button disabled={!onDeleteRow} onClick={() => { onDeleteRow?.(contextMenu.rowIndex); setContextMenu(null); }}><Trash2 size={13} />{onDeleteRow ? "删除此行" : "无主键，无法删除"}</button>
      </div>}
      <div className={`grid-status${table.truncated ? " warning" : ""}`}>
        {table.truncated ? <AlertTriangle size={13} /> : <Rows3 size={13} />}
        <span>{table.rows.length} 行{table.truncated ? " · 已达到结果上限，数据已截断" : ""}</span>
        {onEditCell && <small>双击编辑 · 右键删除 · ⌘S / Ctrl+S 保存</small>}
      </div>
    </div>
  );
}
