import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { CurrentCard } from "./CurrentCard";
import { envelope } from "../../test/fixtures";
it.each([0,1,2,3,4,5,6])("blocks stale basis on R1 card %i instead of inviting repeated submissions", index => {
  const data=envelope(index);data.currentCard!.versionStatus="REFRESH_RECOMMENDED";
  const submit=vi.fn(),save=vi.fn();
  render(<CurrentCard card={data.currentCard!} composer={data.chatComposer} busy={false} blocked={false} save={save} submit={submit}/>);
  expect(screen.getByText(/责任依据与关联资料不一致/)).toBeInTheDocument();
  expect(screen.getByRole('button',{name:data.currentCard!.primaryCommand.label})).toBeDisabled();
  expect(screen.getByRole('button',{name:'保存草稿'})).toBeDisabled();
  expect(submit).not.toHaveBeenCalled();expect(save).not.toHaveBeenCalled();
});
it("keeps one confirmation action available for edited input without a separate candidate editor", () => {
  const data = envelope(5, true);
  render(
    <CurrentCard
      card={data.currentCard!}
      composer={data.chatComposer}
      busy={false}
      blocked={false}
      save={vi.fn()}
      submit={vi.fn()}
    />,
  );
  const complete = screen.getByRole("button", { name: "保存联系结果" });
  expect(complete).toBeEnabled();
  fireEvent.change(screen.getByLabelText("联系说明"), {
    target: { value: "更新说明" },
  });
  expect(complete).toBeEnabled();
  expect(screen.getAllByLabelText("联系说明")).toHaveLength(1);
  expect(screen.queryByRole("region", { name: "候选输入" })).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "保存草稿" })).toBeEnabled();
});
it("offers a secondary draft action inside the assignment card without another editor", () => {
  const data = envelope(2);
  render(
    <CurrentCard
      card={data.currentCard!}
      composer={data.chatComposer}
      busy={false}
      blocked={false}
      save={vi.fn()}
      submit={vi.fn()}
    />,
  );
  expect(screen.queryByRole("region", { name: "候选输入" })).not.toBeInTheDocument();
  expect(screen.getByRole("button", {name:"保存草稿"})).toBeEnabled();
  expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  expect(
    screen.queryByRole("button", { name: /附件|上传|语音|通知/ }),
  ).not.toBeInTheDocument();
});
it("reloads candidate with a safe notice if a fixed selector changes", () => {
  const data = envelope(0, true);
  const props = {
    composer: data.chatComposer,
    busy: false,
    blocked: false,
    save: vi.fn(),
    submit: vi.fn(),
  };
  const { rerender } = render(
    <CurrentCard {...props} card={data.currentCard!} />,
  );
  fireEvent.change(screen.getByLabelText("判断理由 *"), {
    target: { value: "尚未保存的理由" },
  });
  const next = envelope(0, true);
  const nextCard = next.currentCard!;
  nextCard.commandForm.values = {
    ...nextCard.commandForm.values,
    candidateLeadRevision: 8,
  } as typeof nextCard.commandForm.values;
  rerender(<CurrentCard {...props} card={next.currentCard!} />);
  expect(screen.getByLabelText("判断理由 *")).toHaveValue("经核对为独立需求");
  expect(screen.getByRole("status")).toHaveTextContent(/已重新载入/);
});
