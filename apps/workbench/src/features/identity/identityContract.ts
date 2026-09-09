import type { components } from "../../generated/api/schema";
import { isObject, safeText } from "../workcard/contract";

type S = components["schemas"];

type CreateIdentityWrite<
  CommandType extends string,
  Body,
> = {
  readonly commandType: CommandType;
  readonly key: string;
  readonly body: Body;
};

type UpdateIdentityWrite<
  CommandType extends string,
  Body,
> = CreateIdentityWrite<CommandType, Body> & {
  readonly targetId: string;
  readonly ifMatch: string;
};

export type IdentityOriginalWrite =
  | CreateIdentityWrite<"CREATE_IDENTITY_PRINCIPAL", S["CreateIdentityPrincipalV1"]>
  | UpdateIdentityWrite<"RENAME_IDENTITY_PRINCIPAL", S["RenameIdentityPrincipalV1"]>
  | UpdateIdentityWrite<"SUSPEND_IDENTITY_PRINCIPAL", S["SuspendIdentityPrincipalV1"]>
  | UpdateIdentityWrite<"RESUME_IDENTITY_PRINCIPAL", S["ResumeIdentityPrincipalV1"]>
  | UpdateIdentityWrite<"DISABLE_IDENTITY_PRINCIPAL", S["DisableIdentityPrincipalV1"]>
  | CreateIdentityWrite<"CREATE_ORGANIZATION_UNIT", S["CreateOrganizationUnitV1"]>
  | UpdateIdentityWrite<"RENAME_ORGANIZATION_UNIT", S["RenameOrganizationUnitV1"]>
  | UpdateIdentityWrite<"CLOSE_ORGANIZATION_UNIT", S["CloseOrganizationUnitV1"]>
  | CreateIdentityWrite<"CREATE_APPOINTMENT", S["CreateAppointmentV1"]>
  | UpdateIdentityWrite<"SUSPEND_APPOINTMENT", S["SuspendAppointmentV1"]>
  | UpdateIdentityWrite<"RESUME_APPOINTMENT", S["ResumeAppointmentV1"]>
  | UpdateIdentityWrite<"END_APPOINTMENT", S["EndAppointmentV1"]>
  | CreateIdentityWrite<"CREATE_AUTHORITY_GRANT", S["CreateAuthorityGrantV1"]>
  | UpdateIdentityWrite<"REVOKE_AUTHORITY_GRANT", S["RevokeAuthorityGrantV1"]>;

export type IdentityReadOperation =
  | "listIdentityProviderUsers"
  | "getIdentityAdminOptions"
  | "listIdentityPrincipals"
  | "listOrganizationUnits"
  | "listAppointments"
  | "listAuthorityGrants";

type IdentityReadData = {
  listIdentityProviderUsers: S["ProviderUserPageV1"];
  getIdentityAdminOptions: S["IdentityAdminOptionsV1"];
  listIdentityPrincipals: S["IdentityPrincipalPageV1"];
  listOrganizationUnits: S["OrganizationUnitPageV1"];
  listAppointments: S["AppointmentPageV1"];
  listAuthorityGrants: S["AuthorityGrantPageV1"];
};

const identityTag = /^"identity\.[A-Za-z0-9_-]{43}"$/;
const uuid = (value: unknown): value is string =>
  typeof value === "string" &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
const instant = (value: unknown): value is string =>
  typeof value === "string" &&
  /T.+Z$/.test(value) &&
  Number.isFinite(Date.parse(value));
const exactKeys = (value: Record<string, unknown>, expected: readonly string[]) =>
  Object.keys(value).sort().join() === [...expected].sort().join();
const oneOf = (value: unknown, choices: readonly string[]) =>
  typeof value === "string" && choices.includes(value);
const cursor = (value: unknown) =>
  value === null ||
  (typeof value === "string" && value.length >= 1 && value.length <= 2048);
const choice = (value: unknown) =>
  isObject(value) &&
  exactKeys(value, ["id", "label"]) &&
  uuid(value.id) &&
  safeText(value.label, 200);
const page = (value: unknown, item: (entry: unknown) => boolean, maximum = 50) =>
  isObject(value) &&
  exactKeys(value, ["items", "nextCursor"]) &&
  Array.isArray(value.items) &&
  value.items.length <= maximum &&
  value.items.every(item) &&
  cursor(value.nextCursor);

const roles = ["INTAKE_OPERATOR", "ROUTING_SUPERVISOR", "CONTACT_OPERATOR"] as const;
const projectedRoles = [...roles, "IDENTITY_ADMIN"] as const;
const grantable = [
  "LEAD_CAPTURE",
  "LEAD_INGRESS_RESOLVE",
  "LEAD_INGRESS_COMPLETE",
  "LEAD_ASSIGN",
  "LEAD_ROUTING_DECIDE",
  "SOURCE_INTAKE_REQUEST_ACK",
  "SALES_CONTACT_OWNER",
  "LEAD_VALIDITY_REVIEW",
] as const;
const projectedAuthorities = [
  ...grantable,
  "IDENTITY_PRINCIPAL_MANAGE",
  "IDENTITY_ORGANIZATION_MANAGE",
  "IDENTITY_APPOINTMENT_MANAGE",
  "IDENTITY_AUTHORITY_MANAGE",
] as const;

