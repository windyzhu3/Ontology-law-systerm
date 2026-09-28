import { useId } from "react";
import { progressChoices, type OpportunityValues } from "./opportunityProgress";

import { localTime } from "./localTime";

/** Approved HF E: summary first, then progress type and next time in the same card. */
export function OpportunityProgressForm({ values, disabled, onChange }: {
  values: Partial<OpportunityValues>;
  disabled: boolean;
  onChange: (name: keyof OpportunityValues, value: string) => void;
}) {
  const id = useId();
  return <>
    <div className="form-field">
      <label htmlFor={`${id}-summary`}>进展摘要 *</label>
      <textarea id={`${id}-summary`} value={values.progressSummary ?? ""} disabled={disabled} aria-required rows={3} onChange={e => onChange("progressSummary", e.target.value)} />
    </div>
    <div className="opportunity-field-row">
    <div className="form-field">
      <label htmlFor={`${id}-type`}>本次有效进展 *</label>
      <select id={`${id}-type`} value={values.progressTypeCode ?? ""} disabled={disabled} aria-required onChange={e => onChange("progressTypeCode", e.target.value)}>
        <option value="" disabled>请选择</option>
        {progressChoices.map(([code, label]) => <option key={code} value={code}>{label}</option>)}
      </select>
    </div>
    <div className="form-field">
      <label htmlFor={`${id}-next`}>下一次跟进时间 *</label>
      <input id={`${id}-next`} type="datetime-local" value={localTime(values.nextCheckAt)} disabled={disabled} aria-required aria-describedby={`${id}-zone`} onChange={e => {
        const text = e.target.value;
        const date = new Date(text);
        onChange("nextCheckAt", text && Number.isFinite(date.getTime()) && localTime(date.toISOString()) === text ? date.toISOString() : "");
      }} />
      <p className="form-hint" id={`${id}-zone`}>按当前设备时区填写（{Intl.DateTimeFormat().resolvedOptions().timeZone}）。</p>
    </div>
    </div>
    <p className="form-hint">未接通电话、内部备注和草稿不计为有效进展。</p>
  </>;
}
