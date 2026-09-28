import { beforeEach, expect, it, vi } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
const interaction = { type: (element: HTMLElement, value: string) => fireEvent.change(element, { target: { value } }), click: (element: HTMLElement) => fireEvent.click(element) };
import { testSession, receipt, taskId } from "../../test/fixtures";
import { RecoveryStore } from "../session/recoveryMarker";
import { createLeadIntakeApi } from "./leadIntakeApi";
import { LeadIntakeApplication } from "./LeadIntakeApplication";
const source = { sourceAccountCode: "sales", displayName: "客户转介绍", sourceChannelCode: "MANUAL", serviceCategoryCode: "GENERAL_INTAKE", jurisdictionCode: "CN", urgencyCode: "NORMAL" };
beforeEach(() => sessionStorage.clear());
function setup(unknown = false, sources = [source]) {
  const recovery = new RecoveryStore(sessionStorage), requests: Request[] = [];
  let command = "";
  const api = createLeadIntakeApi(recovery, async request => {
    requests.push(request);
    const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store", "Location": `/api/v1/commands/${command}/receipt` } });
    if (request.url.endsWith("intake-sources")) return response({ sources });
    if (request.method === "POST") { command = request.headers.get("Idempotency-Key")!; if (unknown) throw new Error("offline"); }
    return response({ ...receipt(command), resultFact: { factType: "LEAD", factRef: "a".repeat(43), revision: 0 } }, request.method === "POST" ? 201 : 200);
  }, "https://law.test");
  const onReturn = vi.fn(), onRecover = vi.fn(), onOpenTask = vi.fn();
  const view = render(<LeadIntakeApplication session={testSession()} api={api} recovery={recovery} onReturn={onReturn} onRecover={onRecover} onOpenTask={onOpenTask} sessionActions="销售一组" />);
  return { ...view, api, recovery, requests, onReturn, onRecover, onOpenTask };
}
it("saves independent names and shows confirmed capture without inventing a follow-up task", async () => {
  const { requests } = setup(), user = interaction;
  await screen.findByLabelText("联系人");
  await user.type(screen.getByLabelText("联系人"), "王女士");
  await user.type(screen.getByLabelText("客户名称"), "华启制造");
  await user.type(screen.getByLabelText("需求描述"), "委托需求");
  await user.click(screen.getByRole("button", { name: "保存线索" }));
  await screen.findByRole("heading", { name: "线索已录入" });
  const body = await requests.find(r => r.method === "POST")!.json();
  expect(body).toMatchObject({ contactName: "王女士", customerName: "华启制造" });
  expect(body).not.toHaveProperty("capturedName");
  expect(screen.queryByText(/今天.*联系/)).toBeNull();
});
it("an unknown result permits only original receipt lookup, not another capture", async () => {
  const { requests, recovery } = setup(true), user = interaction;
  await screen.findByLabelText("需求描述");
  await user.type(screen.getByLabelText("需求描述"), "需求");
  await user.click(screen.getByRole("button", { name: "保存线索" }));
  await screen.findByRole("button", { name: "核对提交结果" });
  expect(recovery.read()).not.toBeNull();
  expect(screen.queryByRole("button", { name: "保存线索" })).toBeNull();
  await user.click(screen.getByRole("button", { name: "核对提交结果" }));
  await screen.findByRole("heading", { name: "线索已录入" });
  expect(requests.filter(r => r.method === "POST")).toHaveLength(1);
  expect(recovery.read()).toBeNull();
});
it("no source blocks intake and cancelling leave preserves entered fields", async () => {
  const empty = setup(false, []);
  await screen.findByRole("heading", { name: "当前没有可用的录入来源" });
  expect(screen.queryByRole("button", { name: "保存线索" })).toBeNull();
  empty.unmount();
  const { onReturn } = setup(), user = interaction;
  await screen.findByLabelText("客户名称");
  await user.type(screen.getByLabelText("客户名称"), "保留客户");
  await user.click(screen.getByRole("button", { name: "返回工作台" }));
  await user.click(screen.getByRole("button", { name: "继续填写" }));
  expect(screen.getByLabelText("客户名称")).toHaveValue("保留客户");
  expect(onReturn).not.toHaveBeenCalled();
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
});

