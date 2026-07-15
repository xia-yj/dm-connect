import { X } from "lucide-react";
import type { ReactNode } from "react";

interface ModalProps {
  title: string;
  description?: string;
  children: ReactNode;
  footer?: ReactNode;
  wide?: boolean;
  onClose?: () => void;
}

export function Modal({ title, description, children, footer, wide, onClose }: ModalProps) {
  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={event => {
      if (event.currentTarget === event.target) onClose?.();
    }}>
      <section className={`modal-card${wide ? " modal-wide" : ""}`} role="dialog" aria-modal="true" aria-label={title}>
        <header className="modal-header">
          <div>
            <h2>{title}</h2>
            {description && <p>{description}</p>}
          </div>
          {onClose && <button className="icon-button ghost" onClick={onClose} aria-label="关闭"><X size={18} /></button>}
        </header>
        <div className="modal-body">{children}</div>
        {footer && <footer className="modal-footer">{footer}</footer>}
      </section>
    </div>
  );
}

interface ConfirmProps {
  title: string;
  message: ReactNode;
  confirmText?: string;
  danger?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export function ConfirmModal({ title, message, confirmText = "确认", danger, onConfirm, onCancel }: ConfirmProps) {
  return (
    <Modal title={title} onClose={onCancel} footer={
      <>
        <button className="button secondary" onClick={onCancel}>取消</button>
        <button className={`button ${danger ? "danger" : "primary"}`} onClick={onConfirm}>{confirmText}</button>
      </>
    }>
      <div className="confirm-message">{message}</div>
    </Modal>
  );
}
