import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { FindingCard } from "@/components/FindingCard";
import { ApiError, recordFindingAction } from "@/lib/api";
import type * as Api from "@/lib/api";
import type { FindingActionResponse, FindingDto } from "@/lib/types";

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof Api>();
  return { ...actual, recordFindingAction: vi.fn() };
});

const recordMock = vi.mocked(recordFindingAction);

afterEach(() => {
  recordMock.mockReset();
});

const FINDING: FindingDto = {
  id: "f-1",
  filePath: "src/api/users.py",
  lineStart: 12,
  lineEnd: 14,
  antiPattern: "PERFORMANCE_N_PLUS_ONE",
  category: "PERFORMANCE",
  severity: "major",
  confidence: 0.874,
  explanation: "Query executed inside a loop.",
  codeSnippet: "for u in users:\n    db.get(u.id)",
  status: "open",
  triageReason: null,
  triagedAt: null,
  evidence: null,
  remediation: null,
  caveat: null,
};

function saved(status: FindingActionResponse["status"], reason: string | null = null): FindingActionResponse {
  return { findingId: "f-1", status, reason, triagedAt: "2026-11-10T10:00:00Z", triagedBy: "octocat" };
}

describe("FindingCard", () => {
  it("renders rule, severity, confidence, file:line and the snippet", () => {
    render(<FindingCard finding={FINDING} />);
    expect(screen.getByRole("heading", { name: "N+1 Query" })).toBeInTheDocument();
    expect(screen.getByText("MAJOR")).toBeInTheDocument();
    expect(screen.getByText("87% confidence")).toBeInTheDocument();
    expect(screen.getByTestId("finding-location")).toHaveTextContent("src/api/users.py:12–14");
    expect(screen.getByText("Query executed inside a loop.")).toBeInTheDocument();
    expect(screen.getByText(/db\.get\(u\.id\)/)).toBeInTheDocument();
    expect(screen.getByTestId("finding-status")).toHaveTextContent("Open");
  });

  it("shows evidence, remediation and caveat when present", () => {
    render(
      <FindingCard
        finding={{
          ...FINDING,
          evidence: 'api_key = "[REDACTED]"',
          remediation: "Load the key from an environment variable.",
          caveat: "May be a test fixture.",
        }}
      />,
    );
    expect(screen.getByText("Evidence")).toBeInTheDocument();
    expect(screen.getByText('api_key = "[REDACTED]"')).toBeInTheDocument();
    expect(screen.queryByText(/db\.get/)).not.toBeInTheDocument();
    expect(screen.getByText("Load the key from an environment variable.")).toBeInTheDocument();
    expect(screen.getByText("May be a test fixture.")).toBeInTheDocument();
  });

  it("labels findings without a line as file-level", () => {
    render(<FindingCard finding={{ ...FINDING, lineStart: null, lineEnd: null }} />);
    expect(screen.getByTestId("finding-location")).toHaveTextContent("src/api/users.py (file-level)");
  });

  it("does not interpret finding text as HTML", () => {
    const { container } = render(
      <FindingCard
        finding={{ ...FINDING, explanation: "<img src=x onerror=alert(1)>", remediation: "<b>x</b>" }}
      />,
    );
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("b")).toBeNull();
    expect(screen.getByText("<img src=x onerror=alert(1)>")).toBeInTheDocument();
  });

  it("starts from the saved triage state and offers Reopen", () => {
    render(
      <FindingCard
        finding={{ ...FINDING, status: "false_positive", triageReason: "Test fixture", triagedAt: "2026-11-10T10:00:00Z" }}
      />,
    );
    expect(screen.getByTestId("finding-status")).toHaveTextContent("False positive");
    expect(screen.getByTestId("finding-triage")).toHaveTextContent("“Test fixture”");
    expect(screen.getByRole("button", { name: "Reopen" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "False positive…" })).toBeDisabled();
  });

  it("accepts optimistically, disables actions while pending, then takes the saved state", async () => {
    const user = userEvent.setup();
    const pending = Promise.withResolvers<FindingActionResponse>();
    recordMock.mockReturnValue(pending.promise);
    const onTriaged = vi.fn();
    render(<FindingCard finding={FINDING} onTriaged={onTriaged} />);

    await user.click(screen.getByRole("button", { name: "Accept" }));
    expect(screen.getByTestId("finding-status")).toHaveTextContent("Accepted");
    for (const button of within(screen.getByRole("group")).getAllByRole("button")) {
      expect(button).toBeDisabled();
    }
    expect(recordMock).toHaveBeenCalledWith("f-1", "accept", undefined);

    pending.resolve(saved("accepted"));
    await waitFor(() => expect(screen.getByRole("button", { name: "Reopen" })).toBeEnabled());
    expect(onTriaged).toHaveBeenCalledWith(expect.objectContaining({ id: "f-1", status: "accepted" }));
  });

  it("rolls back and shows an error when the API rejects an optimistic action", async () => {
    const user = userEvent.setup();
    recordMock.mockRejectedValue(new ApiError("nope", 404, "/api/scan/action"));
    render(<FindingCard finding={FINDING} />);

    await user.click(screen.getByRole("button", { name: "Mark fixed" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/not found/i);
    expect(screen.getByTestId("finding-status")).toHaveTextContent("Open");
  });

  it("requires a reason before marking a false positive", async () => {
    const user = userEvent.setup();
    recordMock.mockResolvedValue(saved("false_positive", "Fixture key in tests"));
    const onTriaged = vi.fn();
    render(<FindingCard finding={FINDING} onTriaged={onTriaged} />);

    await user.click(screen.getByRole("button", { name: "False positive…" }));
    const dialog = await screen.findByRole("dialog", { name: "Mark as false positive" });
    const reason = within(dialog).getByLabelText(/Reason/);
    expect(reason).toHaveFocus();

    await user.click(within(dialog).getByRole("button", { name: "Mark false positive" }));
    expect(within(dialog).getByText("A reason is required.")).toBeInTheDocument();
    expect(reason).toHaveAttribute("aria-invalid", "true");
    expect(recordMock).not.toHaveBeenCalled();

    await user.type(reason, "  Fixture key in tests  ");
    await user.click(within(dialog).getByRole("button", { name: "Mark false positive" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(recordMock).toHaveBeenCalledWith("f-1", "false_positive", "Fixture key in tests");
    expect(screen.getByTestId("finding-status")).toHaveTextContent("False positive");
    expect(screen.getByTestId("finding-triage")).toHaveTextContent("“Fixture key in tests”");
    expect(onTriaged).toHaveBeenCalledWith(
      expect.objectContaining({ status: "false_positive", triageReason: "Fixture key in tests" }),
    );
    // Focus returns to the card's actions instead of falling back to <body>.
    expect(screen.getByRole("button", { name: "Reopen" })).toHaveFocus();
  });

  it("rejects reasons over 500 characters", async () => {
    const user = userEvent.setup();
    render(<FindingCard finding={FINDING} />);
    await user.click(screen.getByRole("button", { name: "Dismiss…" }));
    const dialog = await screen.findByRole("dialog", { name: "Dismiss finding" });
    const reason = within(dialog).getByLabelText(/Reason/);
    await user.click(reason);
    await user.paste("x".repeat(501));
    await user.click(within(dialog).getByRole("button", { name: "Dismiss finding" }));
    expect(within(dialog).getByText(/500 characters or fewer/)).toBeInTheDocument();
    expect(recordMock).not.toHaveBeenCalled();
  });

  it("keeps the dialog open with the error when saving the reason fails", async () => {
    const user = userEvent.setup();
    recordMock.mockRejectedValue(new ApiError("reason: must not be blank", 400, "/api/scan/action"));
    render(<FindingCard finding={FINDING} />);
    await user.click(screen.getByRole("button", { name: "Dismiss…" }));
    const dialog = await screen.findByRole("dialog");
    await user.type(within(dialog).getByLabelText(/Reason/), "Out of scope");
    await user.click(within(dialog).getByRole("button", { name: "Dismiss finding" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("reason: must not be blank");
    expect(screen.getByTestId("finding-status")).toHaveTextContent("Open");
  });

  it("cancels the dialog with Escape without calling the API", async () => {
    const user = userEvent.setup();
    render(<FindingCard finding={FINDING} />);
    await user.click(screen.getByRole("button", { name: "Dismiss…" }));
    await screen.findByRole("dialog");
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(recordMock).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Dismiss…" })).toHaveFocus();
  });

  it("reopens a triaged finding", async () => {
    const user = userEvent.setup();
    recordMock.mockResolvedValue({ ...saved("open"), triagedAt: null });
    render(<FindingCard finding={{ ...FINDING, status: "dismissed", triageReason: "Later" }} />);
    await user.click(screen.getByRole("button", { name: "Reopen" }));
    await waitFor(() => expect(screen.getByTestId("finding-status")).toHaveTextContent("Open"));
    expect(recordMock).toHaveBeenCalledWith("f-1", "reopen", undefined);
    expect(screen.queryByRole("button", { name: "Reopen" })).not.toBeInTheDocument();
  });
});
