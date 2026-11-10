import { AlertTriangle, CheckCircle2, XCircle } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { formatDateTime, timeAgo } from "@/lib/utils";
import type { ComponentHealth, DiagnosticsResponse, HealthStatus } from "@/lib/types";

const STATUS_STYLE: Record<HealthStatus, { label: string; className: string; Icon: typeof CheckCircle2 }> = {
  up: { label: "Up", className: "bg-green-100 text-green-800 border-green-300", Icon: CheckCircle2 },
  degraded: { label: "Degraded", className: "bg-yellow-100 text-yellow-900 border-yellow-300", Icon: AlertTriangle },
  down: { label: "Down", className: "bg-red-100 text-red-800 border-red-300", Icon: XCircle },
};

function styleFor(status: string) {
  return Object.hasOwn(STATUS_STYLE, status)
    ? STATUS_STYLE[status as HealthStatus]
    : { label: status, className: "", Icon: AlertTriangle };
}

export function HealthBadge({ status }: { status: string }) {
  const { label, className, Icon } = styleFor(status);
  return (
    <Badge variant="outline" className={`gap-1 border ${className}`}>
      <Icon className="h-3 w-3" aria-hidden="true" />
      {label}
    </Badge>
  );
}

const COMPONENTS: { key: keyof DiagnosticsResponse["components"]; title: string; purpose: string }[] = [
  { key: "database", title: "Database", purpose: "PostgreSQL: repositories, reviews and findings." },
  { key: "redis", title: "Redis", purpose: "Sessions and rate limits. Sign-in fails while it is down." },
  { key: "mlWorker", title: "ML worker", purpose: "Runs the detectors for webhook reviews and file scans." },
  { key: "github", title: "GitHub", purpose: "Fetches diffs and publishes review comments." },
];

/** Human-readable age for the oldest queued webhook. */
export function formatAge(seconds: number | null): string {
  if (seconds == null) return "—";
  if (seconds < 60) return `${Math.round(seconds)}s`;
  if (seconds < 3600) return `${Math.round(seconds / 60)}m`;
  if (seconds < 86_400) return `${Math.round(seconds / 3600)}h`;
  return `${Math.round(seconds / 86_400)}d`;
}

function Stat({ label, value, warn }: { label: string; value: string | number; warn?: boolean }) {
  return (
    <div className="rounded-lg border p-4">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className={`mt-1 text-2xl font-bold tabular-nums ${warn ? "text-red-700" : ""}`}>{value}</dd>
    </div>
  );
}

/** Renders GET /api/diagnostics: overall status, each dependency, and the caller's queues. */
export function DiagnosticsView({ data }: { data: DiagnosticsResponse }) {
  const overall = styleFor(data.status);
  const unhealthy = COMPONENTS.filter(({ key }) => data.components[key].status !== "up");
  const q = data.queue;

  return (
    <div className="space-y-8">
      {data.status === "up" ? (
        <Alert>
          <CheckCircle2 className="h-4 w-4" aria-hidden="true" />
          <AlertTitle>All systems operational</AlertTitle>
          <AlertDescription>
            Checked <time dateTime={data.checkedAt} title={formatDateTime(data.checkedAt)}>{timeAgo(data.checkedAt)}</time>.
          </AlertDescription>
        </Alert>
      ) : (
        <Alert variant={data.status === "down" ? "destructive" : "warning"} data-testid="diagnostics-banner">
          <overall.Icon className="h-4 w-4" aria-hidden="true" />
          <AlertTitle>
            {data.status === "down" ? "Reviews are not running" : "Reviews may be delayed"}
          </AlertTitle>
          <AlertDescription>
            {unhealthy.length > 0
              ? `Affected: ${unhealthy.map((c) => c.title).join(", ")}. `
              : ""}
            Checked{" "}
            <time dateTime={data.checkedAt} title={formatDateTime(data.checkedAt)}>
              {timeAgo(data.checkedAt)}
            </time>
            .
          </AlertDescription>
        </Alert>
      )}

      <section aria-labelledby="components-heading">
        <h2 id="components-heading" className="mb-3 text-xl font-semibold">
          Components
        </h2>
        <div className="grid gap-4 sm:grid-cols-2">
          {COMPONENTS.map(({ key, title, purpose }) => {
            const health: ComponentHealth & { engine?: string | null } = data.components[key];
            return (
              <Card key={key} data-testid={`component-${key}`}>
                <CardHeader className="flex flex-row items-start justify-between gap-3 space-y-0 pb-2">
                  <div className="space-y-1">
                    <CardTitle className="text-base">
                      <h3>{title}</h3>
                    </CardTitle>
                    <CardDescription>{purpose}</CardDescription>
                  </div>
                  <HealthBadge status={health.status} />
                </CardHeader>
                <CardContent className="space-y-1 text-sm">
                  {key === "mlWorker" ? (
                    <p>
                      Engine: <span className="font-mono">{health.engine ?? "unknown"}</span>
                    </p>
                  ) : null}
                  <p className="break-words text-muted-foreground">{health.detail || "No details reported."}</p>
                </CardContent>
              </Card>
            );
          })}
        </div>
      </section>

      <section aria-labelledby="queue-heading">
        <h2 id="queue-heading" className="mb-1 text-xl font-semibold">
          Your review queue
        </h2>
        <p className="mb-3 text-sm text-muted-foreground">
          Webhook deliveries and ML ingestion events for your repositories.
        </p>
        {q ? (
          <dl className="grid grid-cols-2 gap-4 md:grid-cols-3">
            <Stat label="Webhooks pending" value={q.webhooksPending} />
            <Stat label="Webhooks retrying" value={q.webhooksRetrying} warn={q.webhooksRetrying > 0} />
            <Stat label="Dead-lettered webhooks" value={q.webhooksDeadLetter} warn={q.webhooksDeadLetter > 0} />
            <Stat label="Oldest pending" value={formatAge(q.oldestPendingSeconds)} />
            <Stat label="Outbox pending" value={q.outboxPending} />
            <Stat label="Outbox failed" value={q.outboxFailed} warn={q.outboxFailed > 0} />
          </dl>
        ) : (
          <p className="rounded-lg border p-6 text-center text-sm text-muted-foreground" data-testid="queue-unavailable">
            Queue counts are unavailable while the database cannot be reached.
          </p>
        )}
      </section>
    </div>
  );
}
