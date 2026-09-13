import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { Info } from "@phosphor-icons/react";
import { createWorkbenchApi, type WorkbenchApi } from "../../lib/api";
import type { SessionController } from "./sessionController";
import {
  SessionProvider,
  useActorSession,
  useSessionController,
  useSessionSetupReady,
  useSessionState,
} from "./SessionProvider";
import { useCurrentCard } from "../workcard/useCurrentCard";
import { publicCommandFacts, type RecoveryMarker } from "./recoveryMarker";
import "../../styles/tokens.css";
import "../../styles/login.css";
import "../../styles/choice.css";
import "../../styles/recovery.css";
export interface RecoveryPageProps {
  controller: SessionController | null;
  api?: WorkbenchApi;
  onChooseIdentity?: () => void;
  onReady?: () => void;
}
const operationNames: Record<keyof typeof publicCommandFacts, string> = {
  CAPTURE_LEAD: "登记线索",
  SAVE_ACTION_DRAFT: "保存候选",
  RESOLVE_DUPLICATE_LEAD: "核对线索归属",
  COMPLETE_LEAD_INGRESS: "补全线索接入信息",
  ASSIGN_LEAD: "分配线索",
  RECORD_ROUTING_DISPOSITION: "记录调配处置",
  ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST: "确认停止接入请求",
  RECORD_CONTACT_RESULT: "记录首联结果",
  REVIEW_LEAD_VALIDITY: "复核线索有效性",
  CREATE_IDENTITY_PRINCIPAL: "创建人员主体",
  RENAME_IDENTITY_PRINCIPAL: "更改人员名称",
  SUSPEND_IDENTITY_PRINCIPAL: "挂起人员主体",
  RESUME_IDENTITY_PRINCIPAL: "恢复人员主体",
  DISABLE_IDENTITY_PRINCIPAL: "停用人员主体",
  CREATE_ORGANIZATION_UNIT: "创建组织单元",
  RENAME_ORGANIZATION_UNIT: "更改组织名称",
  CLOSE_ORGANIZATION_UNIT: "关闭组织单元",
  CREATE_APPOINTMENT: "创建任职",
  SUSPEND_APPOINTMENT: "挂起任职",
  RESUME_APPOINTMENT: "恢复任职",
  END_APPOINTMENT: "结束任职",
  CREATE_AUTHORITY_GRANT: "授予直接权限",
  REVOKE_AUTHORITY_GRANT: "撤销直接权限",
};
const sameMarker = (a: RecoveryMarker | null, b: RecoveryMarker | null) =>
  !!a &&
  !!b &&
  a.commandId === b.commandId &&
  a.commandType === b.commandType &&
  a.actorScopeKey === b.actorScopeKey &&
  a.recordedAt === b.recordedAt;
