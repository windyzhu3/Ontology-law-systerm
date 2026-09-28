import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { MyTasks } from "./MyTasks";
const tasks = [{ taskId: "second", businessPurpose: { code: "COMPLETE_LEAD_INGRESS", label: "补全联系方式" }, priority: "NORMAL" as const, timeHint: "在处理时限内" }];
it("asks before discarding unsaved input and lets the owner cancel", () => {
 const select = vi.fn();
 render(<MyTasks tasks={tasks} recommendedTaskId="second" selectedTaskId="first" dirty blocked={false} select={select} />);
 fireEvent.click(screen.getByText("我的待办（1）"));
 fireEvent.click(screen.getByRole("button", {name: /补全联系方式/}));
 expect(select).not.toHaveBeenCalled();
 expect(screen.getByText(/尚有未提交/)).toBeVisible();
 expect(screen.queryByRole("button",{name:"保存草稿并切换"})).toBeNull();
 fireEvent.click(screen.getByRole("button", {name: "继续编辑"}));
 expect(select).not.toHaveBeenCalled();
 fireEvent.click(screen.getByRole("button", {name: /补全联系方式/}));
 fireEvent.click(screen.getByRole("button", {name: "放弃未保存内容并切换"}));
 expect(select).toHaveBeenCalledWith("second");
});
it("blocks switching while a submission is pending or unknown", () => {
 const select=vi.fn();
 render(<MyTasks tasks={tasks} selectedTaskId="first" dirty={false} blocked select={select}/>);
 expect(screen.getByRole("button", {name: /补全联系方式/})).toBeDisabled();
 expect(screen.getByRole("button", {name: "回到系统推荐"})).toBeDisabled();
});


it("switches only after the draft save is confirmed, and stays on the task if saving fails", async () => {
 const select=vi.fn(); let finish!: (ok: boolean)=>void;
 const saveDraft=()=>new Promise<boolean>(resolve=>{finish=resolve;});
 render(<MyTasks tasks={tasks} selectedTaskId="first" dirty blocked={false} select={select} saveDraft={saveDraft}/>);
 fireEvent.click(screen.getByText("我的待办（1）"));
 fireEvent.click(screen.getByRole("button",{name:/补全联系方式/}));
 fireEvent.click(screen.getByRole("button",{name:"保存草稿并切换"}));
 expect(select).not.toHaveBeenCalled();
 finish(false);
 await waitFor(()=>expect(screen.getByRole("button",{name:"保存草稿并切换"})).toBeEnabled());
 expect(select).not.toHaveBeenCalled();
 fireEvent.click(screen.getByRole("button",{name:"保存草稿并切换"}));
 finish(true);
 await waitFor(()=>expect(select).toHaveBeenCalledWith("second"));
});

it('opens on an explicit return request while allowing the user to collapse it later',()=>{
 const props={tasks,dirty:false,blocked:false,select:vi.fn()};const {rerender}=render(<MyTasks {...props} openRequest={0}/>);const details=screen.getByText('我的待办（1）').closest('details')!;expect(details.open).toBe(false);
 rerender(<MyTasks {...props} openRequest={1}/>);expect(details.open).toBe(true);details.open=false;rerender(<MyTasks {...props} openRequest={1}/>);expect(details.open).toBe(false);rerender(<MyTasks {...props} openRequest={2}/>);expect(details.open).toBe(true);
});
