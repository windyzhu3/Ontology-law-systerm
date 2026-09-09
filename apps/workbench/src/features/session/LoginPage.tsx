import { ArrowRight } from "@phosphor-icons/react";
import { useEffect, useRef, useState } from "react";
import "../../styles/tokens.css";
import "../../styles/login.css";

export interface LoginPageProps {
  onLogin?: () => Promise<void>;
  pending?: boolean;
  message?: string;
}
export function LoginPage({
  onLogin,
  pending = false,
  message,
}: LoginPageProps) {
  const [working, setWorking] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const active = useRef(true);
  const flight = useRef(false);
  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);
  async function login() {
    if (!onLogin || pending || flight.current) return;
    flight.current = true;
    setWorking(true);
    setFailure(null);
    try {
      await onLogin();
    } catch {
      if (active.current) setFailure("登录服务暂不可用，请稍后重试。");
    } finally {
      flight.current = false;
      if (active.current) setWorking(false);
    }
  }
  const busy = pending || working;
  const status = busy
    ? "正在前往统一身份服务，请稍候。"
    : (failure ??
      message ??
      (!onLogin ? "登录服务尚未配置，请联系律所管理员。" : ""));
  return (
    <main className="login-page">
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
      <section className="login-action" aria-labelledby="login-title">
        <div className="login-action-content">
          <h1 id="login-title">登录工作台</h1>
          <p className="login-subtitle">使用律所统一账号继续。</p>
          <button
            className="login-button"
            type="button"
            onClick={() => void login()}
            disabled={!onLogin || busy}
            aria-describedby="login-status"
            aria-busy={busy}
          >
            登录工作台{" "}
            <ArrowRight size={28} weight="regular" aria-hidden="true" />
          </button>
          <p className="login-helper">将前往统一身份服务完成登录</p>
          <p
            className="login-status"
            id="login-status"
            role="status"
            aria-live="polite"
          >
            {status}
          </p>
          <p className="login-admin-help">账号或权限问题，请联系律所管理员</p>
        </div>
        <footer className="login-footer">仅限本所授权人员使用</footer>
      </section>
    </main>
  );
}
