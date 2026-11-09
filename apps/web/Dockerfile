# syntax=docker/dockerfile:1
# Node 20 reached end of life in April 2026; 24 is the active LTS line.
FROM node:24-alpine AS dependencies
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:24-alpine AS build
WORKDIR /app
COPY --from=dependencies /app/node_modules ./node_modules
COPY . .
# No API URL is baked into the bundle: the browser calls /api/* on this origin
# and app/api/[...path]/route.ts forwards it to API_INTERNAL_URL at runtime.
RUN npm run build

FROM node:24-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY --chown=node:node --from=build /app/.next ./.next
# `next start` reads next.config.mjs at runtime (security headers live there).
COPY --from=build /app/next.config.mjs ./
USER node
EXPOSE 3000
# Build output is read-only for the runtime user.
RUN chmod -R 500 /app/.next
# Required at runtime: where /api/* is forwarded, e.g. http://api:8080 or a
# Render private-network host:port. The proxy answers 500 without it.
ENV API_INTERNAL_URL=
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=5 \
    CMD node -e "require('http').get('http://localhost:3000', (res) => process.exit(res.statusCode === 200 ? 0 : 1)).on('error', () => process.exit(1))" || exit 1
CMD ["npm", "start"]
