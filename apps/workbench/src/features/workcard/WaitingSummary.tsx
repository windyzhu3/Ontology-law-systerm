import { Clock } from "@phosphor-icons/react/Clock";
import { Hourglass } from "@phosphor-icons/react/Hourglass";
import type { Schema } from "./contract";
export function WaitingSummary({
  next,
  count,
}: {
  next: Schema["NextSummary"][];
  count: number;
}) {
  return (
    <section className="waiting-strip" aria-label="后续责任与等待">
      <div className="next-summaries">
        {next.length === 0 ? (
          <p className="muted">暂无后续责任</p>
        ) : (
          next.map((item) => (
            <div className="next-summary" key={item.taskId}>
              <Clock size={20} aria-hidden="true" />
              <span>{item.timeHint}</span>
              <span>{item.businessPurpose.label}</span>
              {item.priority === "URGENT" && (
                <span className="priority">优先</span>
              )}
            </div>
          ))
        )}
      </div>
      <p className="waiting-count">
        <Hourglass size={25} aria-hidden="true" />
        <span>等待 {count}</span>
        <span className="muted">当前无需操作</span>
      </p>
    </section>
  );
}
