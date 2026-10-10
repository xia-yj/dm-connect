// @vitest-environment node
import { describe, expect, it } from "vitest";
import { errorMessage } from "./api";

describe("errorMessage", () => {
  it("unwraps Electron IPC invoke errors so the status bar shows the real cause", () => {
    expect(errorMessage(new Error("Error invoking remote method 'update:check': Error: 读取更新清单失败（HTTP 403）")))
      .toBe("读取更新清单失败（HTTP 403）");
  });

  it("keeps ordinary error messages unchanged", () => {
    expect(errorMessage(new Error("连接失败"))).toBe("连接失败");
  });
});
