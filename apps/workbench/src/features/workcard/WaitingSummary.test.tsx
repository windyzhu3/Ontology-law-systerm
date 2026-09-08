import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { WaitingSummary } from "./WaitingSummary";
it("keeps zero waiting and empty next responsibilities explicit", () => {
  render(<WaitingSummary count={0} next={[]} />);
  expect(screen.getByText("等待 0")).toBeVisible();
  expect(screen.getByText("暂无后续责任")).toBeVisible();
  expect(screen.queryByRole("button")).not.toBeInTheDocument();
});
