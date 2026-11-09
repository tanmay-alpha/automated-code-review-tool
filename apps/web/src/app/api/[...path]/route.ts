import { proxyApiRequest } from "@/lib/apiProxy";

// Every request is forwarded at runtime; nothing here may be cached or
// prerendered, and the upstream URL is read from the server environment on
// each call (never inlined at build time).
export const dynamic = "force-dynamic";
export const runtime = "nodejs";

function handle(request: Request): Promise<Response> {
  return proxyApiRequest(request, {
    env: {
      API_INTERNAL_URL: process.env.API_INTERNAL_URL,
      API_PROXY_MAX_BODY_BYTES: process.env.API_PROXY_MAX_BODY_BYTES,
      NODE_ENV: process.env.NODE_ENV,
    },
  });
}

export {
  handle as GET,
  handle as HEAD,
  handle as POST,
  handle as PUT,
  handle as PATCH,
  handle as DELETE,
  handle as OPTIONS,
};
