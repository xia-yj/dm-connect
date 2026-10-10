import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { TableDesignerModal } from "./TableDesignerModal";
import type { DatabaseObject, TableDetails } from "../types";

const object: DatabaseObject = { schema: "SMP", name: "ITEMS", kind: "TABLE", remarks: "" };
const details: TableDetails = {
  table: object,
  columns: [
    { ordinal: 1, name: "ID", typeName: "BIGINT", size: 19, scale: 0, nullable: false, defaultValue: null, autoIncrement: true, onUpdateExpression: null, remarks: "主键" },
    { ordinal: 2, name: "TITLE", typeName: "VARCHAR", size: 100, scale: 0, nullable: true, defaultValue: null, autoIncrement: false, onUpdateExpression: null, remarks: "标题" }
  ],
  constraints: [{ name: "PK_ITEMS", type: "PRIMARY KEY", columns: ["ID"], referencedSchema: null, referencedTable: null, referencedColumns: [] }],
  indexes: []
};

describe("TableDesignerModal", () => {
  it("shows delete-column actions and hides MySQL-only ON UPDATE for DM tables", () => {
    render(<TableDesignerModal
      databaseType="dm"
      object={object}
      details={details}
      schema="SMP"
      onClose={vi.fn()}
      onCreate={vi.fn()}
      onAlter={vi.fn()}
    />);

    expect(screen.getByText("操作")).toBeInTheDocument();
    expect(screen.getAllByTitle("删除字段")).toHaveLength(2);
    expect(screen.queryByText("自动更新")).not.toBeInTheDocument();
    expect(document.querySelector(".modal-xl")).not.toBeNull();
  });

  it("keeps ON UPDATE visible for MySQL and can drop a column from the draft", () => {
    render(<TableDesignerModal
      databaseType="mysql"
      object={object}
      details={details}
      schema="SMP"
      onClose={vi.fn()}
      onCreate={vi.fn()}
      onAlter={vi.fn()}
    />);

    expect(screen.getByText("自动更新")).toBeInTheDocument();
    fireEvent.click(screen.getAllByTitle("删除字段")[1]);
    expect(screen.queryByDisplayValue("TITLE")).not.toBeInTheDocument();
    expect(screen.getAllByTitle("删除字段")).toHaveLength(1);
  });
});
