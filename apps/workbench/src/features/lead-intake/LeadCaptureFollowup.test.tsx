import { render, screen, fireEvent, act } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { testSession, taskId, deferred } from "../../test/fixtures";
import { LeadCaptureFollowup } from "./LeadCaptureFollowup";
const task = { taskId, businessPurpose: { code: "COMPLETE_LEAD_INGRESS", label: "补全联系方式" }, priority: "NORMAL" as const, timeHint: "今天内", subjectFactRef: "a".repeat(43) };
it("shows actual actionable responsibility and opens only the returned task", async () => {
 const onOpen = vi.fn(), load = vi.fn().mockResolvedValue([task]);
 render(<LeadCaptureFollowup session={testSession()} factRef={task.subjectFactRef} load={load} onOpen={onOpen} />);
 fireEvent.click(await screen.findByRole("button", { name: "继续办理：补全联系方式" }));
 expect(onOpen).toHaveBeenCalledWith(taskId); expect(screen.getByText("今天内")).toBeVisible();
});
it("distinguishes an empty authorized result from a read failure without claiming the business ended", async () => {
 const load = vi.fn().mockRejectedValueOnce(new Error("offline")).mockResolvedValue([]);
 render(<LeadCaptureFollowup session={testSession()} factRef={task.subjectFactRef} load={load} onOpen={vi.fn()} />);
 fireEvent.click(await screen.findByRole("button", { name: "重新读取后续事项" }));
 await screen.findByText("当前任职暂无此线索的可处理事项。");
 expect(screen.queryByRole("button", { name: /继续办理/ })).toBeNull();
});

it("discards late follow-up results after changing identity", async () => {
 const old = deferred<typeof task[]>(), load = vi.fn().mockReturnValueOnce(old.promise).mockResolvedValue([]);
 const view = render(<LeadCaptureFollowup session={testSession()} factRef={task.subjectFactRef} load={load} onOpen={vi.fn()} />);
 view.rerender(<LeadCaptureFollowup session={testSession(2)} factRef={task.subjectFactRef} load={load} onOpen={vi.fn()} />);
 await screen.findByText("当前任职暂无此线索的可处理事项。");
 await act(async () => { old.resolve([task]); });
 expect(screen.queryByText("补全联系方式")).toBeNull(); expect(screen.queryByRole("button", { name: /继续办理/ })).toBeNull();
});

it("removes the old customer's action immediately when selecting another customer", async () => {
 const session = testSession(), next = deferred<typeof task[]>();
 const load = vi.fn().mockResolvedValueOnce([task]).mockReturnValueOnce(next.promise), onOpen = vi.fn();
 const view = render(<LeadCaptureFollowup session={session} factRef={task.subjectFactRef} load={load} onOpen={onOpen} />);
 await screen.findByRole("button", { name: "继续办理：补全联系方式" });
 view.rerender(<LeadCaptureFollowup session={session} factRef={"b".repeat(43)} load={load} onOpen={onOpen} />);
 expect(screen.queryByRole("button", { name: /继续办理/ })).toBeNull();
 expect(screen.getByText("正在读取当前任职可处理的后续事项…")).toBeVisible();
 await act(async () => { next.resolve([{ ...task, taskId: "next-customer-task", businessPurpose: { ...task.businessPurpose, label: "核对客户资料" } }]); });
 fireEvent.click(screen.getByRole("button", { name: "继续办理：核对客户资料" }));
 expect(onOpen).toHaveBeenCalledExactlyOnceWith("next-customer-task");
});

it("ignores a late old-customer response after the new customer's tasks are shown", async () => {
 const session = testSession(), old = deferred<typeof task[]>(), onOpen = vi.fn();
 const load = vi.fn().mockReturnValueOnce(old.promise).mockResolvedValueOnce([{ ...task, taskId: "new-task", businessPurpose: { ...task.businessPurpose, label: "核对客户资料" } }]);
 const view = render(<LeadCaptureFollowup session={session} factRef={task.subjectFactRef} load={load} onOpen={onOpen} />);
 view.rerender(<LeadCaptureFollowup session={session} factRef={"b".repeat(43)} load={load} onOpen={onOpen} />);
 await screen.findByRole("button", { name: "继续办理：核对客户资料" });
 await act(async () => { old.resolve([task]); });
 expect(screen.queryByRole("button", { name: "继续办理：补全联系方式" })).toBeNull();
 fireEvent.click(screen.getByRole("button", { name: "继续办理：核对客户资料" }));
 expect(onOpen).toHaveBeenCalledExactlyOnceWith("new-task");
});
