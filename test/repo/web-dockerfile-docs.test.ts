import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

/**
 * 웹 이미지는 추가 빌드 문맥 repo 에서 공개 문서 폴더만 복사한다(web/docs/adr/ADR-20261117-dev-docs-image.md).
 * CI 는 웹 이미지를 만들지 않으므로 복사 원본을 여기서 지킨다. 다른 폴더가 끼면 운영 정보가 화면에 나올 수 있다.
 */
const DOCKERFILE = readFileSync(join(import.meta.dirname, "../../web/Dockerfile"), "utf8");

test("web Dockerfile 은 repo 문맥에서 네 docs 폴더만 같은 상대 경로로 복사한다", () => {
  const copies = [...DOCKERFILE.matchAll(/^COPY\s+--from=repo\s+--chmod=a\+rX\s+(\S+)\s+(\S+)\s*$/gm)].map((m) => [m[1], m[2]]);
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

/**
 * 운영 배포의 소스 checkout 은 디렉터리 700, 파일 600 이라 COPY 가 그 권한을 가져오면 nextjs 가 읽지 못한다.
 * Dockerfile 이 쓰는 COPY 옵션을 그대로 뽑아 좁은 권한의 문맥으로 작은 이미지를 만들어 nextjs 로 읽어 본다.
 */
const dockerReady = spawnSync("docker", ["buildx", "version"], { stdio: "ignore" }).status === 0;

test("좁은 권한의 repo 문맥을 복사해도 nextjs 가 문서를 읽는다", { skip: dockerReady ? false : "docker buildx 가 없다" }, () => {
  const option = DOCKERFILE.match(/^COPY\s+--from=repo\s+(--chmod=\S+)\s+docs\s/m)?.[1];
  assert.ok(option, "docs COPY 에 --chmod 옵션이 있어야 한다");
  const root = mkdtempSync(join(tmpdir(), "dev-docs-perm-"));
  try {
    mkdirSync(join(root, "repo/docs/sub"), { recursive: true });
    mkdirSync(join(root, "ctx"));
    writeFileSync(join(root, "repo/docs/sub/a.md"), "a");
    writeFileSync(join(root, "repo/docs/p.md"), "p");
    for (const dir of ["repo/docs", "repo/docs/sub"]) chmodSync(join(root, dir), 0o700);
    for (const file of ["repo/docs/p.md", "repo/docs/sub/a.md"]) chmodSync(join(root, file), 0o600);
    writeFileSync(
      join(root, "Dockerfile"),
      [
        "FROM alpine:3.20",
        "RUN addgroup -S nodejs && adduser -S nextjs -G nodejs",
        `COPY --from=repo ${option} docs /app/docs`,
        "USER nextjs:nodejs",
        "RUN cat /app/docs/p.md /app/docs/sub/a.md",
      ].join("\n"),
    );
    const result = spawnSync(
      "docker",
      ["buildx", "build", "--build-context", `repo=${join(root, "repo")}`, "-f", join(root, "Dockerfile"), join(root, "ctx")],
      { encoding: "utf8" },
    );
    assert.equal(result.status, 0, result.stderr);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
