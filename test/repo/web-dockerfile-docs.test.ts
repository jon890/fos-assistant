import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

/**
 * 웹 이미지는 추가 빌드 문맥 repo 에서 공개 문서 폴더만 복사한다(web/docs/adr/ADR-20261117-dev-docs-image.md).
 * CI 는 웹 이미지를 만들지 않으므로 복사 원본을 여기서 지킨다. 다른 폴더가 끼면 운영 정보가 화면에 나올 수 있다.
 */
const DOCKERFILE = readFileSync(join(import.meta.dirname, "../../web/Dockerfile"), "utf8");

test("web Dockerfile 은 repo 문맥에서 네 docs 폴더만 같은 상대 경로로 복사한다", () => {
  const copies = [...DOCKERFILE.matchAll(/^COPY\s+--from=repo\s+(\S+)\s+(\S+)\s*$/gm)].map((m) => [m[1], m[2]]);
  assert.deepEqual(copies, [
    ["docs", "/app/dev-docs/docs"],
    ["backend/docs", "/app/dev-docs/backend/docs"],
    ["web/docs", "/app/dev-docs/web/docs"],
    ["hermes/docs", "/app/dev-docs/hermes/docs"],
  ]);
  assert.equal([...DOCKERFILE.matchAll(/--from=repo/g)].length, 4);
});

test("web Dockerfile 은 문서 위치와 배포 커밋을 실행 환경에 둔다", () => {
  assert.match(DOCKERFILE, /^ENV DEV_DOCS_ROOT=\/app\/dev-docs$/m);
  assert.match(DOCKERFILE, /^ARG SOURCE_COMMIT$/m);
  assert.match(DOCKERFILE, /^ENV SOURCE_COMMIT=\$\{SOURCE_COMMIT\}$/m);
});
