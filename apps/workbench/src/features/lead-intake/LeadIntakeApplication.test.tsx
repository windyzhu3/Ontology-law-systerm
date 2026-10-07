/// <reference types="node" />
import { beforeEach, expect, it, vi } from "vitest";
import {readFileSync} from "node:fs";
import {resolve} from "node:path";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
const interaction = { type: (element: HTMLElement, value: string) => fireEvent.change(element, { target: { value } }), click: (element: HTMLElement) => fireEvent.click(element) };
import { testSession, receipt, taskId } from "../../test/fixtures";
import { RecoveryStore } from "../session/recoveryMarker";
import { createLeadIntakeApi } from "./leadIntakeApi";
import { LeadIntakeApplication } from "./LeadIntakeApplication";
const source = { sourceAccountCode: "sales", displayName: "客户转介绍", sourceChannelCode: "MANUAL", serviceCategoryCode: "GENERAL_INTAKE", jurisdictionCode: "CN", urgencyCode: "NORMAL" };
beforeEach(() => sessionStorage.clear());
function setup(unknown = false, sources = [source], sourceSelection?: "BOUND_TO_PRINCIPAL" | "SELECTABLE") {
  const recovery = new RecoveryStore(sessionStorage), requests: Request[] = [];
  let command = "";
  const api = createLeadIntakeApi(recovery, async request => {
    requests.push(request);
    const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store", "Location": `/api/v1/commands/${command}/receipt` } });
    if (request.url.endsWith("intake-sources")) return response({ sources,...(sourceSelection?{sourceSelection}:{}) });
    if (request.method === "POST") { command = request.headers.get("Idempotency-Key")!; if (unknown) throw new Error("offline"); }
    return response({ ...receipt(command), resultFact: { factType: "LEAD", factRef: "a".repeat(43), revision: 0 } }, request.method === "POST" ? 201 : 200);
  }, "https://law.test");
  const onReturn = vi.fn(), onRecover = vi.fn(), onOpenTask = vi.fn();
  const view = render(<LeadIntakeApplication session={testSession()} api={api} recovery={recovery} onReturn={onReturn} onRecover={onRecover} onOpenTask={onOpenTask} sessionActions="销售一组" />);
  return { ...view, api, recovery, requests, onReturn, onRecover, onOpenTask };
}
it("shows the bound account read-only and uses it for manual capture",async()=>{
  const {requests}=setup(false,[source],"BOUND_TO_PRINCIPAL");
  await screen.findByText("来源：客户转介绍");expect(screen.queryByRole("combobox",{name:"来源"})).toBeNull();
  fireEvent.change(screen.getByLabelText("需求描述"),{target:{value:"本人录入"}});fireEvent.click(screen.getByRole("button",{name:"保存线索"}));
  await screen.findByRole("heading",{name:"线索已录入"});expect(await requests.find(r=>r.method==="POST")!.json()).toMatchObject({sourceAccountCode:source.sourceAccountCode});
});
it("retains source selection for legacy responses and refuses an ambiguous bound catalog",async()=>{
  const other={...source,sourceAccountCode:"other",displayName:"旧模式另一来源"};const legacy=setup(false,[source,other]);
  const select=await screen.findByRole("combobox",{name:"来源"});fireEvent.change(select,{target:{value:"other"}});
  fireEvent.change(screen.getByLabelText("需求描述"),{target:{value:"旧模式录入"}});fireEvent.click(screen.getByRole("button",{name:"保存线索"}));
  await screen.findByRole("heading",{name:"线索已录入"});expect(await legacy.requests.find(r=>r.method==="POST")!.json()).toMatchObject({sourceAccountCode:"other"});legacy.unmount();
  const invalid=setup(false,[source,other],"BOUND_TO_PRINCIPAL");await screen.findByRole("button",{name:"重新读取来源"});
  expect(screen.queryByRole("combobox",{name:"来源"})).toBeNull();expect(screen.queryByRole("button",{name:"保存线索"})).toBeNull();expect(invalid.requests.every(r=>r.method==="GET")).toBe(true);
});
it.each(["csv","xlsx"])("uses the bound account for every %s imported row",async extension=>{
  const {requests}=setup(false,[source],"BOUND_TO_PRINCIPAL");
  fireEvent.click(await screen.findByRole("button",{name:"批量导入"}));
  const bytes=extension==="csv"?new TextEncoder().encode("来源记录号,需求描述\nbound-a,本人需求一\nbound-b,本人需求二"):Uint8Array.from(readFileSync(resolve("src/test/lead-intake-fixtures/lead-intake-openpyxl.xlsx")));
  const file=new File([bytes],`bound.${extension}`);Object.defineProperty(file,"arrayBuffer",{value:async()=>bytes.buffer});
  fireEvent.change(screen.getByLabelText("选择文件"),{target:{files:[file]}});
  if(extension==="xlsx") {
    await screen.findByLabelText("映射来源记录号");
    for(const [label,value] of [["来源记录号","来源记录"],["联系人","姓名"],["手机号","电话"],["需求描述","需求"]])fireEvent.change(screen.getByLabelText(`映射${label}`),{target:{value}});
  }
  expect(screen.queryByRole("combobox",{name:"来源"})).toBeNull();fireEvent.click(await screen.findByRole("button",{name:"确认导入 2 条"}));
  await screen.findByText("2条已录入");const posts=requests.filter(r=>r.method==="POST");expect(posts).toHaveLength(2);
  for(const post of posts)expect(await post.json()).toMatchObject({sourceAccountCode:source.sourceAccountCode});
});
it("switching accounts discards the draft without replaying or deleting the original marker",async()=>{
  const view=setup(true,[source],"BOUND_TO_PRINCIPAL");
  fireEvent.change(await screen.findByLabelText("需求描述"),{target:{value:"旧账号正文"}});fireEvent.click(screen.getByRole("button",{name:"保存线索"}));
  await screen.findByRole("button",{name:"核对提交结果"});const marker=view.recovery.read();
  view.rerender(<LeadIntakeApplication session={testSession(2)} api={view.api} recovery={view.recovery} onReturn={view.onReturn} sessionActions="新账号"/>);
  expect(await screen.findByLabelText("需求描述")).toHaveValue("");expect(screen.queryByText("旧账号正文")).toBeNull();expect(view.recovery.read()).toEqual(marker);
  fireEvent.change(screen.getByLabelText("需求描述"),{target:{value:"新账号正文"}});fireEvent.click(screen.getByRole("button",{name:"保存线索"}));
  expect(view.requests.filter(r=>r.method==="POST")).toHaveLength(1);expect(view.recovery.read()).toEqual(marker);
  await expect(view.api.receipt(testSession(2),marker!.commandId,new AbortController().signal)).rejects.toThrow();
});
it("ignores an old account catalog arriving after the new account catalog",async()=>{
  let completeOld!:(response:Response)=>void;let reads=0;
  const reply=(label:string)=>new Response(JSON.stringify({sources:[{...source,displayName:label}],sourceSelection:"BOUND_TO_PRINCIPAL"}),{headers:{"Content-Type":"application/json","Cache-Control":"no-store"}});
  const recovery=new RecoveryStore(sessionStorage);const api=createLeadIntakeApi(recovery,async()=>++reads===1?new Promise<Response>(resolve=>{completeOld=resolve;}):reply("新账号来源"),"https://law.test");
  const view=render(<LeadIntakeApplication session={testSession()} api={api} recovery={recovery} onReturn={()=>{}} sessionActions="旧账号"/>);
  await waitFor(()=>expect(reads).toBe(1));view.rerender(<LeadIntakeApplication session={testSession(2)} api={api} recovery={recovery} onReturn={()=>{}} sessionActions="新账号"/>);
  await screen.findByText("来源：新账号来源");completeOld(reply("旧账号来源"));
  await waitFor(()=>expect(screen.queryByText("来源：旧账号来源")).toBeNull());expect(screen.getByText("来源：新账号来源")).toBeVisible();
});
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
