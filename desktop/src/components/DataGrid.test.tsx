import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { DataGrid, displayValue } from "./DataGrid";

describe("DataGrid", () => {
  it("明确区分 NULL 并显示结果截断状态", () => {
    render(<DataGrid table={{
      columns: [
        { name: "ID", label: "ID", typeName: "BIGINT", jdbcType: -5, nullable: false },
        { name: "NAME", label: "NAME", typeName: "VARCHAR", jdbcType: 12, nullable: true }
      ],
      rows: [["9007199254740993", null]],
      truncated: true
    }} />);

    expect(screen.getByText("9007199254740993")).toBeInTheDocument();
    expect(screen.getByText("NULL")).toHaveClass("null-value");
    expect(screen.getByText(/1 行 · 已达到结果上限/)).toBeInTheDocument();
  });

  it("可以安全显示对象类型的单元格值", () => {
    expect(displayValue({ summary: "BLOB (128 bytes)" })).toBe('{"summary":"BLOB (128 bytes)"}');
  });

  it("右键菜单不会被右键或菜单内部点击提前关闭", () => {
    const onDeleteRow = vi.fn();
    const { container } = render(<DataGrid table={{
      columns: [{ name: "ID", label: "ID", typeName: "INT", jdbcType: 4, nullable: false }],
      rows: [[1]],
      truncated: false
    }} onDeleteRow={onDeleteRow} />);

    fireEvent.contextMenu(container.querySelector("tbody tr")!, { clientX: 40, clientY: 40 });
    const deleteButton = screen.getByRole("button", { name: "删除此行" });
    expect(deleteButton).toBeVisible();

    fireEvent.mouseDown(window, { button: 2 });
    expect(deleteButton).toBeVisible();
    fireEvent.mouseDown(deleteButton, { button: 0 });
    expect(deleteButton).toBeVisible();
    fireEvent.click(deleteButton);
    expect(onDeleteRow).toHaveBeenCalledWith(0);
  });
});
