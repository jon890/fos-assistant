import { cardWouldMask } from "./card-mask.ts";
import { draftChanges, type DraftContent } from "./changes.ts";
import { EXISTING_LINE } from "./document.ts";
import { draftShape, parseBody, validateDraft } from "./draft.ts";

/** 덮어쓸 글의 네 칸. 모양과 설명은 새 글의 같은 칸과 같다. */
export const overwriteContentShape = {
  title: draftShape.title,
  category: draftShape.category,
  tags: draftShape.tags,
  body: draftShape.body,
};

export type OverwriteContent = DraftContent;

/** 바뀌는 내용 글의 최대 글자 수. 넘으면 승인 카드에 다 보이지 않으므로 나눠 고치게 한다. */
export const CHANGES_MAX = 100_000;

const trimLineEnd = (raw: string) => (raw.endsWith("\r") ? raw.slice(0, -1) : raw);

/** 승인 카드가 가리는 자리가 있을 때의 문장. 덮어쓰기는 매번 승인받아 가려진 카드는 승인할 수 없다. */
export const maskedProblem = (where: string) =>
  `${where}에 승인 카드가 가리는 긴 영문과 숫자 덩어리(긴 링크 조각 등)가 있어 덮어쓸 수 없습니다. 새 임시저장으로 남기거나 네이버에서 직접 고쳐 주세요.`;

/**
 * 덮어쓸 글을 검사해 어긋난 자리를 문장으로 돌려준다.
 * 길이와 태그는 새 글과 같은 규칙이고, 구성요소 줄은 기존 구성요소 줄만 받는다.
 * `base` 를 주면 기존 구성요소 줄이 원래 글에 있는지와 바뀐 것이 있는지도 본다.
 */
export function validateOverwrite(
  input: OverwriteContent,
  base?: OverwriteContent,
  changes = base ? draftChanges(base, input) : "",
): string[] {
  const lines = input.body.split("\n").map(trimLineEnd);
  const blocks = parseBody(input.body);
  // 구성요소 줄을 비워 새 글 검사에 넘긴다. 사진 디렉터리와 사진 파일 이름 문장은 나오지 않는다.
  const blanked = lines
    .map((line, index) =>
      EXISTING_LINE.test(line) || blocks[index]!.type !== "text" ? "" : line,
    )
    .join("\n");
  const problems = validateDraft({ ...input, body: blanked });

  // 편집기는 앞뒤 공백을 떼고 넣으므로 저장 전 확인이 어긋난다.
  const padded = (value: string) => value !== value.trim();
  if (padded(input.category) || input.tags.some(padded))
    problems.push("카테고리와 태그 앞뒤에 공백을 두지 않습니다.");

  if (cardWouldMask(input.title)) problems.push(maskedProblem("제목"));
  if (cardWouldMask(input.category)) problems.push(maskedProblem("카테고리"));
  if (input.tags.some(cardWouldMask)) problems.push(maskedProblem("태그"));
  const maskedLine = lines.findIndex(cardWouldMask);
  if (maskedLine >= 0) problems.push(maskedProblem(`본문 ${maskedLine + 1}번째 줄`));

  if (blocks.some((block) => block.type !== "text"))
    problems.push(
      "덮어쓰기에는 새 사진, 스티커, 지도를 넣지 않습니다. 새 글로 저장하거나 네이버에서 직접 넣어 주세요.",
    );

  const existing = lines.filter((line) => EXISTING_LINE.test(line));
  const seen = new Set<string>();
  const repeated = new Set<string>();
  for (const line of existing) {
    if (seen.has(line)) repeated.add(line);
    seen.add(line);
  }
  for (const line of repeated) problems.push(`${line} 은 한 번만 둡니다.`);

  if (base) {
    const baseLines = new Set(base.body.split("\n").map(trimLineEnd));
    for (const line of seen)
      if (!baseLines.has(line)) problems.push(`${line} 은 원래 글에 없습니다.`);
    if (changes === "") problems.push("바뀐 것이 없습니다.");
  }
  return problems;
}
