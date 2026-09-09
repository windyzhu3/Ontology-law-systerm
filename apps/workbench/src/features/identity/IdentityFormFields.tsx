import { useId, type ReactNode } from "react";
import type { useIdentityOptions } from "./useIdentityOptions";

export function IdentityField({ label, value, onChange, error, type = "text", children, disabled = false }: { label: string; value: string; onChange: (value: string) => void; error?: string; type?: string; children?: ReactNode; disabled?: boolean }) {
  const id = useId();
  const props = { id, value, disabled, "aria-invalid": !!error, "aria-describedby": error ? `${id}-error` : undefined, onChange: (event: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => onChange(event.target.value) };
  return <div className="identity-field"><label htmlFor={id}>{label}</label>{children ? <select {...props}>{children}</select> : <input {...props} type={type} />}{error && <p className="identity-field-error" id={`${id}-error`}>{error}</p>}</div>;
}

export function IdentityOptionField({ label, options, error, onDirty }: { label: string; options: ReturnType<typeof useIdentityOptions>; error?: string; onDirty: () => void }) {
  return <div className="identity-option-field">
    <IdentityField label={label} value={options.selected} disabled={options.loading} error={error} onChange={value => { options.select(value); onDirty(); }}>
      <option value="">请选择{label}</option>{options.items.map(item => <option key={item.id} value={item.id}>{item.label}</option>)}
    </IdentityField>
    {options.loading && <p role="status">正在读取{label}候选…</p>}
    {options.error && <p role="alert">{options.error}</p>}
    {!options.loading && !options.error && options.items.length === 0 && <p>本页没有可选的{label}。</p>}
    <div className="identity-option-pagination" aria-label={`${label}候选分页`}>
      <button type="button" disabled={!options.canPrevious} onClick={() => { options.previous(); onDirty(); }}>上一页候选</button>
      <button type="button" disabled={!options.canNext} onClick={() => { options.next(); onDirty(); }}>下一页候选</button>
      <button type="button" disabled={options.loading} onClick={() => { options.reload(); onDirty(); }}>重读候选</button>
    </div>
  </div>;
}
