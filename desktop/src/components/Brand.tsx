import { Database } from "lucide-react";

export function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <div className={`brand${compact ? " compact" : ""}`}>
      <span className="brand-mark"><Database size={compact ? 17 : 21} strokeWidth={2.4} /></span>
      <span className="brand-copy">
        <strong>数据库连接工具</strong>
        {!compact && <small>DM / MySQL 数据库工作台</small>}
      </span>
    </div>
  );
}
