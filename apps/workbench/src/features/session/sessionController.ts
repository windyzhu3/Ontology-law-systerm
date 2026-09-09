import type { components } from "../../generated/api/schema";
import {
  RecoveryStore,
  scopePattern,
  uuidPattern,
  type RecoveryMarker,
} from "./recoveryMarker";
export interface OidcAdapter {
  initialize(): Promise<boolean>;
  getValidAccessToken(): Promise<string>;
  login(): Promise<void>;
  logout(): Promise<void>;
  clear(): void;
  sessionStartedAt(): number;
}
export type SessionContext = components["schemas"]["SessionContextV1"];
export type SessionStatus =
  | "SIGNED_OUT"
  | "INITIALIZING"
  | "READY"
  | "SELECTING"
  | "EXPIRED"
  | "UNAVAILABLE"
  | "DENIED";
export interface SessionState {
  status: SessionStatus;
  context: SessionContext | null;
  identityEpoch: number;
  warning: boolean;
  message: string | null;
  switchConfirmation: boolean;
}
export class SessionController {
  private pendingChoice: {
    context: SessionContext;
    previous: SessionContext | null;
    marker: RecoveryMarker;
  } | null = null;
  confirmIdentitySwitch(confirmed: boolean): void {
    const pending = this.pendingChoice;
    if (!pending) return;
    this.checkLifetime();
    if (!this.authenticated) return;
    if (confirmed) this.recovery.clear(pending.marker);
    const context = confirmed ? pending.context : pending.previous;
    this.pendingChoice = null;
    this.update({
      context,
      status: context?.state === "READY" ? "READY" : "SELECTING",
      switchConfirmation: false,
      message: null,
    });
  }
  private channel: BroadcastChannel | null = null;
  private browserUsers = 0;
  attachBrowser(): () => void {
    this.browserUsers++;
    try {
      this.channel = new BroadcastChannel("r1.session-logout");
    } catch {
      this.channel = null;
    }
    const channel = this.channel;
    const receive = (event: MessageEvent) => {
      if (event.data === "LOGOUT")
        this.invalidate("SIGNED_OUT", "已退出本页面，请重新登录。");
    };
    channel?.addEventListener("message", receive);
    const activity = (event: Event) => {
      if (event.isTrusted) this.noteActivity();
    };
    const focus = () => {
      this.checkLifetime();
      if (!channel && this.authenticated)
        this.invalidate("EXPIRED", "请重新登录以核对当前会话。");
    };
    const pagehide = () =>
      this.invalidate("EXPIRED", "页面已离开，请重新登录。");
    const visibility = () => {
      if (document.visibilityState === "visible") focus();
    };
    const timer = setInterval(() => this.checkLifetime(), 1000);
    window.addEventListener("focus", focus);
    window.addEventListener("pagehide", pagehide);
    document.addEventListener("visibilitychange", visibility);
    window.addEventListener("pointerdown", activity, { passive: true });
    window.addEventListener("keydown", activity);
    let disposed = false;
    return () => {
      if (disposed) return;
      disposed = true;
      this.browserUsers--;
      clearInterval(timer);
      channel?.removeEventListener("message", receive);
      channel?.close();
      if (this.channel === channel) this.channel = null;
      window.removeEventListener("focus", focus);
      window.removeEventListener("pagehide", pagehide);
      document.removeEventListener("visibilitychange", visibility);
      window.removeEventListener("pointerdown", activity);
      window.removeEventListener("keydown", activity);
      queueMicrotask(() => {
        if (this.browserUsers === 0) this.invalidate("SIGNED_OUT", null);
      });
    };
  }
  private state: SessionState = {
    status: "SIGNED_OUT",
    context: null,
    identityEpoch: 0,
    warning: false,
    message: null,
    switchConfirmation: false,
  };
  private readonly listeners = new Set<() => void>();
  private generation = 0;
  private flight: Promise<string> | null = null;
  private authenticated = false;
  private startedAt = 0;
  private activeAt = 0;
  constructor(
    readonly oidc: OidcAdapter,
    readonly recovery: RecoveryStore,
    private readonly fetcher: typeof fetch = fetch,
    private readonly now = Date.now,
  ) {}
  getSnapshot = () => this.state;
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };
  private update(patch: Partial<SessionState>) {
    this.state = { ...this.state, ...patch };
    this.listeners.forEach((fn) => fn());
  }
  invalidate(
    status: SessionStatus = "EXPIRED",
    message: string | null = "登录状态已失效，请重新登录后继续。",
  ) {
    this.generation++;
    this.authenticated = false;
    this.flight = null;
    this.oidc.clear();
    this.pendingChoice = null;
    this.update({
      status,
      context: null,
      identityEpoch: this.state.identityEpoch + 1,
      warning: false,
      message,
      switchConfirmation: false,
    });
  }
  async initialize(): Promise<void> {
    this.invalidate("INITIALIZING", null);
    const generation = this.generation;
    try {
      const ready = await bounded(this.oidc.initialize());
      if (generation !== this.generation) return;
      if (!ready) {
        this.invalidate("SIGNED_OUT", null);
        return;
      }
      this.startedAt = this.oidc.sessionStartedAt();
      this.activeAt = this.now();
      if (!Number.isFinite(this.startedAt) || this.startedAt > this.now())
        throw new Error();
      this.authenticated = true;
      await this.loadContext(null, null, generation);
    } catch (error) {
      if (generation === this.generation) this.handleFailure(error);
    }
  }
  async login() {
    this.invalidate("INITIALIZING", null);
    const generation = this.generation;
    try {
      await bounded(this.oidc.login());
    } catch {
      if (generation === this.generation)
        this.invalidate("UNAVAILABLE", "登录服务暂不可用，请重新登录。");
    }
  }
  async getValidAccessToken(): Promise<string> {
    this.checkLifetime();
    if (!this.authenticated)
      throw new Error("登录状态已失效，请重新登录后继续。");
    if (this.flight) return this.flight;
    const generation = this.generation;
    const flight = bounded(this.oidc.getValidAccessToken())
      .then((token) => {
        if (generation !== this.generation || !this.authenticated)
          throw new Error("会话已变化。");
        if (!token) throw new Error("凭据不可用。");
        this.checkLifetime();
        if (!this.authenticated) throw new Error("会话已到期。");
        return token;
      })
      .catch((error) => {
        if (generation === this.generation) this.handleFailure(error);
        throw new Error("登录服务暂不可用或会话已失效，请重新登录。");
      })
      .finally(() => {
        if (this.flight === flight) this.flight = null;
      });
    this.flight = flight;
    return flight;
  }
  private async loadContext(
    own: string | null,
    delegated: string | null,
    generation: number,
    previous?: SessionContext | null,
  ) {
    const token = await this.getValidAccessToken();
    if (generation !== this.generation) return;
    const abort = new AbortController();
    try {
      const response = await bounded(
        this.fetcher("/api/v1/session/context", {
          headers: {
            Authorization: `Bearer ${token}`,
            ...(own ? { "X-Appointment-Id": own } : {}),
            ...(delegated ? { "X-On-Behalf-Appointment-Id": delegated } : {}),
          },
          cache: "no-store",
          signal: abort.signal,
        }),
      );
      if (generation !== this.generation) return;
      if (response.status !== 200) throw new SessionFailure(response.status);
      const context = parseContext(await response.json());
      if (generation !== this.generation) return;
      if (
        (own && context.selectedAppointmentId !== own) ||
        context.selectedOnBehalfAppointmentId !== delegated
      )
        throw new Error();
      if (previous !== undefined) {
        const marker = this.recovery.read();
        if (marker && marker.actorScopeKey !== context.actorScopeKey) {
          this.pendingChoice = { context, previous, marker };
          this.update({
            switchConfirmation: true,
            message:
              "改用其他身份将只删除本地线索，不撤销原操作。请明确确认放弃未决线索的风险。",
          });
          return;
        }
      }
      this.update({
        status: context.state === "READY" ? "READY" : "SELECTING",
        context,
        message: null,
      });
    } finally {
      abort.abort();
    }
  }
  async selectAppointment(id: string): Promise<void> {
    const context = this.state.context;
    if (!context?.appointmentChoices.some((c) => c.id === id))
      throw new Error("请选择当前可用任职。");
    if (
      context.selectedAppointmentId === id &&
      context.selectedOnBehalfAppointmentId === null &&
      !this.recovery.read()
    )
      return;
    await this.select(id, null);
  }
  async selectOnBehalfAppointment(id: string | null): Promise<void> {
    const context = this.state.context;
    if (
      !context?.selectedAppointmentId ||
      (id !== null &&
        !context.delegatedAppointmentChoices.some((c) => c.id === id))
    )
      throw new Error("请选择当前可用任职。");
    if (context.selectedOnBehalfAppointmentId === id && !this.recovery.read())
      return;
    await this.select(context.selectedAppointmentId, id);
  }
  private async select(own: string, delegated: string | null) {
    // Selection never guesses an Actor from a recovery marker and never deletes it.
    // A mismatched clue continues blocking writes until explicit abandonment.
    const previous = this.state.context,
      generation = ++this.generation;
    this.flight = null;
    this.update({
      context: null,
      status: "SELECTING",
      identityEpoch: this.state.identityEpoch + 1,
      message: null,
    });
    try {
      await this.loadContext(own, delegated, generation, previous);
    } catch (error) {
      if (generation === this.generation) this.handleFailure(error);
    }
  }
  async logout(): Promise<void> {
    this.invalidate("SIGNED_OUT", "已退出本页面，统一会话退出尚未确认。");
    try {
      this.channel?.postMessage("LOGOUT");
    } catch {
      /* Other tabs recheck on their next request. */
    }
    try {
      await bounded(this.oidc.logout());
    } catch {
      /* Local sign-out already completed. */
    }
  }
  noteActivity() {
    this.checkLifetime();
    if (this.authenticated) this.activeAt = this.now();
  }
  checkLifetime(): void {
    if (!this.authenticated) return;
    const remaining =
      Math.min(this.activeAt + 30 * 60_000, this.startedAt + 8 * 60 * 60_000) -
      this.now();
    if (remaining <= 0) {
      this.invalidate();
      return;
    }
    if (this.state.warning !== remaining <= 60_000)
      this.update({ warning: remaining <= 60_000 });
  }
  private handleFailure(error: unknown) {
    const status = error instanceof SessionFailure ? error.status : 0;
    this.invalidate(
      status === 401
        ? "EXPIRED"
        : [403, 404].includes(status)
          ? "DENIED"
          : "UNAVAILABLE",
      status === 401
        ? "登录状态已失效，请重新登录后继续。"
        : [403, 404].includes(status)
          ? "账号或任职暂不可用，请联系管理员。"
          : "登录服务暂不可用，请重新登录。",
    );
  }
}
export class SessionFailure extends Error {
  constructor(readonly status: number) {
    super("会话请求未完成。");
  }
}
export async function bounded<T>(promise: Promise<T>): Promise<T> {
  let timer: ReturnType<typeof setTimeout>;
  try {
    return await Promise.race([
      promise,
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new Error("请求超时。")), 10_000);
      }),
    ]);
  } finally {
    clearTimeout(timer!);
  }
}
function parseContext(value: unknown): SessionContext {
  const fail = () => {
    throw new Error("会话响应不可用。");
  };
  if (!value || typeof value !== "object") return fail();
  const v = value as SessionContext;
  if (
    Object.keys(v).sort().join() !==
    "actorScopeKey,appointmentChoices,canEnterIdentityAdmin,canEnterWorkbench,delegatedAppointmentChoices,displayName,selectedAppointmentId,selectedOnBehalfAppointmentId,state"
  )
    return fail();
  const text = (x: unknown) =>
    typeof x === "string" &&
    x.trim().length > 0 &&
    x.length <= 200 &&
    !/[\u0000-\u001f\u007f]/.test(x);
  const choices = (a: unknown): a is SessionContext["appointmentChoices"] =>
    Array.isArray(a) &&
    a.length <= 50 &&
    a.every(
      (c) =>
        c &&
        Object.keys(c).sort().join() === "id,label" &&
        typeof c.id === "string" &&
        uuidPattern.test(c.id) &&
        text(c.label),
    ) &&
    new Set(a.map((c) => c.id)).size === a.length;
  if (
    !text(v.displayName) ||
    !choices(v.appointmentChoices) ||
    !choices(v.delegatedAppointmentChoices) ||
    typeof v.canEnterWorkbench !== "boolean" ||
    typeof v.canEnterIdentityAdmin !== "boolean"
  )
    return fail();
  if (v.state === "READY") {
    if (
      !v.appointmentChoices.some((c) => c.id === v.selectedAppointmentId) ||
      typeof v.actorScopeKey !== "string" ||
      !scopePattern.test(v.actorScopeKey)
    )
      return fail();
    if (
      v.selectedOnBehalfAppointmentId !== null &&
      (!v.delegatedAppointmentChoices.some(
        (c) => c.id === v.selectedOnBehalfAppointmentId,
      ) ||
        v.canEnterIdentityAdmin)
    )
      return fail();
  } else if (
    !["NO_APPOINTMENT", "APPOINTMENT_SELECTION_REQUIRED"].includes(v.state) ||
    v.selectedAppointmentId !== null ||
    v.actorScopeKey !== null ||
    v.canEnterWorkbench ||
    v.canEnterIdentityAdmin ||
    v.selectedOnBehalfAppointmentId !== null ||
    v.delegatedAppointmentChoices.length ||
    (v.state === "NO_APPOINTMENT"
      ? v.appointmentChoices.length !== 0
      : v.appointmentChoices.length < 2)
  )
    return fail();
  return v;
}
