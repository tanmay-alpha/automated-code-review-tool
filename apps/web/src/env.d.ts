/// <reference types="next" />
/// <reference types="next/image-types/global" />

// Next.js env variables. Variables prefixed with NEXT_PUBLIC_ are exposed to
// the browser bundle at build time; everything else is server-only.
declare namespace NodeJS {
  interface ProcessEnv {
    /**
     * Base URL of the Spring Boot backend. Used by `lib/api.ts` so the
     * frontend can call `/api/auth/me`, `/api/repos`, etc. without
     * knowing the absolute origin.
     */
    NEXT_PUBLIC_API_BASE_URL: string;
  }
}