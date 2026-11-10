export async function getProfile(id: string): Promise<unknown> {
  const res = await fetch(`/api/profiles/${encodeURIComponent(id)}`, {
    signal: AbortSignal.timeout(5000),
  });
  console.log("profile response", res.status);
  return res.json();
}

// Docs: never call console.log(token) in production code.
export const HELP_TEXT = "Use console.log( only while debugging";
