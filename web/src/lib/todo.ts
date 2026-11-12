import { splitAnswer, type Ask } from "./ask.ts";
import { foldVersions, isLatestView } from "./message-versions.ts";

/** 「할 일」 탭이 묻는 카드를 고르는 데 쓰는 메시지 모양이다. 저장 전 줄은 번호가 문자열이다. */
type TodoTurn = {
  id: number | string;
  role: "USER" | "ASSISTANT" | "SYSTEM";
  content: string;
  replacesMessageId?: number | null;
};

/**
 * 지금 누를 수 있는 묻는 카드다. 없으면 null 이다.
 *
 * <p>답 안의 카드와 같은 조건이다. 마지막으로 보이는 줄이 에이전트 답이고, 최신 버전을 보고 있고, 돌고 있는 turn 이
 * 없어야 한다. 저장 전 줄(문자열 번호)이 있으면 보내는 중이거나 막 끝난 답이라 없다고 본다. 한 답에 카드가 여럿이면
 * 마지막 것이다. 동작은 `docs/features/user-browser/design.md` 의 「묻는 카드」 가 갖는다.
 */
export function activeAsk(
  turns: readonly TodoTurn[],
  selectedVersions: Record<number, number>,
  sending: boolean,
): Ask | null {
  if (sending || turns.some((turn) => typeof turn.id === "string")) return null;
  const persisted = turns.map((turn) => ({
    ...turn,
    id: turn.id as number,
    replacesMessageId: turn.replacesMessageId ?? null,
  }));
  const folded = foldVersions(persisted, selectedVersions);
  if (!isLatestView(folded)) return null;
  const answer = folded.at(-1)?.answers.at(-1)?.message;
  if (!answer || answer.role !== "ASSISTANT") return null;
  const last = splitAnswer(answer.content).findLast(
    (segment) => segment.kind === "ask",
  );
  return last?.kind === "ask" ? last.ask : null;
}

/** 입력창 위 한 줄의 글이다. 카드가 없고 끝난 저장 단추만 있으면 건수를 쓰지 않는다. */
export function todoLabel(count: number): string {
  return count > 0 ? `할 일 ${count}건` : "할 일";
}
