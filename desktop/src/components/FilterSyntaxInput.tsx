import { useEffect, useMemo, useRef, type KeyboardEventHandler } from "react";
import { tokenizePreviewFilter } from "../previewFilterHighlight";

interface FilterSyntaxInputProps {
  id?: string;
  value: string;
  placeholder?: string;
  "aria-label"?: string;
  onChange: (value: string) => void;
  onFocus?: () => void;
  onBlur?: () => void;
  onKeyDown?: KeyboardEventHandler<HTMLInputElement>;
}

export function FilterSyntaxInput({
  id,
  value,
  placeholder,
  "aria-label": ariaLabel,
  onChange,
  onFocus,
  onBlur,
  onKeyDown
}: FilterSyntaxInputProps) {
  const inputRef = useRef<HTMLInputElement | null>(null);
  const highlightRef = useRef<HTMLDivElement | null>(null);
  const tokens = useMemo(() => tokenizePreviewFilter(value), [value]);

  useEffect(() => {
    const input = inputRef.current;
    const highlight = highlightRef.current;
    if (!input || !highlight) return;
    const sync = () => {
      highlight.scrollLeft = input.scrollLeft;
    };
    sync();
    input.addEventListener("scroll", sync);
    return () => input.removeEventListener("scroll", sync);
  }, [value]);

  return (
    <div className={`filter-syntax-input${value ? " has-value" : ""}`}>
      <div ref={highlightRef} className="filter-syntax-highlight" aria-hidden="true">
        {tokens.length === 0
          ? <span className="filter-token-text">{" "}</span>
          : tokens.map((token, index) => (
              <span key={`${token.kind}-${index}`} className={`filter-token filter-token-${token.kind}`}>
                {token.text}
              </span>
            ))}
      </div>
      <input
        ref={inputRef}
        id={id}
        value={value}
        spellCheck={false}
        autoComplete="off"
        autoCorrect="off"
        autoCapitalize="off"
        placeholder={placeholder}
        aria-label={ariaLabel}
        onChange={event => onChange(event.target.value)}
        onFocus={onFocus}
        onBlur={onBlur}
        onKeyDown={onKeyDown}
        onScroll={event => {
          if (highlightRef.current) highlightRef.current.scrollLeft = event.currentTarget.scrollLeft;
        }}
      />
    </div>
  );
}
