import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { testSession, taskId, selectorId, receipt } from "../../test/fixtures";
import { RecoveryStore } from "../session/recoveryMarker";
import { createLeadIntakeApi } from "./leadIntakeApi";
import { LeadImportBatch } from "./leadImportBatch";
import { LeadImportResults } from "./LeadImportResults";

beforeEach(() => sessionStorage.clear());
function fixture() {
  const requests: Request[] = [], store = new RecoveryStore(sessionStorage);
  const api = createLeadIntakeApi(store, async request => {
    requests.push(request);
    if (request.method === "POST") throw new Error("network");
    return new Response(JSON.stringify({ ...receipt(taskId), resultFact: { factType: "LEAD", factRef: "opaque", revision: 0 } }), { status: 200, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });
  }, "https://law.test");
  const batch = new LeadImportBatch(testSession().actorScopeKey, [taskId, selectorId].map((key, i) => ({ rowNumber: i + 2, write: { key, body: {
    sourceChannelCode: "MANUAL", sourceAccountCode: "sales_intake", sourceRecordKey: `source-${i}`, capturedName: "华启公司",
    capturedAt: "2026-09-14T08:00:00.000000Z", serviceCategoryCode: "GENERAL_INTAKE", jurisdictionCode: "CN", urgencyCode: "NORMAL", legalNeedSummary: "确认委托需求",
  } } })), api, store);
  return { batch, requests };
}
it("shows the unresolved row and one recovery action; confirmation does not send the next row", async () => {
  const f = fixture(); await f.batch.submit(testSession(), new AbortController().signal);
  const { container } = render(<LeadImportResults batch={f.batch} session={testSession()} onReturn={() => {}} />);
  expect(screen.getByRole("heading", { name: "导入结果" })).toBeVisible();
  expect(screen.getByText("1条结果待确认")).toBeVisible();
  expect(screen.queryByRole("button", { name: "继续提交剩余行" })).not.toBeInTheDocument();
  expect(container.querySelectorAll(".identity-primary")).toHaveLength(1);
  expect(container.textContent).not.toContain(taskId);
  fireEvent.click(screen.getByRole("button", { name: "核对提交结果" }));
  await waitFor(() => expect(screen.getByText("1条已录入")).toBeVisible());
  expect(f.requests.map(r => r.method)).toEqual(["POST", "GET"]);
  expect(screen.getByRole("button", { name: "继续提交剩余行" })).toBeEnabled();
});
it("hides the prior batch after switching actor scopes", async () => {
  const f = fixture(); await f.batch.submit(testSession(), new AbortController().signal);
  render(<LeadImportResults batch={f.batch} session={testSession(2)} onReturn={() => {}} />);
  expect(screen.queryByText("华启公司")).not.toBeInTheDocument();
  expect(screen.queryByRole("table")).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "核对提交结果" })).not.toBeInTheDocument();
  expect(screen.getByRole("alert")).toHaveTextContent("会话已变化");
});
it("updates the view when an external batch submit starts and finishes", async () => {
  const f = fixture(); render(<LeadImportResults batch={f.batch} session={testSession()} onReturn={() => {}} />);
  await act(() => f.batch.submit(testSession(), new AbortController().signal));
  expect(screen.getByRole("button", { name: "核对提交结果" })).toBeEnabled();
  expect(screen.getByText("1条未提交")).toBeVisible();
});

it("returns focus to detail when reopening the selected row on a small screen", () => {
  const previousWidth = window.innerWidth;
  const previousScroll = HTMLElement.prototype.scrollIntoView;
  HTMLElement.prototype.scrollIntoView = vi.fn();
  Object.defineProperty(window, "innerWidth", { configurable: true, value: 390 });
  try {
    const f = fixture();
    render(<LeadImportResults batch={f.batch} session={testSession()} onReturn={() => {}} />);
    const row = screen.getAllByRole("button", { name: "华启公司" })[0];
    const detail = screen.getByRole("complementary", { name: "当前行详情" });
    fireEvent.click(row);
    expect(detail).toHaveFocus();
    fireEvent.click(screen.getByRole("button", { name: "返回列表" }));
    expect(row).toHaveFocus();
    fireEvent.click(row);
    expect(detail).toHaveFocus();
  } finally {
    HTMLElement.prototype.scrollIntoView = previousScroll;
    Object.defineProperty(window, "innerWidth", { configurable: true, value: previousWidth });
  }
});


it("retains each confirmed original receipt and explains browser-only progress without resubmitting", async () => {
 const f=fixture();await f.batch.submit(testSession(),new AbortController().signal);await f.batch.recover(testSession(),new AbortController().signal);
 render(<LeadImportResults batch={f.batch} session={testSession()} onReturn={()=>{}}/>);
 expect(screen.getByText(/本页仅保留本次浏览器进度/)).toBeVisible();
 fireEvent.click(screen.getByRole('button',{name:'查看原录入回执'}));
 expect(screen.getByLabelText('原录入回执')).toHaveTextContent(receipt(taskId).receiptId);
 expect(screen.getByLabelText('原录入回执')).toHaveTextContent('2026/09/08 10:10');
 fireEvent.click(screen.getAllByRole('button',{name:'华启公司'})[1]);expect(screen.queryByLabelText('原录入回执')).toBeNull();expect(screen.queryByRole('button',{name:'查看原录入回执'})).toBeNull();
 expect(f.requests.map(r=>r.method)).toEqual(['POST','GET']);
});
