import { useMemo, useState } from "react";
import { CheckCircle2, Database, FileUp, LoaderCircle, PlugZap } from "lucide-react";
import { rpc, errorMessage } from "../api";
import type { ConnectionProfile, DriverDescriptor, ProfileDraft } from "../types";
import { Modal } from "./Modal";

interface ConnectionModalProps {
  profile?: ConnectionProfile;
  drivers: DriverDescriptor[];
  onDriversChanged: (drivers: DriverDescriptor[]) => void;
  onSaved: () => void;
  onClose: () => void;
}

function initialDraft(profile?: ConnectionProfile, drivers: DriverDescriptor[] = []): ProfileDraft {
  return {
    id: profile?.id,
    name: profile?.name ?? "",
    host: profile?.host ?? "localhost",
    port: profile?.port ?? 5236,
    username: profile?.username ?? "",
    password: "",
    driverId: profile?.driverId ?? drivers[0]?.id ?? "",
    advancedProperties: profile?.advancedProperties ?? {},
    rememberPassword: profile?.rememberPassword ?? true
  };
}

function propertiesText(properties: Record<string, string>): string {
  return Object.entries(properties).map(([key, value]) => `${key}=${value}`).join("\n");
}

function parseProperties(source: string): Record<string, string> {
  const result: Record<string, string> = {};
  source.split(/\r?\n/).forEach((raw, index) => {
    const line = raw.trim();
    if (!line) return;
    const separator = line.indexOf("=");
    if (separator < 1) throw new Error(`高级参数第 ${index + 1} 行应为 key=value`);
    const key = line.slice(0, separator).trim();
    if (/^(user|password)$/i.test(key)) throw new Error("用户名和密码不能写入高级参数");
    result[key] = line.slice(separator + 1).trim();
  });
  return result;
}

