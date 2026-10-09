import { createHash } from "node:crypto";

/** 지문을 계산하는 글의 네 칸. 사진 디렉터리는 글의 내용이 아니라 넣지 않는다. */
export type DraftContent = {
  title: string;
  category: string;
  tags: string[];
  body: string;
};

const lines = (body: string) => body.replace(/\r\n/g, "\n").split("\n");

/**
 * 글 네 칸의 지문. `read_draft` 가 돌려준다. 두 번 읽은 글이 같은지 이 값으로 견준다.
 * 줄 끝 `\r` 은 지문에 넣지 않는다.
 */
export function draftRevision(content: DraftContent) {
  const canonical = JSON.stringify([
    content.title,
    content.category,
    content.tags,
    lines(content.body),
  ]);
  return createHash("sha256").update(canonical).digest("hex").slice(0, 16);
}
