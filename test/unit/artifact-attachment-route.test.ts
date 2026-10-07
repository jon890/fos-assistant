/**
 * 결과물 파일과 첨부 사진의 web 라우트가 같은 대화 주소 아래의 형제인지 본다.
 *
 * 네이버 블로그 커넥터의 미리보기는 `<폴더>/index.html` 에서 `../../attachments/<첨부 번호>` 로
 * 같은 대화의 첨부를 부른다. 그 상대 경로가 이 모양에 기댄다. 계약은 docs/backend/artifact.md 의
 * 「같은 대화의 첨부 사진을 부를 때」 가 갖는다. 두 라우트를 옮기면 미리보기도 함께 고친다.
 */
import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const CONVERSATION = join(
  import.meta.dirname,
  "../../web/src/app/api/chat/conversations/[conversationId]",
);

test("결과물 파일 라우트와 첨부 라우트가 같은 대화 주소 아래의 형제로 있다", () => {
  const files = join(CONVERSATION, "files/[...path]/route.ts");
  const attachments = join(CONVERSATION, "attachments/[attachmentId]/route.ts");

  assert.ok(existsSync(files), "결과물 파일 라우트가 없다: files/[...path]/route.ts");
  assert.ok(existsSync(attachments), "첨부 라우트가 없다: attachments/[attachmentId]/route.ts");
});
