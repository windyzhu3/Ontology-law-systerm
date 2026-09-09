import { expect, expectTypeOf, it } from "vitest";

import type { components } from "../../generated/api/schema";
import type { IdentityApi } from "./identityApi";
import {
  validIdentityQuery,
  validIdentityRead,
  type IdentityOriginalWrite,
} from "./identityContract";

type S = components["schemas"];
type CommandType = IdentityOriginalWrite["commandType"];
type IdentitySuccessReceipt =
  | S["IdentityPrincipalCommandReceiptV1"]
  | S["OrganizationUnitCommandReceiptV1"]
  | S["AppointmentCommandReceiptV1"]
  | S["AuthorityGrantCommandReceiptV1"];

const key = "019c7000-0000-7000-8000-000000000001";
const targetId = "019c7000-0000-7000-8000-000000000002";
const ifMatch = '"identity.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"';

it("keeps the fourteen command discriminants and generated bodies exact", () => {
  expectTypeOf<CommandType>().toEqualTypeOf<
    | "CREATE_IDENTITY_PRINCIPAL"
    | "RENAME_IDENTITY_PRINCIPAL"
    | "SUSPEND_IDENTITY_PRINCIPAL"
    | "RESUME_IDENTITY_PRINCIPAL"
    | "DISABLE_IDENTITY_PRINCIPAL"
    | "CREATE_ORGANIZATION_UNIT"
    | "RENAME_ORGANIZATION_UNIT"
    | "CLOSE_ORGANIZATION_UNIT"
    | "CREATE_APPOINTMENT"
    | "SUSPEND_APPOINTMENT"
    | "RESUME_APPOINTMENT"
    | "END_APPOINTMENT"
    | "CREATE_AUTHORITY_GRANT"
    | "REVOKE_AUTHORITY_GRANT"
  >();
  expectTypeOf<Extract<IdentityOriginalWrite, { commandType: "CREATE_APPOINTMENT" }>["body"]>()
    .toEqualTypeOf<S["CreateAppointmentV1"]>();
  expectTypeOf<Extract<IdentityOriginalWrite, { commandType: "REVOKE_AUTHORITY_GRANT" }>["body"]>()
    .toEqualTypeOf<S["RevokeAuthorityGrantV1"]>();
});

it("keeps every public Identity response narrowed to its generated body", () => {
  type Write = Awaited<ReturnType<IdentityApi["write"]>>;
  type ProviderUsers = Awaited<ReturnType<IdentityApi["listIdentityProviderUsers"]>>;
  type Options = Awaited<ReturnType<IdentityApi["getIdentityAdminOptions"]>>;
  type Principals = Awaited<ReturnType<IdentityApi["listIdentityPrincipals"]>>;
  type Organizations = Awaited<ReturnType<IdentityApi["listOrganizationUnits"]>>;
  type Appointments = Awaited<ReturnType<IdentityApi["listAppointments"]>>;
  type Grants = Awaited<ReturnType<IdentityApi["listAuthorityGrants"]>>;
  type Receipt = Awaited<ReturnType<IdentityApi["receipt"]>>;

  expectTypeOf<Write["data"]>().toEqualTypeOf<IdentitySuccessReceipt>();
  expectTypeOf<ProviderUsers["data"]>().toEqualTypeOf<S["ProviderUserPageV1"]>();
  expectTypeOf<Options["data"]>().toEqualTypeOf<S["IdentityAdminOptionsV1"]>();
  expectTypeOf<Principals["data"]>().toEqualTypeOf<S["IdentityPrincipalPageV1"]>();
  expectTypeOf<Organizations["data"]>().toEqualTypeOf<S["OrganizationUnitPageV1"]>();
  expectTypeOf<Appointments["data"]>().toEqualTypeOf<S["AppointmentPageV1"]>();
  expectTypeOf<Grants["data"]>().toEqualTypeOf<S["AuthorityGrantPageV1"]>();
  expectTypeOf<Receipt["data"]>().toEqualTypeOf<S["CommandReceipt"]>();
});

it("does not put a target or If-Match on creates and requires both on updates", () => {
  const create = {
    commandType: "CREATE_IDENTITY_PRINCIPAL",
    key,
    body: { providerUserSelector: "c2VsZWN0b3I", displayName: "张三" },
  } satisfies IdentityOriginalWrite;
  const update = {
    commandType: "RENAME_IDENTITY_PRINCIPAL",
    key,
    targetId,
    ifMatch,
    body: { displayName: "张三（新）" },
  } satisfies IdentityOriginalWrite;
  // @ts-expect-error Existing-target commands require the original strong Identity ETag.
  const missingIfMatch: IdentityOriginalWrite = {
    commandType: "RENAME_IDENTITY_PRINCIPAL",
    key,
    targetId,
    body: { displayName: "张三（新）" },
  };
  const forbiddenCreateTarget: IdentityOriginalWrite = {
    commandType: "CREATE_IDENTITY_PRINCIPAL",
    key,
    body: { providerUserSelector: "c2VsZWN0b3I", displayName: "张三" },
    // @ts-expect-error Creates do not fabricate an existing target.
    targetId,
  };
  void create;
  void update;
  void missingIfMatch;
  void forbiddenCreateTarget;
});

it("validates typed query and response boundaries independently", () => {
  expect(validIdentityQuery("listIdentityProviderUsers", { search: "alice", limit: 1 })).toBe(true);
  expect(validIdentityQuery("listIdentityProviderUsers", { search: "alice", cursor: "x" })).toBe(false);
  expect(
    validIdentityRead(
      "listIdentityProviderUsers",
      { items: [{ label: "alice", selector: "c2VsZWN0b3I" }], nextCursor: null },
      { search: "alice", limit: 1 },
    ),
  ).toBe(true);
  expect(
    validIdentityRead(
      "listIdentityProviderUsers",
      { items: [{ label: "alice", selector: "c2VsZWN0b3I", kind: "SERVICE" }], nextCursor: null },
      { search: "alice", limit: 1 },
    ),
  ).toBe(false);
});
