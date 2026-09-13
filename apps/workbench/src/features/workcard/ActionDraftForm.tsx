import type { Card, Values } from "./contract";

interface Props {
  card: Card;
  values: Values;
  onChange: (name: string, value: string) => void;
  disabled: boolean;
}
export function ActionDraftForm({ card, values, onChange, disabled }: Props) {
  function field(name: string, fallback?: string) {
    const descriptor = card.commandForm.fields.find((f) => f.name === name);
    if (!descriptor && !fallback) return null;
    const label = descriptor?.label ?? fallback!;
    const required =
      name === "legalNeed"
        ? values.resultCode === "CONNECTED_VALID"
        : descriptor?.required;
    const id = `candidate-${name}`;
    const locked = disabled || descriptor?.readOnly;
    const value =
      typeof values[name] === "string" ? (values[name] as string) : "";
    const common = {
      id,
      name,
      value,
      disabled: locked,
      onChange: (
        event: React.ChangeEvent<
          HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement
        >,
      ) => onChange(name, event.target.value),
      "aria-required": required,
    };
    return (
      <div className="form-field" key={name}>
        <label htmlFor={id}>
          {label}
          {required ? " *" : ""}
        </label>
        {descriptor?.control === "SELECT" ? (
          <select {...common}>
            <option value="" disabled>
              请选择
            </option>
            {descriptor.options.map((option) => (
              <option
                key={option.value}
                value={option.value}
                disabled={option.disabled}
              >
                {option.label}
              </option>
            ))}
          </select>
        ) : name === "phone" || name === "email" ? (
          <input
            {...common}
            type={name === "phone" ? "tel" : "email"}
            autoComplete="off"
            placeholder={name === "phone" ? "+86 手机号码" : "填写电子邮箱"}
          />
        ) : (
          <textarea
            {...common}
            rows={name === "legalNeed" ? 4 : 3}
            maxLength={name === "legalNeed" ? 4000 : 1000}
          />
        )}
      </div>
    );
  }
  // Seven explicit registrations; server metadata only supplies authorized labels and choices.
  switch (card.taskType) {
    case "RESOLVE_LEAD_DUPLICATE":
      return (
        <>
          {field("decisionCode")}
          {field("rationaleSummary")}
        </>
      );
    case "COMPLETE_LEAD_INGRESS":
      return (
        <>
          <p className="form-hint">
            电话或邮箱至少填写一项；电话需包含国家区号。
          </p>
          <div className="contact-fields">
            {field("phone")}
            {field("email")}
          </div>
          {field("sourceCode")}
          {field("sourceSummary")}
        </>
      );
    case "ASSIGN_LEAD":
      return <>{field("ownerAppointmentId")}</>;
    case "RESOLVE_LEAD_ROUTING_GAP":
      return (
        <>
          {field("decisionCode")}
          {field("rationaleSummary")}
        </>
      );
    case "ACK_SOURCE_INTAKE_STOP_REQUEST":
      return <>{field("rationaleSummary")}</>;
    case "CONTACT_LEAD":
      return (
        <>
          {field("contactChannelCode")}
          {field("resultCode")}
          {field("resultSummary")}
          {values.resultCode === "CONNECTED_VALID" &&
            field("legalNeed", "法律需求")}
        </>
      );
    case "REVIEW_LEAD_VALIDITY":
      return (
        <>
          {field("decisionCode")}
          {field("rationaleSummary")}
        </>
      );
  }
}