it("previews CSV and submits only valid rows with independent names, allowing optional mapping removal", async () => {
  const { requests, api, onOpenTask } = setup();
  vi.spyOn(api, "followup").mockResolvedValue([{ taskId, businessPurpose: { code: "COMPLETE_LEAD_INGRESS", label: "补全联系方式" }, priority: "NORMAL", timeHint: "今天内", subjectFactRef: "a".repeat(43) }]);
  fireEvent.click(await screen.findByRole("button", { name: "批量导入" }));
  const bytes = new TextEncoder().encode("来源记录号,客户名称,联系人,邮箱,需求描述\nsource-a,华启制造,王女士,,委托需求\nsource-b,待修正,李先生,,");
  const file = new File([bytes], "leads.csv", { type: "text/csv" });
  Object.defineProperty(file, "arrayBuffer", { value: async () => bytes.buffer });
  fireEvent.change(screen.getByLabelText("选择文件"), { target: { files: [file] } });
  await screen.findByLabelText("映射邮箱");
  fireEvent.change(screen.getByLabelText("映射邮箱"), { target: { value: "" } });
  expect(requests.filter(r => r.method === "POST")).toHaveLength(0);
  fireEvent.click(screen.getByRole("button", { name: "确认导入 1 条" }));
  await screen.findByText("1条已录入");
  expect(screen.getByText("1条需修正")).toBeVisible();
  const posts = requests.filter(r => r.method === "POST"); expect(posts).toHaveLength(1);
  expect(await posts[0].json()).toMatchObject({ customerName: "华启制造", contactName: "王女士", sourceRecordKey: "source-a" });
  fireEvent.click(await screen.findByRole("button", { name: "继续办理：补全联系方式" }));
  expect(onOpenTask).toHaveBeenCalledWith(taskId);
});

it("warns before reload when a paused batch still has unsubmitted rows without a recovery marker", async () => {
  const { api, recovery } = setup();
  vi.spyOn(api, "capture").mockRejectedValue(new Error("not sent"));
  fireEvent.click(await screen.findByRole("button", { name: "批量导入" }));
  const bytes = new TextEncoder().encode("来源记录号,需求描述\nsource-a,需求一\nsource-b,需求二");
  const file = new File([bytes], "paused.csv"); Object.defineProperty(file, "arrayBuffer", { value: async () => bytes.buffer });
  fireEvent.change(screen.getByLabelText("选择文件"), { target: { files: [file] } });
  fireEvent.click(await screen.findByRole("button", { name: "确认导入 2 条" }));
  await screen.findByRole("button", { name: "继续提交剩余行" });
  expect(recovery.read()).toBeNull();
  const event = new Event("beforeunload", { cancelable: true }); window.dispatchEvent(event);
  expect(event.defaultPrevented).toBe(true);
});

it("explicit recovery handoff preserves the marker and cancellation keeps the original input", async () => {
  const { recovery, requests, onRecover } = setup(true);
  fireEvent.change(await screen.findByLabelText("需求描述"), { target: { value: "待核对需求" } });
  fireEvent.click(screen.getByRole("button", { name: "保存线索" }));
  const entry = await screen.findByRole("button", { name: "前往恢复入口" });
  const marker = recovery.read(); fireEvent.click(entry);
  expect(screen.getByRole("dialog", { name: "离开本页并核对恢复线索？" })).toHaveTextContent("未提交行");
  fireEvent.click(screen.getByRole("button", { name: "留在本页" }));
  expect(onRecover).not.toHaveBeenCalled(); expect(recovery.read()).toEqual(marker);
  fireEvent.click(entry); fireEvent.click(screen.getByRole("button", { name: "确认前往恢复" }));
  expect(onRecover).toHaveBeenCalledTimes(1); expect(recovery.read()).toEqual(marker);
  expect(requests.filter(r => r.method === "POST")).toHaveLength(1);
});

it("uses the confirmed capture reference to open its actual follow-up task", async () => {
 const { api, onOpenTask } = setup();
 const read = vi.spyOn(api, "followup").mockResolvedValue([{ taskId, businessPurpose: { code: "COMPLETE_LEAD_INGRESS", label: "补全联系方式" }, priority: "NORMAL", timeHint: "今天内", subjectFactRef: "a".repeat(43) }]);
 fireEvent.change(await screen.findByLabelText("需求描述"), { target: { value: "等待补齐" } });
 fireEvent.click(screen.getByRole("button", { name: "保存线索" }));
 fireEvent.click(await screen.findByRole("button", { name: "继续办理：补全联系方式" }));
 expect(read.mock.calls[0][1]).toBe("a".repeat(43)); expect(onOpenTask).toHaveBeenCalledWith(taskId);
});
