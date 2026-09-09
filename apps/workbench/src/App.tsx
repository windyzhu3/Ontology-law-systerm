import { useLayoutEffect, useMemo, useRef, type ReactNode } from "react";
import { Scales } from "@phosphor-icons/react/Scales";
import { CheckCircle } from "@phosphor-icons/react/CheckCircle";
import { ArrowClockwise } from "@phosphor-icons/react/ArrowClockwise";
import {
  createWorkbenchApi,
  type WorkbenchApi,
  type WorkbenchSession,
} from "./lib/api";
import { CurrentCard } from "./features/workcard/CurrentCard";
import { WaitingSummary } from "./features/workcard/WaitingSummary";
import { useCurrentCard } from "./features/workcard/useCurrentCard";
import "./styles/tokens.css";
import "./styles/workbench.css";

export function App({
  session,
  api,
  sessionActions,
  sessionNotice,
}: {
  session?: WorkbenchSession | null;
  api?: WorkbenchApi;
  sessionActions?: ReactNode;
  sessionNotice?: string | null;
}) {
  const transport = useMemo(() => api ?? createWorkbenchApi(), [api]);
  const work = useCurrentCard(session, transport);
  const envelope = session ? work.envelope : null;
  const logicalFocus = useRef<string | null>(null);
  useLayoutEffect(() => {
    if (document.activeElement === document.body && logicalFocus.current)
      document.getElementById(logicalFocus.current)?.focus();
  }, [envelope]);
  const allowedRoute =
    window.location.pathname === "/workbench" ||
    window.location.pathname === "/";
  return (
    <>
      <header className="app-header">
        <div className="brand">
          <Scales size={30} aria-hidden="true" />
          <span>律所工作助手</span>
        </div>
        {sessionActions ??
          (session?.displayName && <span>{session.displayName}</span>)}
      </header>
      <main
        className="workbench"
        aria-label="责任工作台"
        onFocusCapture={(event) => {
          logicalFocus.current = event.target.id || null;
        }}
      >
        {sessionNotice && (
          <p className="feedback" role="status">
            {sessionNotice}
          </p>
        )}
        {!allowedRoute ? (
          <section className="empty-state">
            <h1>此入口暂不可用</h1>
            <p>请通过工作台入口处理当前责任。</p>
          </section>
        ) : !session ? (
          <section className="empty-state">
            <h1>工作台暂不可用</h1>
            <p>登录服务尚未接入，接入后即可安全查看当前责任。</p>
          </section>
        ) : (
          <>
            <div className="today-summary">
              <CheckCircle size={25} aria-hidden="true" />
              <p aria-live="polite">
                {envelope?.todaySummary ??
                  (work.loading ? "正在读取当前责任…" : "当前责任暂不可用")}
              </p>
              <button
                className="refresh-button"
                onClick={() => void work.refresh()}
                disabled={work.loading || work.busy}
                aria-label="刷新当前责任"
              >
                <ArrowClockwise size={20} aria-hidden="true" />
                <span>刷新</span>
              </button>
            </div>
            {work.error && (
              <div className="feedback" role="alert">
                {work.error}
              </div>
            )}
            {work.message && (
              <p className="feedback success" role="status">
                {work.message}
              </p>
            )}
            {(work.pending || work.recoveryMarker) && (
              <div className="recovery-actions">
                <button
                  disabled={work.busy}
                  onClick={() => void work.recover()}
                >
                  查询原回执
                </button>
                {work.pending && (
                  <button
                    disabled={work.busy}
                    onClick={() => void work.replay()}
                  >
                    使用原请求重试
                  </button>
                )}
              </div>
            )}
            {envelope?.currentCard ? (
              <CurrentCard
                key={envelope.currentCard.taskId}
                card={envelope.currentCard}
                composer={envelope.chatComposer}
                busy={work.busy}
                blocked={
                  !!work.pending || work.recoveryBlocked || work.needsRefresh
                }
                save={work.save}
                submit={work.submit}
              />
            ) : (
              envelope && (
                <section className="empty-state">
                  <CheckCircle size={36} aria-hidden="true" />
                  <h1>当前暂无需要处理的责任</h1>
                  <p>新的责任出现后会在这里显示。</p>
                </section>
              )
            )}
            {envelope && (
              <WaitingSummary
                next={envelope.nextSummaries}
                count={envelope.waitingCount}
              />
            )}
            {envelope && !envelope.currentCard && (
              <section
                className="composer composer-empty"
                aria-label="候选输入"
              >
                <p>当前没有可编辑的候选内容</p>
                <button disabled>保存候选</button>
              </section>
            )}
          </>
        )}
      </main>
    </>
  );
}
