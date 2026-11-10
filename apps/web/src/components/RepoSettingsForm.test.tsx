import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  DEFAULT_REPO_SETTINGS,
  RepoSettingsForm,
  toFormState,
  validateSettings,
} from "@/components/RepoSettingsForm";
import { ApiError } from "@/lib/api";
import type { RepoSettings } from "@/lib/types";

describe("validateSettings", () => {
  it("accepts the defaults unchanged", () => {
    expect(validateSettings(toFormState(DEFAULT_REPO_SETTINGS))).toEqual({
      ok: true,
      settings: DEFAULT_REPO_SETTINGS,
    });
  });

  it.each(["", "-0.1", "1.01", "abc", "0.5.1", "1e-1"])("rejects minConfidence %j", (value) => {
    const result = validateSettings({ ...toFormState(DEFAULT_REPO_SETTINGS), minConfidence: value });
    expect(result.ok).toBe(false);
    expect(!result.ok && result.errors.minConfidence).toBeTruthy();
  });

  it.each(["0", "1", "0.75", ".5"])("accepts minConfidence %j", (value) => {
    const result = validateSettings({ ...toFormState(DEFAULT_REPO_SETTINGS), minConfidence: value });
    expect(result.ok && result.settings.minConfidence).toBe(Number(value));
  });

  it("requires a rule when not using all rules, and at least one language", () => {
    const result = validateSettings({
      ...toFormState(DEFAULT_REPO_SETTINGS),
      allRules: false,
      enabledRules: [],
      languages: [],
    });
    expect(result).toEqual({
      ok: false,
      errors: {
        enabledRules: expect.any(String),
        languages: expect.any(String),
      },
    });
  });

  it("sends enabled rules and languages in canonical order", () => {
    const result = validateSettings({
      ...toFormState(DEFAULT_REPO_SETTINGS),
      allRules: false,
      enabledRules: ["MAINTAINABILITY_PRINT_STATEMENT", "SECURITY_SQL_INJECTION"],
      languages: ["java", "python"],
    });
    expect(result.ok && result.settings.enabledRules).toEqual([
      "SECURITY_SQL_INJECTION",
      "MAINTAINABILITY_PRINT_STATEMENT",
    ]);
    expect(result.ok && result.settings.languages).toEqual(["python", "java"]);
  });

  it("sends an empty list for all rules", () => {
    const result = validateSettings({
      ...toFormState(DEFAULT_REPO_SETTINGS),
      allRules: true,
      enabledRules: ["SECURITY_SQL_INJECTION"],
    });
    expect(result.ok && result.settings.enabledRules).toEqual([]);
  });
});

describe("RepoSettingsForm", () => {
  const INITIAL: RepoSettings = {
    enabledRules: ["SECURITY_HARDCODED_SECRET"],
    minSeverity: "major",
    minConfidence: 0.5,
    postComments: true,
    inlineComments: false,
    languages: ["python"],
  };

  it("renders saved settings with labelled controls", () => {
    render(<RepoSettingsForm initial={INITIAL} onSave={vi.fn()} />);
    expect(screen.getByLabelText("All rules (including rules added later)")).not.toBeChecked();
    expect(screen.getByRole("checkbox", { name: /Hardcoded Secret/ })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: /SQL Injection/ })).not.toBeChecked();
    expect(screen.getByLabelText("Minimum severity")).toHaveValue("major");
    expect(screen.getByLabelText("Minimum confidence")).toHaveValue("0.5");
    expect(screen.getByRole("checkbox", { name: "Python" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Java" })).not.toBeChecked();
    expect(screen.getByRole("checkbox", { name: /Post a summary comment/ })).toBeChecked();
  });

  it("blocks saving invalid input and focuses the field", async () => {
    const user = userEvent.setup();
    const onSave = vi.fn();
    render(<RepoSettingsForm initial={INITIAL} onSave={onSave} />);
    const confidence = screen.getByLabelText("Minimum confidence");
    await user.clear(confidence);
    await user.type(confidence, "1.5");
    await user.click(screen.getByRole("button", { name: "Save settings" }));

    expect(onSave).not.toHaveBeenCalled();
    expect(confidence).toHaveAttribute("aria-invalid", "true");
    expect(confidence).toHaveFocus();
    expect(confidence).toHaveAccessibleDescription(/Enter a number from 0 to 1/);
  });

  it("saves valid settings and announces success", async () => {
    const user = userEvent.setup();
    const onSave = vi.fn(async (s: RepoSettings) => s);
    render(<RepoSettingsForm initial={INITIAL} onSave={onSave} />);

    await user.click(screen.getByRole("checkbox", { name: /SQL Injection/ }));
    await user.selectOptions(screen.getByLabelText("Minimum severity"), "critical");
    await user.click(screen.getByRole("checkbox", { name: "Java" }));
    await user.click(screen.getByRole("checkbox", { name: /Post inline comments/ }));
    await user.click(screen.getByRole("button", { name: "Save settings" }));

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Settings saved."));
    expect(onSave).toHaveBeenCalledWith({
      enabledRules: ["SECURITY_HARDCODED_SECRET", "SECURITY_SQL_INJECTION"],
      minSeverity: "critical",
      minConfidence: 0.5,
      postComments: true,
      inlineComments: true,
      languages: ["python", "java"],
    });
  });

  it("shows the server's validation message when the API rejects the save", async () => {
    const user = userEvent.setup();
    const onSave = vi.fn(async () => {
      throw new ApiError("enabledRules: unknown rule FOO", 400, "/api/repos/r/settings", "VALIDATION_FAILED");
    });
    render(<RepoSettingsForm initial={INITIAL} onSave={onSave} />);
    await user.click(screen.getByRole("button", { name: "Save settings" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("enabledRules: unknown rule FOO");
    expect(screen.getByRole("status")).toHaveTextContent("");
  });

  it("resets the form to the API defaults without saving", async () => {
    const user = userEvent.setup();
    const onSave = vi.fn();
    render(<RepoSettingsForm initial={INITIAL} onSave={onSave} />);
    await user.click(screen.getByRole("button", { name: "Reset to defaults" }));
    expect(screen.getByLabelText("All rules (including rules added later)")).toBeChecked();
    expect(screen.getByLabelText("Minimum severity")).toHaveValue("minor");
    expect(screen.getByLabelText("Minimum confidence")).toHaveValue("0");
    expect(screen.getByRole("checkbox", { name: "TypeScript" })).toBeChecked();
    expect(onSave).not.toHaveBeenCalled();
  });
});
