import { createHash } from "node:crypto";

/** 바뀌는 내용을 견줄 글의 네 칸. 사진 디렉터리는 글의 내용이 아니라 견주지 않는다. */
export type DraftContent = {
  title: string;
  category: string;
  tags: string[];
  body: string;
};

/** 본문 비교에서 앞뒤로 남기는 같은 줄 수. */
const CONTEXT_LINES = 1;
/** 줄 비교 표의 칸 수 상한. 넘으면 본문 전체를 바뀐 것으로 적는다. */
const DIFF_CELLS_MAX = 4_000_000;

const lines = (body: string) => body.replace(/\r\n/g, "\n").split("\n");

/**
 * 글 네 칸의 지문. `read_draft` 가 돌려주고 덮어쓰기 작업이 불러온 글로 다시 계산해 대조한다.
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

type Edit = { kind: "same" | "remove" | "add"; line: string };

/** 두 줄 목록의 최장 공통 부분열로 편집 목록을 만든다. 표가 너무 크면 모두 지우고 모두 더한다. */
function lineEdits(before: string[], after: string[]): Edit[] {
  const n = before.length;
  const m = after.length;
  if ((n + 1) * (m + 1) > DIFF_CELLS_MAX)
    return [
      ...before.map((line) => ({ kind: "remove" as const, line })),
      ...after.map((line) => ({ kind: "add" as const, line })),
    ];
  const table = Array.from({ length: n + 1 }, () => new Uint32Array(m + 1));
  for (let i = n - 1; i >= 0; i--)
    for (let j = m - 1; j >= 0; j--)
      table[i]![j] =
        before[i] === after[j]
          ? table[i + 1]![j + 1]! + 1
          : Math.max(table[i + 1]![j]!, table[i]![j + 1]!);
  const edits: Edit[] = [];
  let i = 0;
  let j = 0;
  while (i < n || j < m) {
    if (i < n && j < m && before[i] === after[j]) {
      edits.push({ kind: "same", line: before[i]! });
      i++;
      j++;
    } else if (i < n && (j === m || table[i + 1]![j]! >= table[i]![j + 1]!)) {
      // 같은 길이면 지우는 줄을 먼저 적어 `-` 줄 뒤에 `+` 줄이 온다.
      edits.push({ kind: "remove", line: before[i]! });
      i++;
    } else {
      edits.push({ kind: "add", line: after[j]! });
      j++;
    }
  }
  return edits;
}

/** 바뀐 줄과 그 앞뒤 한 줄만 남긴다. 건너뛴 자리는 `…` 한 줄이다. */
function bodyChanges(before: string, after: string) {
  const edits = lineEdits(lines(before), lines(after));
  const keep = edits.map(() => false);
  edits.forEach((edit, index) => {
    if (edit.kind === "same") return;
    for (
      let k = Math.max(0, index - CONTEXT_LINES);
      k <= Math.min(edits.length - 1, index + CONTEXT_LINES);
      k++
    )
      keep[k] = true;
  });
  const out: string[] = [];
  let skipped = false;
  edits.forEach((edit, index) => {
    if (!keep[index]) {
      skipped = true;
      return;
    }
    if (skipped && out.length) out.push("…");
    skipped = false;
    const mark = edit.kind === "same" ? " " : edit.kind === "add" ? "+" : "-";
    out.push(`${mark} ${edit.line}`);
  });
  return out;
}

/**
 * 원래 글에서 새 글로 바뀌는 내용을 사람이 읽는 글로 만든다. 같으면 빈 문자열이다.
 * 승인 카드에 이 글이 그대로 보이고, 덮어쓰기 작업은 불러온 글로 다시 만들어 글자까지 같은지 본다.
 */
export function draftChanges(before: DraftContent, after: DraftContent) {
  const out: string[] = [];
  if (before.title !== after.title) out.push(`제목: ${before.title} → ${after.title}`);
  if (before.category !== after.category)
    out.push(`카테고리: ${before.category} → ${after.category}`);
  const removedTags = before.tags.filter((tag) => !after.tags.includes(tag));
  const addedTags = after.tags.filter((tag) => !before.tags.includes(tag));
  if (removedTags.length) out.push(`뺀 태그: ${removedTags.join(", ")}`);
  if (addedTags.length) out.push(`더한 태그: ${addedTags.join(", ")}`);
  const body = bodyChanges(before.body, after.body);
  if (body.length) out.push("본문:", ...body);
  return out.join("\n");
}