const reason = (value: unknown) =>
  isObject(value) &&
  exactKeys(value, ["reasonCode"]) &&
  oneOf(value.reasonCode, ["ADMINISTRATIVE_ACTION", "SECURITY_RESPONSE"]);
const displayName = (value: unknown) =>
  isObject(value) &&
  exactKeys(value, ["displayName"]) &&
  safeText(value.displayName, 200);
const validWindow = (start: unknown, end: unknown) =>
  instant(start) &&
  (end === null || (instant(end) && Date.parse(end) > Date.parse(start)));

export function validIdentityOriginal(value: unknown): value is IdentityOriginalWrite {
  if (!isObject(value) || !uuid(value.key) || typeof value.commandType !== "string")
    return false;
  const create = value.commandType.startsWith("CREATE_");
  if (
    !exactKeys(
      value,
      create
        ? ["commandType", "key", "body"]
        : ["commandType", "key", "targetId", "ifMatch", "body"],
    ) ||
    (!create && (!uuid(value.targetId) || !identityTag.test(String(value.ifMatch))))
  )
    return false;
  switch (value.commandType) {
    case "CREATE_IDENTITY_PRINCIPAL":
      return (
        isObject(value.body) &&
        exactKeys(value.body, ["providerUserSelector", "displayName"]) &&
        typeof value.body.providerUserSelector === "string" &&
        /^[A-Za-z0-9_-]{1,2048}$/.test(value.body.providerUserSelector) &&
        safeText(value.body.displayName, 200)
      );
    case "RENAME_IDENTITY_PRINCIPAL":
    case "RENAME_ORGANIZATION_UNIT":
      return displayName(value.body);
    case "SUSPEND_IDENTITY_PRINCIPAL":
    case "RESUME_IDENTITY_PRINCIPAL":
    case "DISABLE_IDENTITY_PRINCIPAL":
    case "CLOSE_ORGANIZATION_UNIT":
    case "SUSPEND_APPOINTMENT":
    case "RESUME_APPOINTMENT":
    case "END_APPOINTMENT":
    case "REVOKE_AUTHORITY_GRANT":
      return reason(value.body);
    case "CREATE_ORGANIZATION_UNIT":
      return (
        isObject(value.body) &&
        exactKeys(value.body, ["parentOrganizationId", "code", "displayName"]) &&
        uuid(value.body.parentOrganizationId) &&
        typeof value.body.code === "string" &&
        /^[A-Z][A-Z0-9_]{0,63}$/.test(value.body.code) &&
        safeText(value.body.displayName, 200)
      );
    case "CREATE_APPOINTMENT":
      return (
        isObject(value.body) &&
        exactKeys(value.body, ["principalId", "organizationId", "roleCode", "effectiveFrom", "effectiveUntil"]) &&
        uuid(value.body.principalId) &&
        uuid(value.body.organizationId) &&
        oneOf(value.body.roleCode, roles) &&
        validWindow(value.body.effectiveFrom, value.body.effectiveUntil)
      );
    case "CREATE_AUTHORITY_GRANT":
      return (
        isObject(value.body) &&
        exactKeys(value.body, ["appointmentId", "authorityCode", "scopeOrganizationId", "validFrom", "validUntil"]) &&
        uuid(value.body.appointmentId) &&
        oneOf(value.body.authorityCode, grantable) &&
        uuid(value.body.scopeOrganizationId) &&
        validWindow(value.body.validFrom, value.body.validUntil)
      );
    default:
      return false;
  }
}

const exactArray = (value: unknown, expected: readonly string[]) =>
  Array.isArray(value) &&
  value.length === expected.length &&
  expected.every((entry) => value.includes(entry)) &&
  value.every((entry) => typeof entry === "string" && expected.includes(entry));

