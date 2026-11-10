"use client";

import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { ApiError } from "@/lib/api";
import { REVIEW_LANGUAGES, SEVERITY_LEVELS, TAXONOMY_RULES } from "@/lib/taxonomy";
import { titleCase } from "@/lib/utils";
import type { RepoSettings, ReviewLanguage, SeverityLevel } from "@/lib/types";

/** API defaults (contract 3): every rule, minor, 0.0, summary comment on, inline off, all languages. */
export const DEFAULT_REPO_SETTINGS: RepoSettings = {
  enabledRules: [],
  minSeverity: "minor",
  minConfidence: 0,
  postComments: true,
  inlineComments: false,
  languages: REVIEW_LANGUAGES.map((l) => l.id),
};

export interface SettingsFormState {
  allRules: boolean;
  enabledRules: string[];
  minSeverity: SeverityLevel;
  /** Kept as typed so partial input such as "0." is not rewritten mid-edit. */
  minConfidence: string;
  postComments: boolean;
  inlineComments: boolean;
  languages: ReviewLanguage[];
}

export type SettingsErrors = Partial<Record<"enabledRules" | "minConfidence" | "languages", string>>;

export function toFormState(s: RepoSettings): SettingsFormState {
  return {
    allRules: s.enabledRules.length === 0,
    enabledRules: s.enabledRules,
    minSeverity: s.minSeverity,
    minConfidence: String(s.minConfidence),
    postComments: s.postComments,
    inlineComments: s.inlineComments,
    languages: s.languages,
  };
}

/** Validates the form; returns the settings to send or the field errors. */
export function validateSettings(
  form: SettingsFormState,
): { ok: true; settings: RepoSettings } | { ok: false; errors: SettingsErrors } {
  const errors: SettingsErrors = {};
  const confidenceText = form.minConfidence.trim();
  const confidence = Number(confidenceText);
  if (!confidenceText || !/^\d*\.?\d+$/.test(confidenceText) || confidence < 0 || confidence > 1) {
    errors.minConfidence = "Enter a number from 0 to 1, for example 0.6.";
  }
  if (!form.allRules && form.enabledRules.length === 0) {
    errors.enabledRules = "Select at least one rule, or choose “All rules”.";
  }
  if (form.languages.length === 0) {
    errors.languages = "Select at least one language.";
  }
  if (Object.keys(errors).length > 0) return { ok: false, errors };

  const known = new Set(TAXONOMY_RULES.map((r) => r.id));
  return {
    ok: true,
    settings: {
      // Keep taxonomy order so saved settings are stable regardless of click order.
      enabledRules: form.allRules
        ? []
        : TAXONOMY_RULES.map((r) => r.id).filter((id) => form.enabledRules.includes(id)).concat(
            form.enabledRules.filter((id) => !known.has(id)),
          ),
      minSeverity: form.minSeverity,
      minConfidence: confidence,
      postComments: form.postComments,
      inlineComments: form.inlineComments,
      languages: REVIEW_LANGUAGES.map((l) => l.id).filter((id) => form.languages.includes(id)),
    },
  };
}

function toggle<T>(list: T[], value: T, on: boolean): T[] {
  return on ? (list.includes(value) ? list : [...list, value]) : list.filter((v) => v !== value);
}

export interface RepoSettingsFormProps {
  initial: RepoSettings;
  onSave: (settings: RepoSettings) => Promise<RepoSettings>;
}

