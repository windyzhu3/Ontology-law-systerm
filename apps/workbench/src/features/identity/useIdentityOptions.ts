import { useEffect, useRef, useState } from "react";
import type { components } from "../../generated/api/schema";
import type { WorkbenchSession } from "../../lib/sessionTransport";
import type { IdentityApi } from "./identityApi";
type S = components["schemas"];
const roles: S["IdentityRoleCodeV1"][] = ["INTAKE_OPERATOR", "ROUTING_SUPERVISOR", "CONTACT_OPERATOR"];
const authorities: S["GrantableAuthorityCodeV1"][] = ["LEAD_CAPTURE", "LEAD_INGRESS_RESOLVE", "LEAD_INGRESS_COMPLETE", "LEAD_ASSIGN", "LEAD_ROUTING_DECIDE", "SOURCE_INTAKE_REQUEST_ACK", "SALES_CONTACT_OWNER", "LEAD_VALIDITY_REVIEW"];

/** One bounded, page-qualified selector. Each field owns independent cursor history. */
export function useIdentityOptions(session: WorkbenchSession, api: IdentityApi, page: S["IdentityAdminPageV1"], optionKind: S["IdentityOptionKindV1"] | null) {
  const owner = `${session.identityEpoch}:${session.actorScopeKey}:${session.selectedAppointmentId}:${page}:${optionKind}`;
  const [navigation, setNavigation] = useState({ owner, cursors: [undefined] as Array<string | undefined>, index: 0, refresh: 0 });
  const [data, setData] = useState<S["IdentityAdminOptionsV1"] | null>(null);
  const [selected, setSelected] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const latest = useRef(session); latest.current = session;
  const sequence = useRef(0);
  useEffect(() => {
    setNavigation({ owner, cursors: [undefined], index: 0, refresh: 0 });
    setData(null); setSelected(""); setError(null);
  }, [owner]);
  const cursor = navigation.cursors[navigation.index];
  useEffect(() => {
    if (!optionKind || navigation.owner !== owner) return;
    const controller = new AbortController(), seq = ++sequence.current, actor = latest.current;
    setLoading(true); setData(null); setSelected(""); setError(null);
    void api.getIdentityAdminOptions(actor, { page, optionKind, limit: 20, ...(cursor ? { cursor } : {}) }, controller.signal).then(result => {
      if (controller.signal.aborted || seq !== sequence.current || !actor.isCurrent()) return;
      setData(result.data as S["IdentityAdminOptionsV1"]); setLoading(false);
    }, () => {
      if (controller.signal.aborted || seq !== sequence.current) return;
      setError("候选读取失败，请重读后再选择。"); setLoading(false);
    });
    return () => controller.abort();
  }, [api, owner, navigation.owner, page, optionKind, cursor, navigation.refresh]);
  const active = navigation.owner === owner && !loading ? data : null;
  const changePage = (direction: "next" | "previous") => {
    setData(null); setSelected("");
    setNavigation(current => direction === "next" && active?.candidates.nextCursor ? { ...current, cursors: [...current.cursors.slice(0, current.index + 1), active.candidates.nextCursor], index: current.index + 1 } : { ...current, index: Math.max(0, current.index - 1) });
  };
  return {
    items: active?.candidates.items ?? [], selected: active ? selected : "", loading, error,
    select: (id: string) => setSelected(active?.candidates.items.some(item => item.id === id) ? id : ""),
    roleCodes: active?.roleCodes.filter(code => roles.includes(code)) ?? [],
    authorityCodes: active?.grantableAuthorityCodes.filter(code => authorities.includes(code)) ?? [],
    canPrevious: navigation.index > 0 && !loading,
    canNext: !!active?.candidates.nextCursor && !loading,
    previous: () => changePage("previous"), next: () => changePage("next"),
    reload: () => { setData(null); setSelected(""); setNavigation(current => ({ ...current, refresh: current.refresh + 1 })); },
  };
}

/** Explicit complete-username lookup; changing text invalidates the opaque choice immediately. */
export function useIdentityProviderUser(session: WorkbenchSession, api: IdentityApi) {
  const [search, setSearch] = useState("");
  const [items, setItems] = useState<S["ProviderUserChoiceV1"][]>([]);
  const [selected, setSelected] = useState("");
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const request = useRef<AbortController | null>(null);
  const latest = useRef(session); latest.current = session;
  useEffect(() => () => request.current?.abort(), []);
  const owner = `${session.identityEpoch}:${session.actorScopeKey}:${session.selectedAppointmentId}`;
  useEffect(() => {
    request.current?.abort(); setSearch(""); setItems([]); setSelected(""); setMessage(null); setLoading(false);
  }, [owner]);
  const change = (value: string) => { request.current?.abort(); setSearch(value); setItems([]); setSelected(""); setMessage(null); setLoading(false); };
  const query = async () => {
    request.current?.abort();
    const controller = new AbortController(); request.current = controller;
    setLoading(true); setItems([]); setSelected(""); setMessage(null);
    const actor = latest.current;
    try {
      const result = await api.listIdentityProviderUsers(actor, { search: search.trim() }, controller.signal);
      if (controller.signal.aborted || !actor.isCurrent()) return;
      const candidates = (result.data as S["ProviderUserPageV1"]).items;
      setItems(candidates); setMessage(candidates.length ? null : "未找到完全匹配的用户。");
    } catch {
      if (controller.signal.aborted) return;
      setMessage("精确查询失败，请核对完整用户名后重试。");
    } finally { if (!controller.signal.aborted) setLoading(false); }
  };
  return { search, change, items, selected, select: (selector: string) => setSelected(items.some(item => item.selector === selector) ? selector : ""), loading, message, query };
}