export function ConnectionModal({ profile, drivers, onDriversChanged, onSaved, onClose }: ConnectionModalProps) {
  const [draft, setDraft] = useState(() => initialDraft(profile, drivers));
  const [advanced, setAdvanced] = useState(() => propertiesText(profile?.advancedProperties ?? {}));
  const [status, setStatus] = useState<{ type: "error" | "success" | "info"; text: string } | null>(null);
  const [busy, setBusy] = useState<"save" | "test" | "driver" | null>(null);
  const title = profile ? "编辑数据库连接" : "新建数据库连接";
  const selectedDriver = useMemo(() => drivers.find(item => item.id === draft.driverId), [drivers, draft.driverId]);

  function payload(): ProfileDraft {
    if (!draft.name.trim()) throw new Error("请输入连接名称");
    if (!draft.host.trim()) throw new Error("请输入数据库主机");
    if (!draft.username.trim()) throw new Error("请输入用户名");
    if (!draft.driverId) throw new Error("请先导入并选择 JDBC 驱动");
    return { ...draft, name: draft.name.trim(), host: draft.host.trim(), username: draft.username.trim(), advancedProperties: parseProperties(advanced) };
  }

  async function save(event: { preventDefault(): void }) {
    event.preventDefault();
    setStatus(null);
    setBusy("save");
    try {
      await rpc("profile.save", payload());
      onSaved();
    } catch (cause) {
      setStatus({ type: "error", text: errorMessage(cause) });
    } finally {
      setBusy(null);
    }
  }

  async function test() {
    setStatus(null);
    setBusy("test");
    try {
      await rpc("profile.test", payload());
      setStatus({ type: "success", text: "连接成功，驱动与数据库响应正常。" });
    } catch (cause) {
      setStatus({ type: "error", text: `连接失败：${errorMessage(cause)}` });
    } finally {
      setBusy(null);
    }
  }

  async function importDriver() {
    const path = await window.dmConnect.selectDriver();
    if (!path) return;
    setBusy("driver");
    setStatus({ type: "info", text: "正在校验 JDBC 驱动…" });
    try {
      const imported = await rpc<DriverDescriptor>("driver.import", { path });
      const refreshed = await rpc<DriverDescriptor[]>("driver.list");
      onDriversChanged(refreshed);
      setDraft(value => ({ ...value, driverId: imported.id }));
      setStatus({ type: "success", text: `已导入 ${imported.displayName}，驱动版本 ${imported.version}` });
    } catch (cause) {
      setStatus({ type: "error", text: errorMessage(cause) });
    } finally {
      setBusy(null);
    }
  }

  return (
    <Modal title={title} description="连接信息只保存在本机，密码可在本地加密保存。" onClose={onClose} wide footer={
      <>
        <button className="button secondary" onClick={onClose}>取消</button>
        <button className="button secondary" onClick={test} disabled={Boolean(busy) || drivers.length === 0}>
          {busy === "test" ? <LoaderCircle className="spin" size={16} /> : <PlugZap size={16} />}测试连接
        </button>
        <button className="button primary" onClick={save} disabled={Boolean(busy) || drivers.length === 0}>
          {busy === "save" && <LoaderCircle className="spin" size={16} />}保存连接
        </button>
      </>
    }>
      <form className="connection-form" onSubmit={save}>
        <section className="form-section">
          <div className="section-heading"><span className="section-icon"><Database size={17} /></span><div><h3>基本信息</h3><p>达梦 JDBC 默认端口为 5236</p></div></div>
          <div className="form-grid two-columns">
            <label className="field-label span-two">连接名称<input className="field-input" value={draft.name} onChange={event => setDraft({ ...draft, name: event.target.value })} placeholder="例如：生产环境 / 测试库" /></label>
            <label className="field-label">主机<input className="field-input mono" value={draft.host} onChange={event => setDraft({ ...draft, host: event.target.value })} placeholder="localhost 或 IP 地址" /></label>
            <label className="field-label">端口<input className="field-input mono" type="number" min={1} max={65535} value={draft.port} onChange={event => setDraft({ ...draft, port: Number(event.target.value) })} /></label>
            <label className="field-label">用户名<input className="field-input" value={draft.username} onChange={event => setDraft({ ...draft, username: event.target.value })} autoComplete="username" /></label>
            <label className="field-label">密码<input className="field-input" type="password" value={draft.password} onChange={event => setDraft({ ...draft, password: event.target.value })} placeholder={profile?.hasSavedPassword ? "留空则保留已保存密码" : "数据库密码"} autoComplete="new-password" /></label>
          </div>
          <label className="check-row"><input type="checkbox" checked={draft.rememberPassword} onChange={event => setDraft({ ...draft, rememberPassword: event.target.checked })} /><span><strong>在本机加密保存密码</strong><small>关闭后每次连接都需要重新输入</small></span></label>
        </section>

        <section className="form-section">
          <div className="section-heading"><span className="section-icon"><FileUp size={17} /></span><div><h3>JDBC 驱动</h3><p>驱动应尽量与达梦服务器版本保持一致</p></div></div>
          <div className="driver-picker">
            <select className="field-input" value={draft.driverId} onChange={event => setDraft({ ...draft, driverId: event.target.value })}>
              {drivers.length === 0 && <option value="">尚未导入驱动</option>}
              {drivers.map(driver => <option key={driver.id} value={driver.id}>{driver.displayName} · v{driver.version}</option>)}
            </select>
            <button type="button" className="button secondary" onClick={importDriver} disabled={Boolean(busy)}>
              {busy === "driver" ? <LoaderCircle className="spin" size={16} /> : <FileUp size={16} />}导入 JAR
            </button>
          </div>
          {selectedDriver && <div className="driver-meta"><CheckCircle2 size={14} />{selectedDriver.driverClass} · SHA-256 {selectedDriver.sha256.slice(0, 12)}…</div>}
        </section>

        <section className="form-section">
          <div className="section-heading"><span className="section-index">A</span><div><h3>高级 JDBC 参数</h3><p>可选，每行输入一个 key=value</p></div></div>
          <textarea className="field-textarea mono" value={advanced} onChange={event => setAdvanced(event.target.value)} rows={4} placeholder={"socketTimeout=30000\nssl=true"} />
        </section>
        {status && <div className={`form-status ${status.type}`} role="status">{status.text}</div>}
      </form>
    </Modal>
  );
}
