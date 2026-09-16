# Stage 1: dependencies
FROM node:22-alpine AS deps

RUN corepack enable && corepack prepare pnpm@10.22.0 --activate
WORKDIR /app

COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

# Stage 2: build
FROM node:22-alpine AS builder

RUN corepack enable && corepack prepare pnpm@10.22.0 --activate
WORKDIR /app

COPY --from=deps /app/node_modules ./node_modules
COPY . .

ENV NEXT_TELEMETRY_DISABLED=1
# Secrets are read at request time, so the build only needs placeholders that never ship.
ENV AUTH_SECRET=build-time-placeholder
ENV ASSISTANT_JWT_SECRET=build-time-placeholder
ENV CONTROL_PLANE_BASE_URL=http://build-time-placeholder
ENV AUTH_GOOGLE_ID=build-time-placeholder
ENV AUTH_GOOGLE_SECRET=build-time-placeholder
RUN pnpm build

# Stage 3: runtime
FROM node:22-alpine AS runner

WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

RUN addgroup -S nodejs && adduser -S nextjs -G nodejs

COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static
COPY --from=builder /app/public ./public

USER nextjs:nodejs

EXPOSE 3000

CMD ["node", "server.js"]
