import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const ROOT = join(import.meta.dirname, "../..");

/** 저장소 루트 기준 파일에서 정규식의 첫 묶음을 수로 읽는다. 찾지 못하면 실패한다. */
function readLimit(relativePath: string, pattern: RegExp): number {
  const source = readFileSync(join(ROOT, relativePath), "utf8");
  const match = source.match(pattern);
  assert.ok(match, `${relativePath} 에서 ${pattern} 를 찾지 못했다`);
  return Number(match[1]);
}

test("한 번에 올리는 사진 장수 상한은 화면 상수와 서버 기본값, 서버 설정이 같다", () => {
  const web = readLimit(
    "web/src/components/chat/composer-attachment-utils.ts",
    /export const MAX_ATTACHMENTS = (\d+);/,
  );
  const serverDefault = readLimit(
    "backend/src/main/java/com/bifos/assistant/chat/infra/AttachmentProperties.java",
    /DEFAULT_MAX_FILES = (\d+);/,
  );
  const serverSetting = readLimit(
    "backend/src/main/resources/application.yml",
    /^\s*max-files: (\d+)\s*$/m,
  );

  assert.equal(
    serverDefault,
    web,
    `AttachmentProperties.DEFAULT_MAX_FILES(${serverDefault}) 가 화면의 MAX_ATTACHMENTS(${web}) 와 다르다`,
  );
  assert.equal(
    serverSetting,
    web,
    `application.yml 의 max-files(${serverSetting}) 가 화면의 MAX_ATTACHMENTS(${web}) 와 다르다`,
  );
});
