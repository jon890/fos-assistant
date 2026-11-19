# Stage 1: dependencies
FROM node:24.22.3-alpine AS deps

RUN corepack enable && corepack prepare pnpm@10.22.0 --activate
WORKDIR /app

COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

# Stage 2: build
FROM node:24.22.3-alpine AS builder

RUN corepack enable && corepack prepare pnpm@10.22.0 --activate
WORKDIR /app

COPY --from=deps /app/node_modules ./node_modules
COPY . .

ENV NEXT_TELEMETRY_DISABLED=1
# 비밀값은 요청을 받을 때 읽으므로 빌드에는 배포되지 않는 자리표시자만 준다.
# 이 다섯 값이 자리표시자의 정본이다. 로컬 pnpm build, scripts/dev/check-local.sh, web/test/browser/web-server.ts 가 같은 값을 쓴다.
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
FROM node:24.22.3-alpine AS runner

WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

# 실행은 node만 사용하므로 패키지 관리자와 그 의존성을 이미지에서 뺀다.
RUN rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack \
    /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack \
    /usr/local/bin/pnpm /usr/local/bin/pnpx /usr/local/bin/yarn /usr/local/bin/yarnpkg \
    /root/.cache/node/corepack /root/.npm \
    && addgroup -S nodejs && adduser -S nextjs -G nodejs

COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static
COPY --from=builder /app/public ./public

# 「개발 문서」 화면이 읽는 문서다(web/docs/adr/ADR-20261117-dev-docs-image.md).
# 소유자는 root 로 두고 `--chmod=755` 로 모두에게 읽기와 디렉터리 진입을 열어(8진수는 구버전 Docker 도 받는다. 파일에 실행 비트가 붙지만 문서라 무해하다), 좁은 권한의 checkout(디렉터리 700, 파일 600)이어도 화면 서버 사용자(nextjs)가 읽는다. 쓰기는 열지 않는다.
# 추가 빌드 문맥 repo(배포 커밋의 저장소 root)가 있어야 한다. 없으면 Docker 가 repo 이미지를 받으려다
# 「pull access denied」 로 끝난다. 로컬에서는 web/ 에서 docker buildx build --build-context repo=.. . 로 만든다.
COPY --from=repo --chmod=755 docs /app/dev-docs/docs
COPY --from=repo --chmod=755 backend/docs /app/dev-docs/backend/docs
COPY --from=repo --chmod=755 web/docs /app/dev-docs/web/docs
COPY --from=repo --chmod=755 hermes/docs /app/dev-docs/hermes/docs
ENV DEV_DOCS_ROOT=/app/dev-docs
# 화면 위에 보일 배포 커밋이다. 40자리 소문자 16진수가 아니면 화면이 「커밋 정보 없음」 을 보인다.
ARG SOURCE_COMMIT
ENV SOURCE_COMMIT=${SOURCE_COMMIT}

USER nextjs:nodejs

EXPOSE 3000

CMD ["node", "server.js"]
