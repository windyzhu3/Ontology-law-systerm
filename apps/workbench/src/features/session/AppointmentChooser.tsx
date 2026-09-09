import type { SessionContext, SessionController } from "./sessionController";
import { useEffect, useRef, useState } from "react";
import { Info } from "@phosphor-icons/react";
import {
  SessionProvider,
  useSessionController,
  useSessionSetupReady,
  useSessionState,
} from "./SessionProvider";
import "../../styles/tokens.css";
import "../../styles/login.css";
import "../../styles/choice.css";
export interface AppointmentChooserProps {
  controller: SessionController | null;
  onConfirmed?: (context: SessionContext) => void;
}
export function AppointmentChooser({
  controller,
  onConfirmed,
}: AppointmentChooserProps) {
  if (!controller)
    return (
      <ChoiceLayout>
        <h1 id="choice-title">请选择本次办理身份</h1>
        <p role="status">会话服务尚未配置，请联系律所管理员。</p>
        <button disabled>确认本次身份</button>
      </ChoiceLayout>
    );
  return (
    <SessionProvider controller={controller}>
      <ChoiceForm onConfirmed={onConfirmed} />
    </SessionProvider>
  );
}
function ChoiceLayout({ children }: { children: React.ReactNode }) {
  return (
    <main className="login-page choice-page">
      <section className="login-brand" aria-label="律所工作助手">
        <div className="login-brand-content">
          <div className="login-logo" aria-label="Logo 占位">
            LOGO
          </div>
          <p className="login-firm-name">律所名称</p>
          <p className="login-product-name">律所工作助手</p>
          <div className="login-brand-divider" aria-hidden="true" />
          <p className="login-tagline">专注当前责任，清晰完成每一步。</p>
        </div>
      </section>
      <section className="choice-action" aria-labelledby="choice-title">
        {children}
        <footer className="login-footer">仅限本所授权人员使用</footer>
      </section>
    </main>
  );
}
function ChoiceForm({
  onConfirmed,
}: Pick<AppointmentChooserProps, "onConfirmed">) {
  const controller = useSessionController(),
    state = useSessionState(),
    setup = useSessionSetupReady();
  const context = state.context;
  const [owner, setOwner] = useState(controller);
  const [sourceEpoch, setSourceEpoch] = useState<number | null>(null);
  const [own, setOwn] = useState("");
  const [mode, setMode] = useState<"own" | "delegated">("own");
  const [delegated, setDelegated] = useState("");
  const [busy, setBusy] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const [message, setMessage] = useState("");
  const active = useRef(true),
    flight = useRef(false),
    sequence = useRef(0),
    current = useRef(controller);
  const target = useRef<{ own: string; delegated: string | null } | null>(null);
  const cancelButton = useRef<HTMLButtonElement>(null),
    confirmButton = useRef<HTMLButtonElement>(null),
    ownSelect = useRef<HTMLSelectElement>(null),
    restoreFocus = useRef(false);
  current.current = controller;
  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
      sequence.current++;
    };
  }, []);
  useEffect(() => {
    if (state.switchConfirmation && !busy) cancelButton.current?.focus();
    else if (
      restoreFocus.current &&
      !state.switchConfirmation &&
      context &&
      !busy
    ) {
      (confirmButton.current?.disabled
        ? ownSelect.current
        : confirmButton.current
      )?.focus();
      restoreFocus.current = false;
    }
  }, [state.switchConfirmation, context, busy]);
  if (owner !== controller) {
    setOwner(controller);
    setSourceEpoch(null);
    setOwn("");
    setMode("own");
    setDelegated("");
    setBusy(false);
    setConfirmed(false);
    setMessage("");
    flight.current = false;
    sequence.current++;
    target.current = null;
  } else if (context && sourceEpoch !== state.identityEpoch) {
    setSourceEpoch(state.identityEpoch);
    setOwn(context.selectedAppointmentId ?? "");
    setMode(context.selectedOnBehalfAppointmentId ? "delegated" : "own");
    setDelegated(context.selectedOnBehalfAppointmentId ?? "");
    setConfirmed(false);
    setMessage("");
  } else if (
    !context &&
    !state.switchConfirmation &&
    !["INITIALIZING", "SELECTING"].includes(state.status) &&
    sourceEpoch !== null
  ) {
    setSourceEpoch(null);
    setOwn("");
    setMode("own");
    setDelegated("");
    setConfirmed(false);
    setMessage("");
    target.current = null;
  }
  let marker = null,
    storageFailed = false;
  try {
    marker = controller.recovery.read();
  } catch {
    storageFailed = true;
  }
  const enabled =
    setup && !busy && !storageFailed && !!context && !state.switchConfirmation;
  const established =
    !!context?.selectedAppointmentId && own === context.selectedAppointmentId;
  const ownCandidate = !!context?.appointmentChoices.some((c) => c.id === own);
  const canDelegate =
    established && !!context?.delegatedAppointmentChoices.length;
  const targetValid =
    mode === "own" ||
    !!context?.delegatedAppointmentChoices.some((c) => c.id === delegated);
  const ownLabel = context?.appointmentChoices.find((c) => c.id === own)?.label;
  const delegatedLabel = context?.delegatedAppointmentChoices.find(
    (c) => c.id === delegated,
  )?.label;
  async function run(
    operation: () => Promise<void> | void,
    after?: () => void,
  ) {
    if (flight.current || !setup) return;
    flight.current = true;
    const serial = ++sequence.current;
    setBusy(true);
    setMessage("");
    try {
      const pendingOperation = operation();
      const epoch = controller.getSnapshot().identityEpoch;
      await pendingOperation;
      if (
        active.current &&
        current.current === controller &&
        serial === sequence.current &&
        controller.getSnapshot().identityEpoch === epoch
      )
        after?.();
    } catch {
      if (
        active.current &&
        current.current === controller &&
        serial === sequence.current
      )
        setMessage("身份核对未完成，请重试；不会自动改用其他身份。");
    } finally {
      if (
        active.current &&
        current.current === controller &&
        serial === sequence.current
      ) {
        flight.current = false;
        setBusy(false);
      }
    }
  }
  function emitConfirmed() {
    controller.checkLifetime();
    const latest = controller.getSnapshot(),
      selected = latest.context,
      expected = target.current;
    if (latest.switchConfirmation) return;
    if (
      latest.status !== "READY" ||
      !selected ||
      !expected ||
      selected.selectedAppointmentId !== expected.own ||
      selected.selectedOnBehalfAppointmentId !== expected.delegated
    )
      return;
    const pending = controller.recovery.read();
    if (pending && pending.actorScopeKey !== selected.actorScopeKey)
      throw new Error();
    setSourceEpoch(latest.identityEpoch);
    setOwn(selected.selectedAppointmentId!);
    setMode(selected.selectedOnBehalfAppointmentId ? "delegated" : "own");
    setDelegated(selected.selectedOnBehalfAppointmentId ?? "");
    setConfirmed(true);
    setMessage("本次身份已确认。");
    onConfirmed?.(selected);
  }
  function chooseOwn(value: string) {
    if (!enabled || !context?.appointmentChoices.some((c) => c.id === value))
      return;
    setOwn(value);
    setMode("own");
    setDelegated("");
    setConfirmed(false);
    setMessage("");
  }
  function confirm() {
    if (!enabled || !established || !ownCandidate || !targetValid || confirmed)
      return;
    target.current = {
      own,
      delegated: mode === "delegated" ? delegated : null,
    };
    void run(
      () => controller.selectOnBehalfAppointment(target.current!.delegated),
      emitConfirmed,
    );
  }
  const ended =
    !context &&
    !state.switchConfirmation &&
    !["INITIALIZING", "SELECTING"].includes(state.status);
  const status = storageFailed
    ? "恢复线索存储不可用，结果尚未确认，不能继续。"
    : ended
      ? (state.message ?? "请先登录后核对本次身份。")
      : message ||
        (busy || !setup || state.status === "INITIALIZING"
          ? "正在核对身份，请稍候。"
          : context?.state === "NO_APPOINTMENT"
            ? "当前没有可用本人任职，请联系律所管理员。"
            : !context && !state.switchConfirmation
              ? (state.message ?? "请先登录后核对本次身份。")
              : "");
  return (
    <ChoiceLayout>
      <div className="choice-account">
        <span>{context?.displayName ?? ""}</span>
        <button
          type="button"
          disabled={!setup || state.status === "SIGNED_OUT"}
          onClick={() => {
            sequence.current++;
            flight.current = false;
            setBusy(false);
            setMessage("");
            setConfirmed(false);
            target.current = null;
            void controller.logout();
          }}
        >
          退出
        </button>
      </div>
      <div className="choice-content">
        <h1 id="choice-title">请选择本次办理身份</h1>
        <p className="choice-subtitle">先确认本人任职，再选择办理方式。</p>
        <div className="choice-own">
          <label htmlFor="choice-own">本人任职</label>
          <div className="choice-own-row">
            <select
              ref={ownSelect}
              id="choice-own"
              value={context ? own : ""}
              disabled={!enabled || !context?.appointmentChoices.length}
              onChange={(e) => chooseOwn(e.target.value)}
            >
              <option value="" disabled>
                请选择本人任职
              </option>
              {context?.appointmentChoices.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.label}
                </option>
              ))}
            </select>
            <button
              className="choice-text-button"
              type="button"
              disabled={!enabled || !ownCandidate || established}
              onClick={() => void run(() => controller.prepareAppointment(own))}
            >
              {context?.selectedAppointmentId ? "更换任职" : "确认任职"}
            </button>
          </div>
          {ownLabel && (
            <p
              aria-label="当前本人任职完整名称"
              className={`choice-selected-label${ownLabel.length > 18 ? " is-long" : ""}`}
            >
              {ownLabel}
            </p>
          )}
        </div>
        <fieldset className="choice-mode" disabled={!enabled || !established}>
          <legend>办理方式</legend>
          <label>
            <input
              type="radio"
              name="choice-mode"
              checked={mode === "own"}
              onChange={() => {
                setMode("own");
                setDelegated("");
                setConfirmed(false);
                setMessage("");
              }}
            />
            本人办理
          </label>
          <label>
            <input
              type="radio"
              name="choice-mode"
              checked={mode === "delegated"}
              disabled={!canDelegate}
              onChange={() => {
                setMode("delegated");
                setDelegated("");
                setConfirmed(false);
                setMessage("");
              }}
            />
            合法代办
          </label>
          <p className="choice-mode-help">
            {established && !canDelegate
              ? "当前任职没有可用的合法代办关系。"
              : "仅可选择当前任职已有的有效代办关系。"}
          </p>
          {mode === "delegated" && established && (
            <div className="choice-delegated">
              <label htmlFor="choice-delegated">被代办任职</label>
              <select
                id="choice-delegated"
                value={delegated}
                onChange={(e) => {
                  if (
                    context?.delegatedAppointmentChoices.some(
                      (c) => c.id === e.target.value,
                    )
                  ) {
                    setDelegated(e.target.value);
                    setConfirmed(false);
                    setMessage("");
                  }
                }}
              >
                <option value="" disabled>
                  请选择被代办任职
                </option>
                {context?.delegatedAppointmentChoices.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.label}
                  </option>
                ))}
              </select>
              {delegatedLabel && (
                <p
                  aria-label="当前被代办任职完整名称"
                  className={`choice-selected-label${delegatedLabel.length > 18 ? " is-long" : ""}`}
                >
                  {delegatedLabel}
                </p>
              )}
            </div>
          )}
        </fieldset>
        {marker && (
          <aside className="choice-notice">
            <Info size={30} aria-hidden="true" />
            <div>
              <strong>存在待核对操作；</strong>
              <p>请先选择原操作身份，确认前不能发起新操作。</p>
            </div>
          </aside>
        )}
        {state.switchConfirmation && (
          <div className="choice-risk">
            <p>
              改用其他身份将只删除本地线索，不撤销原操作。请明确确认放弃未决线索的风险。
            </p>
            <div>
              <button
                type="button"
                disabled={busy || storageFailed}
                onClick={() =>
                  void run(
                    () => controller.confirmIdentitySwitch(true),
                    emitConfirmed,
                  )
                }
              >
                确认放弃线索并使用此身份
              </button>
              <button
                ref={cancelButton}
                type="button"
                disabled={busy}
                onClick={() => {
                  restoreFocus.current = true;
                  void run(() => controller.confirmIdentitySwitch(false));
                }}
              >
                取消切换
              </button>
            </div>
          </div>
        )}
        <button
          ref={confirmButton}
          className="choice-confirm"
          type="button"
          disabled={
            !enabled ||
            !established ||
            !ownCandidate ||
            !targetValid ||
            confirmed
          }
          onClick={confirm}
        >
          确认本次身份
        </button>
        <p role="status" className="choice-status" aria-live="polite">
          {status}
        </p>
        {!context &&
          ["UNAVAILABLE", "EXPIRED", "DENIED"].includes(state.status) && (
            <button
              type="button"
              disabled={busy}
              onClick={() => void run(() => controller.initialize())}
            >
              重新核对身份
            </button>
          )}
        <p className="choice-admin-help">账号或任职问题，请联系律所管理员。</p>
      </div>
    </ChoiceLayout>
  );
}
