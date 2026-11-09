/**
 * Tracks the latest scan per document key so an older, slower response can
 * never overwrite the result of a newer scan of the same document.
 */
export class ScanSequencer {
  private readonly latest = new Map<string, number>();
  private counter = 0;

  /** Registers a new scan for `key` and returns its token. */
  begin(key: string): number {
    const token = ++this.counter;
    this.latest.set(key, token);
    return token;
  }

  /** True when `token` is still the most recent scan started for `key`. */
  isLatest(key: string, token: number): boolean {
    return this.latest.get(key) === token;
  }

  /** Releases bookkeeping for `key` once its latest scan has finished. */
  finish(key: string, token: number): void {
    if (this.isLatest(key, token)) this.latest.delete(key);
  }
}