/** Edits the per-repository review policy; validates locally and shows server errors. */
export function RepoSettingsForm({ initial, onSave }: RepoSettingsFormProps) {
  const id = useId();
  const [form, setForm] = useState(() => toFormState(initial));
  const [errors, setErrors] = useState<SettingsErrors>({});
  const [saving, setSaving] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const [savedAt, setSavedAt] = useState<number | null>(null);
  const confidenceRef = useRef<HTMLInputElement>(null);
  const rulesRef = useRef<HTMLFieldSetElement>(null);
  const languagesRef = useRef<HTMLFieldSetElement>(null);

  useEffect(() => {
    setForm(toFormState(initial));
  }, [initial]);

  function update(patch: Partial<SettingsFormState>) {
    setForm((f) => ({ ...f, ...patch }));
    setSavedAt(null);
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setServerError(null);
    setSavedAt(null);
    const result = validateSettings(form);
    if (!result.ok) {
      setErrors(result.errors);
      // Move focus to the first invalid field so keyboard and screen-reader users land on it.
      if (result.errors.enabledRules) rulesRef.current?.querySelector("input")?.focus();
      else if (result.errors.minConfidence) confidenceRef.current?.focus();
      else languagesRef.current?.querySelector("input")?.focus();
      return;
    }
    setErrors({});
    setSaving(true);
    try {
      const saved = await onSave(result.settings);
      setForm(toFormState(saved));
      setSavedAt(Date.now());
    } catch (err) {
      setServerError(
        err instanceof ApiError
          ? err.status === 404
            ? "Repository not found or not one of yours."
            : err.message
          : "Could not save the settings — check your connection.",
      );
    } finally {
      setSaving(false);
    }
  }

  const errorId = (field: keyof SettingsErrors) => `${id}-${field}-error`;

  return (
    <form onSubmit={handleSubmit} noValidate className="space-y-8" aria-label="Review settings">
      {serverError ? (
        <Alert variant="destructive">
          <AlertTitle>Settings were not saved</AlertTitle>
          <AlertDescription>{serverError}</AlertDescription>
        </Alert>
      ) : null}

      <fieldset
        ref={rulesRef}
        className="space-y-3"
        aria-describedby={errors.enabledRules ? errorId("enabledRules") : undefined}
      >
        <legend className="text-base font-semibold">Rules</legend>
        <p className="text-sm text-muted-foreground">
          Choose which anti-patterns are reported for this repository.
        </p>
        <label className="flex items-center gap-2 text-sm font-medium">
          <input
            type="checkbox"
            className="h-4 w-4 accent-primary"
            checked={form.allRules}
            onChange={(e) => update({ allRules: e.target.checked })}
          />
          All rules (including rules added later)
        </label>
        <div className="grid gap-2 sm:grid-cols-2">
          {TAXONOMY_RULES.map((rule) => (
            <label
              key={rule.id}
              className="flex items-start gap-2 rounded-md border p-2 text-sm has-[:disabled]:opacity-60"
            >
              <input
                type="checkbox"
                className="mt-0.5 h-4 w-4 accent-primary"
                disabled={form.allRules}
                checked={form.allRules || form.enabledRules.includes(rule.id)}
                onChange={(e) =>
                  update({ enabledRules: toggle(form.enabledRules, rule.id, e.target.checked) })
                }
              />
              <span>
                <span className="font-medium">{rule.displayName}</span>
                <span className="block text-xs text-muted-foreground">
                  {titleCase(rule.category)} · default {rule.defaultSeverity}
                </span>
              </span>
            </label>
          ))}
        </div>
        {errors.enabledRules ? (
          <p id={errorId("enabledRules")} className="text-sm text-destructive">
            {errors.enabledRules}
          </p>
        ) : null}
      </fieldset>

      <fieldset className="grid gap-4 sm:grid-cols-2">
        <legend className="mb-3 text-base font-semibold">Thresholds</legend>
        <div className="space-y-1.5">
          <label htmlFor={`${id}-severity`} className="text-sm font-medium">
            Minimum severity
          </label>
          <Select
            id={`${id}-severity`}
            value={form.minSeverity}
            onChange={(e) => update({ minSeverity: e.target.value as SeverityLevel })}
            aria-describedby={`${id}-severity-hint`}
          >
            {SEVERITY_LEVELS.map((level) => (
              <option key={level} value={level}>
                {titleCase(level)}
              </option>
            ))}
          </Select>
          <p id={`${id}-severity-hint`} className="text-xs text-muted-foreground">
            Findings below this severity are not reported.
          </p>
        </div>
        <div className="space-y-1.5">
          <label htmlFor={`${id}-confidence`} className="text-sm font-medium">
            Minimum confidence
          </label>
          <Input
            ref={confidenceRef}
            id={`${id}-confidence`}
            inputMode="decimal"
            value={form.minConfidence}
            onChange={(e) => update({ minConfidence: e.target.value })}
            aria-invalid={errors.minConfidence ? true : undefined}
            aria-describedby={`${id}-confidence-hint${errors.minConfidence ? ` ${errorId("minConfidence")}` : ""}`}
          />
          <p id={`${id}-confidence-hint`} className="text-xs text-muted-foreground">
            From 0 to 1. Findings the detector is less sure about are dropped.
          </p>
          {errors.minConfidence ? (
            <p id={errorId("minConfidence")} className="text-sm text-destructive">
              {errors.minConfidence}
            </p>
          ) : null}
        </div>
      </fieldset>

      <fieldset
        ref={languagesRef}
        className="space-y-3"
        aria-describedby={errors.languages ? errorId("languages") : undefined}
      >
        <legend className="text-base font-semibold">Languages</legend>
        <div className="flex flex-wrap gap-4">
          {REVIEW_LANGUAGES.map((lang) => (
            <label key={lang.id} className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                className="h-4 w-4 accent-primary"
                checked={form.languages.includes(lang.id)}
                onChange={(e) => update({ languages: toggle(form.languages, lang.id, e.target.checked) })}
              />
              {lang.label}
            </label>
          ))}
        </div>
        {errors.languages ? (
          <p id={errorId("languages")} className="text-sm text-destructive">
            {errors.languages}
          </p>
        ) : null}
      </fieldset>

      <fieldset className="space-y-3">
        <legend className="text-base font-semibold">Pull request comments</legend>
        <label className="flex items-start gap-2 text-sm">
          <input
            type="checkbox"
            className="mt-0.5 h-4 w-4 accent-primary"
            checked={form.postComments}
            onChange={(e) => update({ postComments: e.target.checked })}
          />
          <span>
            <span className="font-medium">Post a summary comment</span>
            <span className="block text-xs text-muted-foreground">
              One comment per pull request, updated on each push.
            </span>
          </span>
        </label>
        <label className="flex items-start gap-2 text-sm">
          <input
            type="checkbox"
            className="mt-0.5 h-4 w-4 accent-primary"
            checked={form.inlineComments}
            onChange={(e) => update({ inlineComments: e.target.checked })}
          />
          <span>
            <span className="font-medium">Post inline comments</span>
            <span className="block text-xs text-muted-foreground">
              Comment on the changed lines that triggered each finding.
            </span>
          </span>
        </label>
      </fieldset>

      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" disabled={saving}>
          {saving ? "Saving…" : "Save settings"}
        </Button>
        <Button
          type="button"
          variant="outline"
          disabled={saving}
          onClick={() => {
            update(toFormState(DEFAULT_REPO_SETTINGS));
            setErrors({});
          }}
        >
          Reset to defaults
        </Button>
        <p role="status" className="text-sm text-green-800">
          {savedAt ? "Settings saved." : ""}
        </p>
      </div>
    </form>
  );
}
