/// <reference types="next" />
/// <reference types="next/image-types/global" />

// Server-only runtime environment. Nothing here is exposed to the browser:
// the browser calls same-origin `/api/*` and the route handler forwards it.
declare namespace NodeJS {
  interface ProcessEnv {
    /**
     * Where `app/api/[...path]/route.ts` forwards `/api/*`: an origin such as
     * `http://api:8080` or a bare `host:port`. Read on every request. Required
     * in production; `next dev` falls back to http://localhost:8080.
     */
    API_INTERNAL_URL?: string;
    /** Proxy request-body cap in bytes; defaults to 1048576 (the API's limit). */
    API_PROXY_MAX_BODY_BYTES?: string;
  }
}
