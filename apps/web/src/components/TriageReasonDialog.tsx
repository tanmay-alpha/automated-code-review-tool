"use client";

import { useEffect, useId, useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/input";

/** Matches the API's limit on `FindingActionRequest.reason` (after trimming). */
export const MAX_REASON_CHARS = 500;

/** Returns an error message for a triage reason, or null when it is valid. */
export function validateReason(reason: string): string | null {
  const trimmed = reason.trim();
  if (!trimmed) return "A reason is required.";
  if (trimmed.length > MAX_REASON_CHARS) {
    return `Keep the reason to ${MAX_REASON_CHARS} characters or fewer (currently ${trimmed.length}).`;
  }
  return null;
}

export interface TriageReasonDialogProps {
  open: boolean;
  title: string;
  description: string;
  confirmLabel: string;
  /** Resolves on success; rejects with an Error whose message is shown in the dialog. */
  onConfirm: (reason: string) => Promise<void>;
  onOpenChange: (open: boolean) => void;
  /**
   * Where focus goes once the dialog closes. The dialog has no Radix trigger,
   * so without this focus would fall back to <body>.
   */
  onRestoreFocus?: () => void;
}

/**
 * Modal that collects the reason the API requires for `dismiss` and
 * `false_positive`. Focus starts in the textarea, Escape cancels, and the
 * dialog stays open with the error if the request fails.
 */
export function TriageReasonDialog({
  open,
  title,
  description,
  confirmLabel,
  onConfirm,
  onOpenChange,
  onRestoreFocus,
}: TriageReasonDialogProps) {
  const fieldId = useId();
  const [reason, setReason] = useState("");
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  useEffect(() => {
    if (open) {
      setReason("");
      setTouched(false);
      setSubmitError(null);
    }
  }, [open]);

  const validation = validateReason(reason);
  const showValidation = touched && validation !== null;
  const remaining = MAX_REASON_CHARS - reason.trim().length;

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setTouched(true);
    if (validation) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      await onConfirm(reason.trim());
      onOpenChange(false);
    } catch (err) {
      setSubmitError(err instanceof Error ? err.message : "Could not save the reason.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={(next) => !submitting && onOpenChange(next)}>
      <DialogContent
        onCloseAutoFocus={(e) => {
          if (!onRestoreFocus) return;
          e.preventDefault();
          onRestoreFocus();
        }}
      >
        <form onSubmit={handleSubmit} className="grid gap-4" noValidate>
          <DialogHeader>
            <DialogTitle>{title}</DialogTitle>
            <DialogDescription>{description}</DialogDescription>
          </DialogHeader>
          <div className="grid gap-2">
            <label htmlFor={`${fieldId}-reason`} className="text-sm font-medium">
              Reason <span aria-hidden="true">*</span>
            </label>
            <Textarea
              id={`${fieldId}-reason`}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              onBlur={() => setTouched(true)}
              required
              aria-required="true"
              aria-invalid={showValidation}
              aria-describedby={`${fieldId}-hint${showValidation ? ` ${fieldId}-error` : ""}`}
              rows={4}
            />
            <p id={`${fieldId}-hint`} className="text-xs text-muted-foreground">
              Shared with your team in the review history. {remaining >= 0 ? `${remaining} characters left.` : ""}
            </p>
            {showValidation ? (
              <p id={`${fieldId}-error`} className="text-sm text-destructive">
                {validation}
              </p>
            ) : null}
          </div>
          {submitError ? (
            <p role="alert" className="text-sm text-destructive">
              {submitError}
            </p>
          ) : null}
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => onOpenChange(false)}
              disabled={submitting}
            >
              Cancel
            </Button>
            <Button type="submit" disabled={submitting}>
              {submitting ? "Saving…" : confirmLabel}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
