import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
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
};

describe("FindingCard", () => {
  it("renders the anti-pattern, severity, confidence, location and snippet", () => {
    render(<FindingCard finding={FINDING} />);

    expect(screen.getByText("Performance N Plus One")).toBeInTheDocument();
    expect(screen.getByText("MAJOR")).toBeInTheDocument();
    expect(screen.getByText("87%")).toBeInTheDocument();
    expect(screen.getByText("src/api/users.py")).toBeInTheDocument();
    expect(screen.getByText(/L12–14/)).toBeInTheDocument();
    expect(screen.getByText("Query executed inside a loop.")).toBeInTheDocument();
    expect(screen.getByText(/db\.get\(u\.id\)/)).toBeInTheDocument();
  });

  it("shows no disposition badge for an open finding", () => {
    render(<FindingCard finding={FINDING} />);
    expect(screen.queryByTestId("finding-disposition")).toBeNull();
  });

  it("starts from the saved disposition", () => {
    render(<FindingCard finding={{ ...FINDING, status: "dismissed" }} />);
    expect(screen.getByTestId("finding-disposition")).toHaveTextContent("Dismissed");
    expect(screen.getByRole("button", { name: "Dismiss" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
  });

  it("labels findings without a line as file-level", () => {
    render(
      <FindingCard finding={{ ...FINDING, lineStart: null, lineEnd: null }} />,
    );
    expect(screen.getByText(/file-level/)).toBeInTheDocument();
  });

  it("does not interpret the explanation as HTML", () => {
    const { container } = render(
      <FindingCard
        finding={{ ...FINDING, explanation: "<script>alert(1)</script>" }}
      />,
    );
    expect(container.querySelector("script")).toBeNull();
    expect(screen.getByText("<script>alert(1)</script>")).toBeInTheDocument();
  });

  it("records an action and shows the confirmed disposition", async () => {
    recordMock.mockResolvedValue({ findingId: "f-1", action: "fix", status: "fixed" });
    render(<FindingCard finding={FINDING} />);

    fireEvent.click(screen.getByRole("button", { name: "Mark fixed" }));

    expect(recordMock).toHaveBeenCalledWith("f-1", "fix");
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Mark fixed" })).toHaveAttribute(
        "aria-pressed",
        "true",
      ),
    );
    expect(screen.getByTestId("finding-disposition")).toHaveTextContent("Fixed");
    expect(screen.getByRole("button", { name: "Accept" })).toBeEnabled();
  });

  it("shows the disposition optimistically and disables buttons while pending", async () => {
    let resolve!: (r: FindingActionResponse) => void;
    recordMock.mockReturnValue(new Promise((r) => (resolve = r)));
    render(<FindingCard finding={FINDING} />);

    fireEvent.click(screen.getByRole("button", { name: "Accept" }));

    expect(screen.getByTestId("finding-disposition")).toHaveTextContent("Accepted");
    expect(screen.getByRole("button", { name: "Dismiss" })).toBeDisabled();

    resolve({ findingId: "f-1", action: "accept", status: "accepted" });
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Dismiss" })).toBeEnabled(),
    );
  });

  it("rolls back and shows an error when the API rejects the action", async () => {
    recordMock.mockRejectedValue(new ApiError("nope", 404, "u"));
    render(<FindingCard finding={FINDING} />);

    fireEvent.click(screen.getByRole("button", { name: "Dismiss" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Finding not found or not in one of your repositories.",
    );
    expect(screen.queryByTestId("finding-disposition")).toBeNull();
    expect(screen.getByRole("button", { name: "Dismiss" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
  });

  it("rolls back to the saved disposition when a change fails", async () => {
    recordMock.mockRejectedValue(new ApiError("boom", 500, "u"));
    render(<FindingCard finding={{ ...FINDING, status: "accepted" }} />);

    fireEvent.click(screen.getByRole("button", { name: "Mark fixed" }));
    expect(screen.getByTestId("finding-disposition")).toHaveTextContent("Fixed");

    expect(await screen.findByRole("alert")).toHaveTextContent("HTTP 500");
    expect(screen.getByTestId("finding-disposition")).toHaveTextContent("Accepted");
  });
});
