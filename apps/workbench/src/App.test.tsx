import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { createWorkbenchApi } from "./lib/api";
import {
  envelope,
  deferred,
  jsonResponse,
  variants,
  taskId,
  tags,
  receipt,
  selectorId,
} from "./test/fixtures";

import { testSession } from "./test/fixtures";
const session = testSession();
function mount(index = 5, draft = false) {
  const fetcher = vi.fn(async () => jsonResponse(envelope(index, draft)));
  render(<App session={session} api={createWorkbenchApi(fetcher)} />);
  return fetcher;
}
describe("single responsibility workbench", () => {
  it.each(variants.map((v, i) => [v.taskType, i] as const))(
    "renders the static %s form with one completion action",
    async (_, index) => {
      mount(index);
      expect(
        await screen.findByRole("heading", { name: variants[index].purpose }),
      ).toBeVisible();
      expect(
        screen.getAllByRole("button", { name: variants[index].label }),
      ).toHaveLength(1);
      expect(
        within(screen.getByRole("article")).getByLabelText(
          variants[index].fields[0].label,
          { exact: false },
        ),
      ).toBeVisible();
      expect(screen.queryByRole("region", { name: "候选输入" })).not.toBeInTheDocument();
      expect(screen.queryByRole("navigation")).not.toBeInTheDocument();
      expect(screen.queryByText(selectorId)).not.toBeInTheDocument();
    },
  );
  it("shows summary, two next summaries and waiting count without extra cards", async () => {
    mount();
    expect(await screen.findByText("联系下一位客户")).toBeVisible();
    expect(screen.getByText("复核联系结果")).toBeVisible();
    expect(screen.getByText(/等待 1/)).toBeVisible();
    expect(screen.getAllByRole("article")).toHaveLength(1);
  });
  it("fails closed without an injected session", () => {
    const fetcher = vi.fn();
    render(<App api={createWorkbenchApi(fetcher)} />);
    expect(screen.getByText(/登录服务尚未接入/)).toBeVisible();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("rejects unknown discriminators without showing sensitive content", async () => {
    const data = envelope();
    (data.currentCard as unknown as { taskType: string }).taskType = "UNKNOWN";
    render(
      <App
        session={session}
        api={createWorkbenchApi(async () => jsonResponse(data))}
      />,
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(/暂时无法显示/);
    expect(screen.queryByText("王某 · 劳动仲裁咨询")).not.toBeInTheDocument();
  });
  it("restores saved Draft and keeps logical input focus on refresh", async () => {
    mount(5, true);
    const input = await screen.findByLabelText("联系说明");
    expect(input).toHaveValue("本次拨打未接通");
    input.focus();
    fireEvent(window, new Event("focus"));
    await waitFor(() => expect(input).toHaveFocus());
  });
  it("preserves unsaved text and focus when refresh returns the same Draft version", async () => {
    let gets = 0;
    const api = createWorkbenchApi(async () =>
      jsonResponse({
        ...envelope(5, true),
        todaySummary: ++gets === 1 ? "初始摘要" : "刷新后的摘要",
      }),
    );
    render(<App session={session} api={api} />);
    const input = await screen.findByLabelText("联系说明");
    fireEvent.change(input, { target: { value: "尚未保存的补充" } });
    input.focus();
    fireEvent(window, new Event("focus"));
    await screen.findByText("刷新后的摘要");
    expect(input).toHaveValue("尚未保存的补充");
    expect(input).toHaveFocus();
  });
  it("requires legal need when a restored unconnected Draft is changed to connected", async () => {
    mount(5, true);
    fireEvent.change(await screen.findByLabelText("联系结果 *"), {
      target: { value: "CONNECTED_VALID" },
    });
    expect(screen.getByLabelText("法律需求 *")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "保存草稿" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/法律需求/);
    expect(screen.queryByLabelText("联系证据")).not.toBeInTheDocument();
  });
  it("secondary draft save preserves the complete single-editor input without submitting a command", async () => {
    const requests: Request[] = [];
    const fetcher = async (request: Request) => {
      requests.push(request);
      if (request.method === "PUT") {
        const body = await request.clone().json();
        const d = envelope(5, true).currentCard!.actionDraft!;
        return jsonResponse(
          {
            receipt: receipt(
              request.headers.get("Idempotency-Key")!,
              "ACTION_DRAFT",
            ),
            draft: { ...d, values: body.values },
            preconditions: tags,
          },
          201,
          tags.draftETag,
        );
      }
      return jsonResponse(envelope());
    };
    render(<App session={session} api={createWorkbenchApi(fetcher)} />);
    fireEvent.change(await screen.findByLabelText("联系说明"), {
      target: { value: "再次拨打未接通" },
    });
    fireEvent.click(screen.getByRole("button", { name: "保存草稿" }));
    await waitFor(() =>
      expect(requests.filter((r) => r.method === "PUT")).toHaveLength(1),
    );
    const write = requests.find((r) => r.method === "PUT")!;
    expect(write.headers.get("If-None-Match")).toBe("*");
    expect(write.headers.has("If-Match")).toBe(false);
    expect((await write.clone().json()).values).toEqual({
      leadAssignmentId: selectorId,
      leadAssignmentRevision: 0,
      contactChannelCode: "PHONE",
      resultCode: "NOT_CONNECTED",
      resultSummary: "再次拨打未接通",
    });
    expect(requests.some((r) => r.method === "POST")).toBe(false);
  });
  it("clears actor content immediately on replacement and ignores the old response", async () => {
    const fetcher = vi.fn(async () => jsonResponse(envelope()));
    const { rerender } = render(
      <App session={session} api={createWorkbenchApi(fetcher)} />,
    );
    await screen.findByText("王某 · 劳动仲裁咨询");
    rerender(<App session={null} api={createWorkbenchApi(fetcher)} />);
    expect(screen.queryByText("王某 · 劳动仲裁咨询")).not.toBeInTheDocument();
  });
  it("does not derive the signed-in identity from the card owner", async () => {
    mount();
    await screen.findByText("王某 · 劳动仲裁咨询");
    expect(screen.getByRole("banner")).not.toHaveTextContent("陈晓");
  });
  it("returns focus to the same logical field after refresh moves to another task", async () => {
    let gets = 0;
    const api = createWorkbenchApi(async () => {
      const data = envelope();
      if (++gets > 1) {
        data.currentCard!.taskId = selectorId;
        data.chatComposer.targetTaskId = selectorId;
        data.todaySummary = "新的当前责任";
      }
      return jsonResponse(data);
    });
    render(<App session={session} api={api} />);
    const old = await screen.findByLabelText("联系说明");
    old.focus();
    fireEvent(window, new Event("focus"));
    await screen.findByText("新的当前责任");
    expect(screen.getByLabelText("联系说明")).toHaveFocus();
  });
});

 it("saves the current edited task before switching through MyTasks without a completion command", async () => {
  const data=envelope(), next=envelope(0); next.currentCard!.taskId=selectorId; next.chatComposer.targetTaskId=selectorId;
  data.myTasks=[{taskId:selectorId,businessPurpose:next.currentCard!.businessPurpose,priority:"NORMAL",timeHint:"今天"}];
  const requests:Request[]=[];
  const api=createWorkbenchApi(async r=>{
    requests.push(r);
    if(r.method==="PUT") {const body=await r.clone().json(); return jsonResponse({receipt:receipt(r.headers.get("Idempotency-Key")!,"ACTION_DRAFT"),draft:{...envelope(5,true).currentCard!.actionDraft!,values:body.values},preconditions:tags},200,tags.draftETag);}
    return jsonResponse(new URL(r.url).searchParams.has("taskId")?next:data);
  });
  render(<App session={testSession()} api={api}/>);
  fireEvent.change(await screen.findByLabelText("联系说明"),{target:{value:"保留这次沟通记录"}});
  fireEvent.click(screen.getByText("我的待办（1）"));
  fireEvent.click(screen.getByRole("button",{name:/解决疑似重复线索/}));
  fireEvent.click(screen.getByRole("button",{name:"保存草稿并切换"}));
  expect(await screen.findByRole("heading",{name:"解决疑似重复线索"})).toBeVisible();
  expect(requests.map(r=>r.method)).toEqual(["GET","PUT","GET"]);
  expect((await requests[1].clone().json()).values.resultSummary).toBe("保留这次沟通记录");
  expect(new URL(requests[2].url).searchParams.get("taskId")).toBe(selectorId);
 });

it('defers workcard reads for ledger-only entry and selects the exact ledger task on handling',async()=>{
 history.replaceState(null,'','/management/opportunities');
 const fetcher=vi.fn(async()=>jsonResponse(envelope(5,false)));
 const row={opportunity:{id:'opp-a',revision:1},customerLabel:'台账客户',ownerLabel:'本人',taskState:'OPEN' as const};
 const ledgerApi={taskContext:vi.fn(),list:vi.fn().mockResolvedValue({items:[row]}),detail:vi.fn().mockResolvedValue({...row,canHandle:true,task:{id:taskId,revision:0,etag:tags.taskETag}})};
 try{render(<App session={{...session,canReadOpportunityLedger:true}} api={createWorkbenchApi(fetcher)} ledgerApi={ledgerApi}/>);
 fireEvent.click(await screen.findByRole('button',{name:'办理当前事项'}));
 await waitFor(()=>expect(fetcher).toHaveBeenCalledTimes(1));
 const request=fetcher.mock.calls[0] as unknown as [Request];
 expect(request[0].url).toContain(taskId);
 }finally{history.replaceState(null,'','/workbench');}
});
it('does not issue workcard reads for a read-only ledger viewer',async()=>{
 history.replaceState(null,'','/management/opportunities');const fetcher=vi.fn();
 const row={opportunity:{id:'opp-a',revision:1},customerLabel:'只读客户',ownerLabel:'他人',taskState:'OPEN' as const};
 const ledgerApi={taskContext:vi.fn(),list:vi.fn().mockResolvedValue({items:[row]}),detail:vi.fn().mockResolvedValue({...row,canHandle:false})};
 try{render(<App session={{...session,canReadOpportunityLedger:true}} api={createWorkbenchApi(fetcher)} ledgerApi={ledgerApi}/>);await screen.findByText('你可以查看此记录，当前无需由你办理。');expect(fetcher).not.toHaveBeenCalled();expect(screen.queryByRole('button',{name:'我的待办'})).toBeNull();}finally{history.replaceState(null,'','/workbench');}
});

it("keeps MyTasks and ledger navigation available while a cached card refreshes", async () => {
 const data=envelope(), next=envelope(0);next.currentCard!.taskId=selectorId;next.chatComposer.targetTaskId=selectorId;
 data.myTasks=[{taskId:selectorId,businessPurpose:next.currentCard!.businessPurpose,priority:"NORMAL",timeHint:"今天"}];
 const background=deferred<Response>();let reads=0;
 const api=createWorkbenchApi(async r=>new URL(r.url).searchParams.has("taskId")?jsonResponse(next):++reads===1?jsonResponse(data):background.promise);
 render(<App session={{...session,canReadOpportunityLedger:true}} api={api}/>);
 await screen.findByLabelText("联系说明");fireEvent.click(screen.getByText("我的待办（1）"));
 fireEvent(window,new Event("focus"));await waitFor(()=>expect(reads).toBe(2));
 expect(screen.getByRole("button",{name:"商机台账"})).toBeEnabled();
 const choose=screen.getByRole("button",{name:/解决疑似重复线索/});expect(choose).toBeEnabled();fireEvent.click(choose);
 expect(await screen.findByRole("heading",{name:"解决疑似重复线索"})).toBeVisible();
});

it('returns from the ledger directly to the expanded task queue',async()=>{
 history.replaceState(null,'','/management/opportunities');const data=envelope();data.myTasks=[];const fetcher=vi.fn(async()=>jsonResponse(data));const ledgerApi={taskContext:vi.fn(),list:vi.fn().mockResolvedValue({items:[]}),detail:vi.fn()};
 try{render(<App session={{...session,canReadOpportunityLedger:true,canEnterWorkbench:true}} api={createWorkbenchApi(fetcher)} ledgerApi={ledgerApi}/>);fireEvent.click(await screen.findByRole('button',{name:'我的待办'}));const summary=await screen.findByText('我的待办（0）');await waitFor(()=>expect(summary.closest('details')).toHaveAttribute('open'));}finally{history.replaceState(null,'','/workbench');}
});

it('keeps the original unsaved workcard guard when navigating to lead management',async()=>{
 const onLeads=vi.fn();render(<App session={session} api={createWorkbenchApi(async()=>jsonResponse(envelope()))} onLeads={onLeads}/>);
 fireEvent.change(await screen.findByLabelText('联系说明'),{target:{value:'保留原联系草稿'}});fireEvent.click(screen.getByRole('button',{name:'客户与线索'}));
 expect(screen.getByRole('dialog')).toBeVisible();expect(onLeads).not.toHaveBeenCalled();fireEvent.click(screen.getByRole('button',{name:'继续填写'}));expect(screen.getByLabelText('联系说明')).toHaveValue('保留原联系草稿');
});
it('keeps the unsaved original draft when navigating to the overview',async()=>{
 const onOverview=vi.fn();render(<App session={session} api={createWorkbenchApi(async()=>jsonResponse(envelope()))} onOverview={onOverview}/>);
 fireEvent.change(await screen.findByLabelText('联系说明'),{target:{value:'概览不绕过原草稿'}});fireEvent.click(screen.getByRole('button',{name:'经营概览'}));expect(screen.getByRole('dialog')).toBeVisible();expect(onOverview).not.toHaveBeenCalled();fireEvent.click(screen.getByRole('button',{name:'继续填写'}));expect(screen.getByLabelText('联系说明')).toHaveValue('概览不绕过原草稿');
});
