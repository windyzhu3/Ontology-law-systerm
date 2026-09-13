import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { CurrentCard } from "./CurrentCard";
import { envelope } from "../../test/fixtures";
it("disables completion immediately when the user edits saved candidate text", () => {
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
  expect(complete).toBeDisabled();
  expect(screen.getByRole("button", { name: "保存候选" })).toBeEnabled();
});
it("uses assignment composer only to save the structured selection", () => {
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
  expect(screen.getByRole("region", { name: "候选输入" })).toHaveTextContent(
    "保存上方所选负责人",
  );
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