export function validIdentityQuery(
  operation: IdentityReadOperation,
  query: unknown,
): boolean {
  if (!isObject(query)) return false;
  const common = (required: readonly string[] = []) =>
    Object.keys(query).every((key) => ["limit", "cursor", ...required].includes(key)) &&
    required.every((key) => key in query) &&
    (query.limit === undefined ||
      (Number.isInteger(query.limit) && Number(query.limit) >= 1 && Number(query.limit) <= 50)) &&
    (query.cursor === undefined ||
      (typeof query.cursor === "string" && query.cursor.length >= 1 && query.cursor.length <= 2048));
  if (operation === "listIdentityProviderUsers")
    return (
      common(["search"]) &&
      query.cursor === undefined &&
      typeof query.search === "string" &&
      query.search === query.search.trim() &&
      safeText(query.search, 200)
    );
  if (operation === "getIdentityAdminOptions") {
    if (!common(["page", "optionKind"])) return false;
    const pair = `${String(query.page)}:${String(query.optionKind)}`;
    return [
      "PRINCIPALS:PRINCIPAL",
      "ORGANIZATIONS:ORGANIZATION",
      "APPOINTMENTS:PRINCIPAL",
      "APPOINTMENTS:ORGANIZATION",
      "AUTHORITY_GRANTS:APPOINTMENT",
      "AUTHORITY_GRANTS:ORGANIZATION",
    ].includes(pair);
  }
  return common();
}

const providerUser = (value: unknown) =>
  isObject(value) &&
  exactKeys(value, ["label", "selector"]) &&
  safeText(value.label, 200) &&
  typeof value.selector === "string" &&
  /^[A-Za-z0-9_-]{1,2048}$/.test(value.selector);
const principal = (value: unknown) =>
  isObject(value) &&
  exactKeys(value, ["id", "displayName", "state", "etag"]) &&
  uuid(value.id) &&
  safeText(value.displayName, 200) &&
  oneOf(value.state, ["ACTIVE", "SUSPENDED", "DISABLED"]) &&
  identityTag.test(String(value.etag));
const organization = (value: unknown) =>
  isObject(value) &&
  exactKeys(value, ["id", "parentOrganizationId", "code", "displayName", "state", "etag"]) &&
  uuid(value.id) &&
  (value.parentOrganizationId === null || uuid(value.parentOrganizationId)) &&
  typeof value.code === "string" &&
  /^[A-Z][A-Z0-9_]{0,63}$/.test(value.code) &&
  safeText(value.displayName, 200) &&
  oneOf(value.state, ["ACTIVE", "CLOSED"]) &&
  identityTag.test(String(value.etag));
const appointment = (value: unknown) =>
  isObject(value) &&
  exactKeys(value, ["id", "principal", "organization", "roleCode", "effectiveFrom", "effectiveUntil", "state", "etag"]) &&
  uuid(value.id) &&
  choice(value.principal) &&
  choice(value.organization) &&
  oneOf(value.roleCode, projectedRoles) &&
  instant(value.effectiveFrom) &&
  (value.effectiveUntil === null || instant(value.effectiveUntil)) &&
  oneOf(value.state, ["ACTIVE", "SUSPENDED", "ENDED"]) &&
  identityTag.test(String(value.etag));
const authorityGrant = (value: unknown) =>
  isObject(value) &&
  exactKeys(value, ["id", "appointment", "authorityCode", "scopeOrganization", "validFrom", "validUntil", "state", "etag"]) &&
  uuid(value.id) &&
  choice(value.appointment) &&
  oneOf(value.authorityCode, projectedAuthorities) &&
  choice(value.scopeOrganization) &&
  instant(value.validFrom) &&
  (value.validUntil === null || instant(value.validUntil)) &&
  oneOf(value.state, ["ACTIVE", "REVOKED"]) &&
  identityTag.test(String(value.etag));

export function validIdentityRead<Operation extends IdentityReadOperation>(
  operation: Operation,
  value: unknown,
  query: Record<string, unknown>,
): value is IdentityReadData[Operation] {
  if (operation === "listIdentityProviderUsers")
    return page(value, providerUser, 1) && isObject(value) && value.nextCursor === null;
  if (operation === "listIdentityPrincipals") return page(value, principal);
  if (operation === "listOrganizationUnits") return page(value, organization);
  if (operation === "listAppointments") return page(value, appointment);
  if (operation === "listAuthorityGrants") return page(value, authorityGrant);
  if (
    !isObject(value) ||
    !exactKeys(value, ["page", "optionKind", "candidates", "roleCodes", "grantableAuthorityCodes"]) ||
    value.page !== query.page ||
    value.optionKind !== query.optionKind ||
    !page(value.candidates, choice)
  )
    return false;
  const adminPage = String(value.page);
  if (adminPage === "PRINCIPALS")
    return (
      isObject(value.candidates) &&
      Array.isArray(value.candidates.items) &&
      value.candidates.items.length === 0 &&
      exactArray(value.roleCodes, []) &&
      exactArray(value.grantableAuthorityCodes, [])
    );
  if (adminPage === "ORGANIZATIONS")
    return exactArray(value.roleCodes, []) && exactArray(value.grantableAuthorityCodes, []);
  if (adminPage === "APPOINTMENTS")
    return exactArray(value.roleCodes, roles) && exactArray(value.grantableAuthorityCodes, []);
  if (adminPage === "AUTHORITY_GRANTS")
    return exactArray(value.roleCodes, []) && exactArray(value.grantableAuthorityCodes, grantable);
  return false;
}
