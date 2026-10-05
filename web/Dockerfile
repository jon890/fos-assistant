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
# 비밀값은 요청을 받을 때 읽으므로 빌드에는 배포되지 않는 자리표시자만 준다.
# 이 다섯 값이 자리표시자의 정본이다. 로컬 pnpm build, scripts/check-local.sh, test/browser/web-server.ts 가 같은 값을 쓴다.
ENV AUTH_SECRET=build-time-placeholder
ENV ASSISTANT_JWT_SECRET=build-time-placeholder
ENV CONTROL_PLANE_BASE_URL=http://build-time-placeholder
ENV AUTH_GOOGLE_ID=build-time-placeholder
ENV AUTH_GOOGLE_SECRET=build-time-placeholder
# 화면 전환을 끄려면 build arg 로 off 를 준다. 주지 않으면 코드의 기본값을 따른다.
ARG NEXT_PUBLIC_VIEW_TRANSITION
ENV NEXT_PUBLIC_VIEW_TRANSITION=${NEXT_PUBLIC_VIEW_TRANSITION}
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
