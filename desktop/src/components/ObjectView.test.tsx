import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { ObjectLoadResult, PagedResultTable } from "../types";
import { ObjectView } from "./ObjectView";

const preview: PagedResultTable = {
  columns: [{ name: "ID", label: "ID", typeName: "INT", jdbcType: 4, nullable: false }],
  rows: [[47]],
  truncated: false,
  totalRows: 1,
  page: 1,
  pageSize: 100
};

const result: ObjectLoadResult = {
  object: { schema: "TEST", name: "CONFIGURATION", kind: "TABLE", remarks: "" },
  details: null,
  detailsError: "",
  preview,
  previewError: "",
  ddl: null,
  ddlError: ""
};

const editableResult: ObjectLoadResult = {
  ...result,
  details: {
    table: result.object,
    columns: [{ ordinal: 1, name: "ID", typeName: "INT", size: 10, scale: 0, nullable: false, defaultValue: null, autoIncrement: false, onUpdateExpression: null, remarks: null }],
    constraints: [{ name: "PK_CONFIGURATION", type: "PRIMARY KEY", columns: ["ID"], referencedSchema: null, referencedTable: null, referencedColumns: [] }],
    indexes: []
  },
  preview: { ...preview, rows: [[47], [48]], totalRows: 2 }
};

describe("ObjectView preview filter", () => {
  it("loads unfiltered data when the filter input is empty", async () => {
    const onLoadPreview = vi.fn().mockResolvedValue(preview);
    render(<ObjectView
      result={result}
      onLoadPreview={onLoadPreview}
      onSaveChanges={vi.fn()}
      onDeleteRow={vi.fn()}
    />);

    const filterButton = screen.getByRole("button", { name: "筛选" });
    expect(filterButton).toBeEnabled();
    fireEvent.click(filterButton);

    await waitFor(() => expect(onLoadPreview).toHaveBeenCalledWith(1, null));
    expect(screen.queryByText(/\u7b5b\u9009\u683c\u5f0f\u5e94\u4e3a/)).not.toBeInTheDocument();
  });

  it("stages a new row locally and supports undo", () => {
    const onLoadPreview = vi.fn().mockResolvedValue(preview);
    const { container } = render(<ObjectView result={result} onLoadPreview={onLoadPreview} onSaveChanges={vi.fn().mockResolvedValue(undefined)} onDeleteRow={vi.fn()} />);

    fireEvent.click(screen.getByRole("button", { name: "新增行" }));
    expect(screen.getByRole("button", { name: "保存修改" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "撤销草稿" })).toBeEnabled();
    expect(container.querySelectorAll("tbody tr")).toHaveLength(2);
    expect(container.querySelector("tbody tr:last-child")).toHaveClass("draft-row");
    fireEvent.click(screen.getByRole("button", { name: "撤销草稿" }));
    expect(screen.getByRole("button", { name: "保存修改" })).toBeDisabled();
    expect(container.querySelectorAll("tbody tr")).toHaveLength(1);
  });

  it("marks multiple rows for deletion locally and only deletes them on save", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    const onLoadPreview = vi.fn().mockResolvedValue(editableResult.preview);
    const onSaveChanges = vi.fn().mockResolvedValue(undefined);
    const { container } = render(<ObjectView result={editableResult} onLoadPreview={onLoadPreview} onSaveChanges={onSaveChanges} onDeleteRow={vi.fn()} />);

    const rows = container.querySelectorAll("tbody tr");
    fireEvent.click(rows[0]);
    fireEvent.click(rows[1], { ctrlKey: true });
    fireEvent.click(screen.getByRole("button", { name: "删除选中行" }));

    expect(confirm).not.toHaveBeenCalled();
    expect(container.querySelectorAll("tbody tr")).toHaveLength(2);
    expect(container.querySelectorAll("tbody tr.pending-delete-row")).toHaveLength(2);
    expect(screen.getByText("已标记删除 2 行，保存后生效")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "保存修改" }));
    await waitFor(() => expect(onSaveChanges).toHaveBeenCalledWith([
      { type: "DELETE", keyValues: { ID: 47 } },
      { type: "DELETE", keyValues: { ID: 48 } }
    ]));
    await waitFor(() => expect(container.querySelectorAll("tbody tr.pending-delete-row")).toHaveLength(0));
    confirm.mockRestore();
  });
});