export function RecoveryPage({
  controller,
  api,
  onChooseIdentity,
  onReady,
}: RecoveryPageProps) {
  const transport = useMemo(
    () =>
      api ??
      (controller
        ? createWorkbenchApi(undefined, location.origin, controller.recovery)
        : null),
    [api, controller],
  );
  if (!controller || !transport)
    return (
      <RecoveryLayout>
        <h1 id="recovery-title">核对原操作结果</h1>
        <p role="status">恢复服务尚未配置，请联系律所管理员。</p>
      </RecoveryLayout>
    );
  return (
    <SessionProvider controller={controller}>
      <RecoveryContent
        api={transport}
        onChooseIdentity={onChooseIdentity}
        onReady={onReady}
      />
    </SessionProvider>
  );
}
function RecoveryLayout({ children }: { children: ReactNode }) {
  return (
    <main className="login-page recovery-page">
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
      <section className="recovery-content" aria-labelledby="recovery-title">
        {children}
        <footer className="login-footer">仅限本所授权人员使用</footer>
      </section>
    </main>
  );
}
function RecoveryContent({
  api,
  onChooseIdentity,
  onReady,
}: Pick<RecoveryPageProps, "onChooseIdentity" | "onReady"> & {
  api: WorkbenchApi;
}) {
  const controller = useSessionController(),
    actor = useActorSession(),
    state = useSessionState(),
    setup = useSessionSetupReady();
  const currentSelection = () => {
    const latest = controller.getSnapshot();
    return (
      setup &&
      state.status === "SELECTING" &&
      state.context !== null &&
      latest.status === "SELECTING" &&
      latest.context === state.context &&
      latest.identityEpoch === state.identityEpoch
    );
  };
  const work = useCurrentCard(actor, api, {
    recoveryOnly: true,
    readAfterRecovery: state.context?.canEnterWorkbench === true,
    canAbandonInvalidClue: currentSelection,
  });
  const [risk, setRisk] = useState<{
    marker: RecoveryMarker | null;
    controller: SessionController;
    epoch: number;
  } | null>(null);
  const [localError, setLocalError] = useState("");
  const cancel = useRef<HTMLButtonElement>(null),
    abandon = useRef<HTMLButtonElement>(null);
  const readySent = useRef(false);
  let stored: RecoveryMarker | null = null,
    storageFailed = false;
  try {
    stored = api.recovery.read();
  } catch {
    storageFailed = true;
  }
  storageFailed ||= !stored && work.recoveryBlocked;
  const marker =
    actor && stored?.actorScopeKey === actor.actorScopeKey ? stored : null;
  const riskVisible =
    !!risk &&
    risk.controller === controller &&
    risk.epoch === state.identityEpoch &&
    (sameMarker(risk.marker, marker) || (!risk.marker && storageFailed));
  useEffect(() => {
    if (riskVisible) cancel.current?.focus();
  }, [riskVisible]);
  useEffect(() => {
    setRisk(null);
    setLocalError("");
    readySent.current = false;
  }, [controller, state.identityEpoch, actor?.actorScopeKey]);
  const unavailable = !actor;
  const mismatch = !!actor && !!stored && !marker;
  const commandType =
    marker?.commandType ?? (actor ? work.recoveredCommandType : null);
  const name =
    commandType && Object.hasOwn(operationNames, commandType)
      ? operationNames[commandType as keyof typeof operationNames]
      : "原操作";
  const context = actor ? state.context : null;
  const ownLabel = context?.appointmentChoices.find(
    (c) => c.id === context.selectedAppointmentId,
  )?.label;
  const delegatedLabel = context?.delegatedAppointmentChoices.find(
    (c) => c.id === context.selectedOnBehalfAppointmentId,
  )?.label;
  const identityLabel = delegatedLabel
    ? `${ownLabel}（代办：${delegatedLabel}）`
    : ownLabel;
  const subtitle = storageFailed
    ? "恢复存储不可用，请联系律所管理员。"
    : unavailable
      ? state.status === "DENIED"
        ? "当前访问受限，请联系律所管理员。"
        : state.status === "UNAVAILABLE"
          ? "会话服务暂时不可用，请稍后重试。"
          : state.status === "EXPIRED"
            ? "登录状态已失效，请重新登录后继续。"
            : "请先确认当前办理身份。"
      : mismatch
        ? "请先选择原操作身份，再核对原回执。"
        : work.recoveryAbandoned
          ? "已放弃本地线索，原操作结果仍需另行核对。"
          : work.recoveryConfirmed
            ? work.loading
              ? "原操作结果已确认，正在读取当前责任。"
              : work.needsRefresh
                ? "原操作结果已确认，当前责任暂时无法读取。"
                : "原操作结果已确认。"
            : !stored
              ? "当前没有待核对的本地线索。"
              : "结果尚未确认，请勿重复提交。";
  const canQuery =
    setup &&
    !!actor &&
    !!marker &&
    sameMarker(marker, work.recoveryMarker) &&
    !storageFailed &&
    !work.busy &&
    !work.loading &&
    !riskVisible;
  function finish() {
    if (
      !setup ||
      !(actor?.isCurrent() || (work.recoveryAbandoned && currentSelection())) ||
      readySent.current ||
      work.busy ||
      work.loading ||
      storageFailed
    )
      return;
    try {
      if (api.recovery.read()) return;
      readySent.current = true;
      onReady?.();
    } catch {
      setLocalError("恢复存储不可用，暂时不能继续。");
    }
  }
  return (
    <RecoveryLayout>
      <div className="choice-account">
        <span>{context?.displayName ?? ""}</span>
        <button
          type="button"
          disabled={!setup || state.status === "SIGNED_OUT"}
          onClick={() => void controller.logout()}
        >
          退出
        </button>
      </div>
      <h1 id="recovery-title">核对原操作结果</h1>
      <p className="recovery-subtitle">{subtitle}</p>
      {actor &&
        !mismatch &&
        !storageFailed &&
        (marker || work.recoveryConfirmed) && (
          <section className="recovery-operation">
            <h2>{name}</h2>
            <div className="recovery-identity">
              <span>当前办理身份</span>
              <p>{identityLabel}</p>
            </div>
          </section>
        )}
      {!unavailable && !mismatch && marker && !storageFailed && (
        <aside className="recovery-notice">
          <Info size={30} aria-hidden="true" />
          <div>
            <strong>
              {work.busy
                ? "正在查询原回执，请稍候。"
                : "当前仅能查询原回执。"}
            </strong>
            <p>核对完成前，不能发起新操作。</p>
          </div>
        </aside>
      )}
      {unavailable &&
      ["EXPIRED", "DENIED", "UNAVAILABLE", "SIGNED_OUT"].includes(
        state.status,
      ) ? (
        <button
          className="recovery-primary"
          type="button"
          disabled={!setup || state.status === "INITIALIZING"}
          onClick={() => void controller.login()}
        >
          重新登录
        </button>
      ) : actor && work.recoveryConfirmed && !stored && !storageFailed ? (
        work.needsRefresh || work.loading ? (
          <button
            className="recovery-primary"
            disabled={work.loading || work.busy}
            onClick={() => void work.refresh()}
          >
            重新读取当前责任
          </button>
        ) : (
          <button className="recovery-primary" onClick={finish}>
            继续
          </button>
        )
      ) : (actor || (work.recoveryAbandoned && currentSelection())) &&
        (work.recoveryAbandoned || !stored) &&
        !storageFailed ? (
        <button className="recovery-primary" onClick={finish}>
          继续
        </button>
      ) : (
        <button
          className="recovery-primary"
          type="button"
          disabled={!canQuery}
          onClick={() => void work.recover()}
        >
          查询原操作结果
        </button>
      )}
      {(mismatch || (unavailable && state.status === "SELECTING")) && (
        <button
          type="button"
          disabled={!setup || !onChooseIdentity}
          onClick={onChooseIdentity}
        >
          重新选择办理身份
        </button>
      )}
      <p role="status" className="recovery-status" aria-live="polite">
        {storageFailed || unavailable
          ? ""
          : localError ||
            work.error ||
            (work.busy ? "正在核对原操作结果，请稍候。" : "")}
      </p>
      {(actor || currentSelection()) && (marker || storageFailed) && (
        <section className="recovery-abandon">
          <button
            ref={abandon}
            type="button"
            disabled={!setup}
            onClick={() => {
              if (actor?.isCurrent() || currentSelection())
                setRisk({
                  marker: storageFailed ? null : marker,
                  controller,
                  epoch: state.identityEpoch,
                });
            }}
          >
            放弃本地线索
          </button>
          <p>仅删除本地线索，不撤销原操作。</p>
          <p>放弃前需再次确认。</p>
          {riskVisible && (
            <div className="recovery-risk">
              <p>只删除本地线索，不撤销原操作。确认放弃？</p>
              <div>
                <button
                  type="button"
                  onClick={() => {
                    if (
                      risk &&
                      risk.epoch === controller.getSnapshot().identityEpoch &&
                      work.abandonRecovery(risk.marker, true)
                    )
                      setRisk(null);
                  }}
                >
                  确认放弃本地线索
                </button>
                <button
                  ref={cancel}
                  type="button"
                  onClick={() => {
                    setRisk(null);
                    abandon.current?.focus();
                  }}
                >
                  取消放弃
                </button>
              </div>
            </div>
          )}
        </section>
      )}
      <p className="recovery-contact">账号或任职问题，请联系律所管理员。</p>
    </RecoveryLayout>
  );
}
