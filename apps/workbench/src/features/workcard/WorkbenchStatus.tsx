import type { useCurrentCard } from "./useCurrentCard";

type Status = Pick<
  ReturnType<typeof useCurrentCard>,
  | "error"
  | "message"
  | "pending"
  | "recoveryBlocked"
  | "recoveryMarker"
  | "receiptOutcome"
  | "recoveredCommandType"
  | "readFailed"
  | "loading"
  | "waitingAutoPaused"
  | "receiptAutoPaused"
  | "envelope"
>;

/** Presentation only: receipt certainty and read health come from the shared hook. */
export function WorkbenchStatus({ work }: { work: Status }) {
  const unresolved =
    !!work.pending || !!work.recoveryMarker || work.recoveryBlocked;
  const recorded =
    work.receiptOutcome !== null && work.receiptOutcome !== "REJECTED";
  const result =
    work.receiptOutcome === "REJECTED"
      ? "本次请求未被接受"
      : work.recoveredCommandType === "SAVE_ACTION_DRAFT"
        ? "候选已保存"
        : "结果已记录";
  let text = work.message;
  let alert = false;
  let success = recorded;
  if (unresolved) {
    text = work.readFailed
      ? "结果尚未确认，当前责任暂时无法读取。请核对原回执；请勿重复发起。"
      : work.error ?? "结果尚未确认，请先核对原回执；请勿重复发起。";
    alert = !!work.error;
    success = false;
  } else if (work.receiptOutcome !== null && work.readFailed) {
    text = work.loading
      ? `${result}，正在刷新当前责任…`
      : `${result}，当前责任刷新失败。可手动刷新。`;
    alert = !work.loading;
    success = false;
  } else if (work.error) {
    text = work.error;
    alert = true;
    success = false;
  } else if (recorded && work.loading) {
    text = `${result}，正在刷新当前责任…`;
  }
  const paused = unresolved
    ? work.receiptAutoPaused
      ? "自动回执查询已暂停，可手动查询原回执。"
      : null
    : work.waitingAutoPaused && (work.envelope?.waitingCount ?? 0) > 0
      ? "自动刷新已暂停，可手动刷新。"
      : null;
  return (
    <>
      {text && (
        <p
          className={`feedback${success ? " success" : ""}`}
          role={alert ? "alert" : "status"}
        >
          {text}
        </p>
      )}
      {paused && <p className="feedback" role="status">{paused}</p>}
    </>
  );
}
