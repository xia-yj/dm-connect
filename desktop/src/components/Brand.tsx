import { Database } from "lucide-react";

export function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <div className={`brand${compact ? " compact" : ""}`}>
      <span className="brand-mark"><Database size={compact ? 17 : 21} strokeWidth={2.4} /></span>
      <span className="brand-copy">
        <strong>DM Connect</strong>
        {!compact && <small>达梦数据库专业工作台</small>}
      </span>
    </div>
  );
}
