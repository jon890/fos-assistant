FROM oven/bun:1.3.14-alpine@sha256:5acc90a93e91ff07bf72aa90a7c9f0fa189765aec90b47bdbf2152d2196383c0 AS build
ARG SOURCE_COMMIT
WORKDIR /src
# 소스보다 의존성 층을 먼저 두어 lockfile이 같으면 설치를 재사용한다.
COPY gmail/package.json gmail/bun.lock gmail/
COPY naver-blog/package.json naver-blog/bun.lock naver-blog/
COPY tossinvest/package.json tossinvest/bun.lock tossinvest/
RUN for connector in */; do bun install --cwd "$connector" --frozen-lockfile --network-concurrency=1; done
COPY . .
RUN bun run package.ts build /bundle "$SOURCE_COMMIT"

FROM scratch AS bundle
COPY --from=build /bundle /bundle
# 실행용 이미지가 아니다. docker create/cp만 쓰고 start/run하지 않는다.
CMD ["/none"]
