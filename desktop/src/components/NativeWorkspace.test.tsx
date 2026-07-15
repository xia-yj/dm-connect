import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { NativeWorkspace } from "./NativeWorkspace";
import type { ConnectionProfile } from "../types";

const redisProfile: ConnectionProfile = {
  id: "redis-1", name: "Redis", databaseType: "redis", host: "localhost", port: 6379,
  database: "", username: "", driverId: "builtin:redis", advancedProperties: {},
  rememberPassword: false, hasSavedPassword: false, connected: true
};

const mongoProfile: ConnectionProfile = {
  ...redisProfile, id: "mongo-1", name: "Mongo", databaseType: "mongo", port: 27017,
  database: "old_db", driverId: "builtin:mongo"
};

function installRequest(request: ReturnType<typeof vi.fn>) {
  Object.defineProperty(window, "dmConnect", { configurable: true, value: { request } });
}

describe("NativeWorkspace", () => {
  it("uses the returned Redis cursor for the next SCAN page", async () => {
    const request = vi.fn()
      .mockResolvedValueOnce({ keys: ["first"], cursor: "42", finished: false })
      .mockResolvedValueOnce({ keys: ["second"], cursor: "0", finished: true });
    installRequest(request);
    render(<NativeWorkspace profile={redisProfile} namespaces={["db0"]} />);

    fireEvent.click(screen.getByRole("button", { name: "扫描键" }));
    await screen.findByRole("button", { name: "first" });
    fireEvent.click(screen.getByRole("button", { name: "下一页" }));
    await screen.findByRole("button", { name: "second" });

    expect(request).toHaveBeenNthCalledWith(1, "redis.keys", expect.objectContaining({ cursor: "0", pattern: "*", count: 200 }));
    expect(request).toHaveBeenNthCalledWith(2, "redis.keys", expect.objectContaining({ cursor: "42" }));
  });

  it("retries a backend-classified dangerous command only after confirmation", async () => {
    const request = vi.fn()
      .mockResolvedValueOnce({ command: "SET", risk: "write", dangerous: true, executed: false, requiresConfirmation: true })
      .mockResolvedValueOnce({ command: "SET", risk: "write", dangerous: true, executed: true, requiresConfirmation: false, result: "OK" });
    installRequest(request);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<NativeWorkspace profile={redisProfile} namespaces={["db0"]} />);

    fireEvent.change(screen.getByLabelText("Redis 命令"), { target: { value: "SET key value" } });
    fireEvent.click(screen.getByRole("button", { name: "执行命令" }));

    await waitFor(() => expect(request).toHaveBeenCalledTimes(2));
    expect(request).toHaveBeenNthCalledWith(2, "redis.command", { profileId: "redis-1", command: "SET key value", confirmed: true });
    expect(screen.getByText('"OK"')).toBeInTheDocument();
  });

  it("ignores a stale collection response after switching Mongo profiles", async () => {
    let resolveOld!: (value: string[]) => void;
    const oldResponse = new Promise<string[]>(resolve => { resolveOld = resolve; });
    const request = vi.fn((_method: string, params: { profileId: string }) => params.profileId === "mongo-1"
      ? oldResponse : Promise.resolve(["new_collection"]));
    installRequest(request);
    const { rerender } = render(<NativeWorkspace profile={mongoProfile} namespaces={["old_db"]} />);

    const replacement = { ...mongoProfile, id: "mongo-2", name: "Mongo 2", database: "new_db" };
    rerender(<NativeWorkspace profile={replacement} namespaces={["new_db"]} />);
    await screen.findByRole("option", { name: "new_collection" });
    await act(async () => { resolveOld(["stale_collection"]); await oldResponse; });

    expect(screen.queryByRole("option", { name: "stale_collection" })).not.toBeInTheDocument();
  });
});
