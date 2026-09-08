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
  jsonResponse,
  variants,
  taskId,
  tags,
  receipt,
  selectorId,
} from "./test/fixtures";

const session = { accessToken: "test-only", sessionKey: "actor-one" };
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
      expect(screen.getByRole("region", { name: "候选输入" })).toBeVisible();
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
    fireEvent.click(screen.getByRole("button", { name: "保存候选" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/法律需求/);
    expect(screen.queryByLabelText("联系证据")).not.toBeInTheDocument();
  });
  it("composer edits a named field, saves a full candidate, and never submits a command", async () => {
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
    fireEvent.change(await screen.findByLabelText("候选输入：联系说明"), {
      target: { value: "再次拨打未接通" },
    });
    fireEvent.click(screen.getByRole("button", { name: "保存候选" }));
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
