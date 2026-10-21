import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { ObjectLoadResult } from "../types";
import { ObjectView } from "./ObjectView";

const object = { schema: "T", name: "M", kind: "TABLE" as const, remarks: "" };
const result: ObjectLoadResult = {
  object, detailsError: "", previewError: "", ddl: null, ddlError: "",
  details: {
    table: object,
    columns: [
      { ordinal: 1, name: "ID", typeName: "BIGINT", size: 10, scale: 0, nullable: false, defaultValue: null, autoIncrement: true, onUpdateExpression: null, remarks: null },
      { ordinal: 2, name: "NAME", typeName: "VARCHAR", size: 10, scale: 0, nullable: false, defaultValue: null, autoIncrement: false, onUpdateExpression: null, remarks: null },
      { ordinal: 3, name: "N", typeName: "INT", size: 10, scale: 0, nullable: true, defaultValue: null, autoIncrement: false, onUpdateExpression: null, remarks: null }
    ],
    constraints: [{ name: "PK", type: "PRIMARY KEY", columns: ["ID"], referencedSchema: null, referencedTable: null, referencedColumns: [] }],
    indexes: []
  },
  preview: {
    columns: [
      { name: "ID", label: "ID", typeName: "BIGINT", jdbcType: -5, nullable: false },
      { name: "NAME", label: "NAME", typeName: "VARCHAR", jdbcType: 12, nullable: false },
      { name: "N", label: "N", typeName: "INT", jdbcType: 4, nullable: true }
    ],
    rows: [[1, "a", null]], truncated: false, totalRows: 1, page: 1, pageSize: 100
  }
};

function addDraftAndGetNameCell(container: HTMLElement) {
  fireEvent.click(screen.getByRole("button", { name: "新增行" }));
  return container.querySelectorAll("tbody tr:last-child td")[2] as HTMLElement;
}

function renderView(view: ObjectLoadResult = result, onSaveChanges = vi.fn().mockResolvedValue(undefined)) {
  return render(<ObjectView result={view} onLoadPreview={vi.fn().mockResolvedValue(view.preview)} onSaveChanges={onSaveChanges} onDeleteRow={vi.fn()} />);
}

async function editCell(cell: HTMLElement, text: string) {
  const user = userEvent.setup();
  await user.dblClick(cell);
  await user.clear(document.activeElement as HTMLInputElement);
  await user.keyboard(`${text}{Enter}`);
}

describe("editing cells of a new row", () => {
  it("opens the editor with a single click", async () => {
    const { container } = renderView();
    const cell = addDraftAndGetNameCell(container);
    const user = userEvent.setup();
    await user.click(cell);
    const input = container.querySelector("input.grid-cell-editor") as HTMLInputElement;
    expect(input).not.toBeNull();
    expect(document.activeElement).toBe(input);
    await user.keyboard("hello");
    expect(input.value).toBe("hello");
  });

  it("starts editing with the first typed character after the cell is selected", async () => {
    const { container } = renderView();
    const cell = addDraftAndGetNameCell(container);
    const user = userEvent.setup();
    cell.focus();
    await user.keyboard("hi");
    const input = container.querySelector("input.grid-cell-editor") as HTMLInputElement;
    expect(input.value).toBe("hi");
  });

  it("does not open the editor for existing rows on a single click", async () => {
    const { container } = renderView();
    const user = userEvent.setup();
    await user.click(container.querySelectorAll("tbody tr:first-child td")[2] as HTMLElement);
    expect(container.querySelector("input.grid-cell-editor")).toBeNull();
  });

  it("locates rows of a table without a primary key by their original values", async () => {
    const withoutKey: ObjectLoadResult = { ...result, details: { ...result.details!, constraints: [] } };
    const onSaveChanges = vi.fn().mockResolvedValue(undefined);
    const { container } = renderView(withoutKey, onSaveChanges);
    const cells = () => container.querySelectorAll("tbody tr:first-child td");
    await editCell(cells()[2] as HTMLElement, "x");
    await editCell(cells()[3] as HTMLElement, "5");
    fireEvent.click(screen.getByRole("button", { name: "保存修改" }));
    await waitFor(() => expect(onSaveChanges).toHaveBeenCalledWith([
      { type: "UPDATE", column: "NAME", value: "x", keyValues: { ID: 1, NAME: "a", N: null } },
      { type: "UPDATE", column: "N", value: "5", keyValues: { ID: 1, NAME: "x", N: null } }
    ]));
  });

  it("prefers a unique index over the whole row when the table has no primary key", async () => {
    const withUnique: ObjectLoadResult = { ...result, details: { ...result.details!, constraints: [], indexes: [{ name: "UK_NAME", unique: true, columns: ["NAME"] }] } };
    const onSaveChanges = vi.fn().mockResolvedValue(undefined);
    const { container } = renderView(withUnique, onSaveChanges);
    await editCell(container.querySelectorAll("tbody tr:first-child td")[3] as HTMLElement, "7");
    fireEvent.click(screen.getByRole("button", { name: "保存修改" }));
    await waitFor(() => expect(onSaveChanges).toHaveBeenCalledWith([{ type: "UPDATE", column: "N", value: "7", keyValues: { NAME: "a" } }]));
  });

  it("edits and removes new rows of a table without a primary key", async () => {
    const withoutKey: ObjectLoadResult = { ...result, details: { ...result.details!, constraints: [] } };
    const { container } = renderView(withoutKey);
    const user = userEvent.setup();
    const cell = addDraftAndGetNameCell(container);
    await user.click(cell);
    await user.keyboard("new");
    expect((container.querySelector("input.grid-cell-editor") as HTMLInputElement).value).toBe("new");
    expect(container.querySelectorAll("tbody tr:last-child td")[1]).not.toHaveClass("editable-cell");

    fireEvent.contextMenu(container.querySelector("tbody tr:last-child") as HTMLElement);
    fireEvent.click(screen.getByRole("button", { name: "移除新增行" }));
    expect(container.querySelectorAll("tbody tr")).toHaveLength(1);
  });
});
