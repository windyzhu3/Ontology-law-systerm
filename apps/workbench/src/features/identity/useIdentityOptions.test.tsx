import { act, renderHook, waitFor } from "@testing-library/react";
import { expect, it } from "vitest";
import { fixture, json, roles } from "./identityWriteFixtures";
import { useIdentityOptions, useIdentityProviderUser } from "./useIdentityOptions";
import { deferred, draftId, selectorId, testSession } from "../../test/fixtures";

it("clears an exact provider selection immediately when the hook Actor changes", async () => {
  const f = fixture("/admin/identity/principals");
  const { result, rerender } = renderHook(({ session }) => useIdentityProviderUser(session, f.api), { initialProps: { session: testSession() } });
  act(() => result.current.change("demo.user")); await act(async () => { await result.current.query(); });
  act(() => result.current.select("opaque_provider_choice")); expect(result.current.selected).toBe("opaque_provider_choice");
  rerender({ session: testSession(2) });
  expect(result.current.search).toBe(""); expect(result.current.items).toEqual([]); expect(result.current.selected).toBe("");
});

it("invalidates old page-qualified options immediately and ignores their late response", async () => {
  const first = deferred<Response>();
  const f = fixture("/admin/identity/appointments", { handle: async r => {
    const url = new URL(r.url); if (!url.pathname.endsWith("options")) return;
    if (url.searchParams.get("optionKind") === "PRINCIPAL") return first.promise;
    return json({ page: "ORGANIZATIONS", optionKind: "ORGANIZATION", candidates: { items: [{ id: draftId, label: "新页面组织" }], nextCursor: null }, roleCodes: [], grantableAuthorityCodes: [] });
  } });
  const { result, rerender } = renderHook(({ page, kind }: { page: "APPOINTMENTS" | "ORGANIZATIONS"; kind: "PRINCIPAL" | "ORGANIZATION" }) => useIdentityOptions(f.session, f.api, page, kind), { initialProps: { page: "APPOINTMENTS", kind: "PRINCIPAL" } });
  await waitFor(() => expect(f.requests).toHaveLength(1));
  rerender({ page: "ORGANIZATIONS", kind: "ORGANIZATION" });
  expect(result.current.items).toEqual([]); expect(result.current.roleCodes).toEqual([]);
  await waitFor(() => expect(result.current.items[0]?.label).toBe("新页面组织"));
  first.resolve(json({ page: "APPOINTMENTS", optionKind: "PRINCIPAL", candidates: { items: [{ id: selectorId, label: "旧页面人员" }], nextCursor: null }, roleCodes: roles, grantableAuthorityCodes: [] }));
  await act(async () => {}); expect(result.current.items[0].label).toBe("新页面组织"); expect(result.current.roleCodes).toEqual([]);
});
