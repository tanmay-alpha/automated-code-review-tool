export async function getProfile(id: string): Promise<unknown> {
  const res = await fetch(`/api/profiles/${encodeURIComponent(id)}`, {
    signal: AbortSignal.timeout(5000),
  });
  return res.json();
}
