import { act, fireEvent, render, renderHook, screen, waitFor } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { parseEnvelope } from "./contract";
import { CurrentCard } from "./CurrentCard";
import { useCurrentCard } from "./useCurrentCard";
import { createWorkbenchApi } from "../../lib/api";
import { opportunityEnvelope, opportunityValues } from "../../test/opportunityFixtures";
import { jsonResponse, receipt, tags, testSession, envelope } from "../../test/fixtures";
beforeEach(()=>sessionStorage.clear());
it('opens a secondary business entry without submitting invented progress',()=>{
 const data=parseEnvelope(opportunityEnvelope()),next=vi.fn(),submit=vi.fn();
 render(<CurrentCard card={data.currentCard!} composer={data.chatComposer} busy={false} blocked={false} save={vi.fn()} submit={submit} onBusinessContinue={next}/>);
 fireEvent.click(screen.getByText('其他业务处理'));fireEvent.click(screen.getByRole('button',{name:'推进或结束本次商机'}));
 expect(next).toHaveBeenCalledWith('choose');expect(submit).not.toHaveBeenCalled();expect(document.querySelectorAll('.primary-action')).toHaveLength(1);
});
it("parses only the exact opportunity identity and action and rejects widened forms",()=>{
  const data=opportunityEnvelope();expect(parseEnvelope(data).currentCard?.taskType).toBe("PROGRESS_OPPORTUNITY");
  for(const patch of [{subject:{...data.currentCard.subject,subjectType:"LEAD"}},{expectedCompletionFact:"LEAD_CONTACT_RESULT"},{commandForm:{...data.currentCard.commandForm,values:{businessCategory:"execution"}}},{primaryCommand:{...data.currentCard.primaryCommand,code:"RECORD_CONTACT_RESULT"}}]) {
    expect(()=>parseEnvelope({...data,currentCard:{...data.currentCard,...patch}})).toThrow();
  }
});
it("renders the existing card shell and marks human edits dirty before allowing confirmation",()=>{
  const data=parseEnvelope(opportunityEnvelope());const dirty=vi.fn(),save=vi.fn(),submit=vi.fn();
  render(<CurrentCard card={data.currentCard!} composer={data.chatComposer} busy={false} blocked={false} save={save} submit={submit} onDirtyChange={dirty}/>);
  expect(screen.getByRole("button",{name:"确认本次进展"})).toBeEnabled();
  expect(dirty).toHaveBeenLastCalledWith(false);
  fireEvent.change(screen.getByLabelText(/进展摘要 \*/),{target:{value:"客户已确认"}});
  expect(dirty).toHaveBeenLastCalledWith(true);
  expect(screen.queryByLabelText(/业务分类/)).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button",{name:"保存草稿"}));
  expect(save.mock.calls[0][0].progressSummary).toBe("客户已确认");
  expect(Number.isFinite(Date.parse(save.mock.calls[0][0].occurredAt))).toBe(true);
});
it("saves, updates, confirms and refreshes through the real workcard hook",async()=>{
  const initial=opportunityEnvelope();const requests:Request[]=[];let saved:ReturnType<typeof opportunityEnvelope>|null=null,done=false;
  const api=createWorkbenchApi(async r=>{
    requests.push(r);
    if(r.method==="GET") return jsonResponse(done?{...envelope(),currentCard:null,myTasks:[],recommendedTaskId:null,nextSummaries:[],waitingCount:1,chatComposer:{mode:"ACTION_DRAFT",targetTaskId:null,placeholder:"等待下一事项",enabled:false}}:saved??initial);
    const body=await r.json();const key=r.headers.get("Idempotency-Key")!;
    if(r.method==="PUT") {const existed=!!saved;saved=opportunityEnvelope(body.values);return jsonResponse({receipt:receipt(key,"ACTION_DRAFT"),draft:saved.currentCard.actionDraft,preconditions:tags},existed?200:201,tags.draftETag);}
    done=true;return jsonResponse(receipt(key,"OPPORTUNITY_PROGRESS"));
  });
  const session=testSession();const {result}=renderHook(()=>useCurrentCard(session,api));
  await waitFor(()=>expect(result.current.envelope?.currentCard?.taskType).toBe("PROGRESS_OPPORTUNITY"));
  const values=opportunityValues();
  await act(async()=>{await result.current.save(values);});expect(result.current.pending).toBeNull();
  const changed={...values,progressSummary:"客户补充范围"};
  await act(async()=>{await result.current.submit(changed);});expect(requests.filter(r=>r.method==="POST")).toHaveLength(0);
  await act(async()=>{await result.current.save(changed);});expect(result.current.pending).toBeNull();
  await act(async()=>{await result.current.submit(changed);});
  expect(result.current.envelope?.currentCard).toBeNull();expect(result.current.envelope?.waitingCount).toBe(1);
  expect(requests.filter(r=>r.method==="PUT").every(r=>r.url.endsWith("/opportunity-progress-draft"))).toBe(true);
  expect(requests.filter(r=>r.method==="POST")).toHaveLength(1);
  expect(requests.find(r=>r.method==="POST")?.url).toContain("/record-opportunity-progress");
});
it("switches from the opportunity to an authorized R1 item and back to the recommendation",async()=>{
  const requests:Request[]=[];
  const selected=envelope();
  selected.currentCard!.taskId="019c7000-0000-7000-8000-000000000004";
  selected.chatComposer.targetTaskId=selected.currentCard!.taskId;
  const api=createWorkbenchApi(async r=>{
    requests.push(r);
    return jsonResponse(new URL(r.url).searchParams.has("taskId")?selected:opportunityEnvelope());
  });
  const session=testSession();const {result}=renderHook(()=>useCurrentCard(session,api));
  await waitFor(()=>expect(result.current.envelope?.currentCard?.taskType).toBe("PROGRESS_OPPORTUNITY"));
  await act(async()=>{await result.current.selectTask(selected.currentCard!.taskId);});
  expect(result.current.envelope?.currentCard?.taskType).toBe(selected.currentCard!.taskType);
  expect(requests.at(-1)!.headers.get("If-None-Match")).toBeNull();
  await act(async()=>{await result.current.selectTask(null);});
  expect(result.current.envelope?.currentCard?.taskType).toBe("PROGRESS_OPPORTUNITY");
});
it("keeps the opportunity write pending and prevents switching after a lost response",async()=>{
  const requests:Request[]=[];
  const api=createWorkbenchApi(async r=>{requests.push(r);if(r.method==="PUT")throw Error("connection lost");return jsonResponse(opportunityEnvelope());});
  const session=testSession();const {result}=renderHook(()=>useCurrentCard(session,api));
  await waitFor(()=>expect(result.current.envelope?.currentCard?.taskType).toBe("PROGRESS_OPPORTUNITY"));
  await act(async()=>{await result.current.save(opportunityValues());});
  expect(result.current.pending).not.toBeNull();
  const count=requests.length;
  await act(async()=>{await result.current.selectTask(null);});
  expect(requests).toHaveLength(count);
  expect(result.current.pending).not.toBeNull();
});
