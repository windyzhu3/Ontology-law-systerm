import type { ReactNode } from "react";
import { Circle, Info } from "@phosphor-icons/react";

export function IdentityListPage({
  title,
  description,
  createLabel,
  count,
  loading,
  error,
  empty,
  canPrevious,
  canNext,
  onPrevious,
  onNext,
  onReload,
  list,
  detail,
  organization = false,
  wideDetail = false,
  authorityDetail = false,
}: {
  title: string;
  description: string;
  createLabel: string;
  count: number | null;
  loading: boolean;
  error: string | null;
  empty: boolean;
  canPrevious: boolean;
  canNext: boolean;
  onPrevious: () => void;
  onNext: () => void;
  onReload: () => void;
  list: ReactNode;
  detail: ReactNode;
  organization?: boolean;
  wideDetail?: boolean;
  authorityDetail?: boolean;
}) {
  return (
    <>
      <section className="identity-page-heading">
        <div>
          <h1>{title}</h1>
          <p>{description}</p>
        </div>
        <button className="identity-create" disabled aria-describedby="identity-read-only-note">
          {createLabel}
        </button>
      </section>
      <div className={`identity-page-grid${organization ? " organization" : ""}${wideDetail ? " wide-detail" : ""}${authorityDetail ? " authority-detail" : ""}`}>
        <section className="identity-list-panel" aria-label={`${title}列表`}>
          <p id="identity-read-only-note" className="identity-read-only-note" role="note">
            当前仅开放查询，写入功能尚未接入
          </p>
          {loading && count === null ? (
            <p className="identity-state" role="status">正在读取当前页…</p>
          ) : error ? (
            <div className="identity-state" role="alert">
              <p>{error}</p>
              <button onClick={onReload}>重读</button>
            </div>
          ) : empty ? (
            <p className="identity-state">当前页没有可显示的记录。</p>
          ) : (
            <div className={organization ? "identity-list-content" : "identity-list-scroll"}>
              {list}
            </div>
          )}
          <footer className="identity-pagination" aria-label="当前页分页">
            <button disabled={!canPrevious || loading} onClick={onPrevious}>上一页</button>
            <span>{count === null ? "本页数量未知" : `本页 ${count} 项`}</span>
            <button disabled={!canNext || loading} onClick={onNext}>下一页</button>
            <button className="identity-reload" disabled={loading} onClick={onReload}>刷新</button>
          </footer>
        </section>
        <aside className="identity-detail-panel" aria-label={`${title}详情`}>
          {detail ?? <p className="identity-state">请选择一项查看详情。</p>}
        </aside>
      </div>
    </>
  );
}

export function StatusBadge({ state, label, dot = false }: { state: string; label: string; dot?: boolean }) {
  return <span className={`identity-status${dot ? " dot" : ""} state-${state.toLowerCase()}`}>{dot && <Circle size={9} weight="fill" aria-hidden="true" />}{label}</span>;
}

export function DetailRows({ children }: { children: ReactNode }) {
  return <dl className="identity-detail-rows">{children}</dl>;
}

export function DetailRow({ label, children }: { label: string; children: ReactNode }) {
  return <div><dt>{label}</dt><dd>{children}</dd></div>;
}

export function InfoNote({ children, boxed = true }: { children: ReactNode; boxed?: boolean }) {
  return <p className={`identity-info${boxed ? " boxed" : ""}`}><Info size={19} aria-hidden="true" /> <span>{children}</span></p>;
}

export function DisabledActions({ children, variant = "stacked" }: { children: ReactNode; variant?: "stacked" | "inline" | "full" }) {
  return (
    <div className={`identity-disabled-actions ${variant}`} aria-describedby="identity-detail-disabled-note">
      {children}
      <p id="identity-detail-disabled-note">当前仅开放查询，写入功能尚未接入</p>
    </div>
  );
}
