import type { ChatEvent } from "../../lib/chat-event.ts";

/** 실행 동안만 두는 답 조각과 중간 안내 중복 상태다. 이력이나 저장 API에 넘기지 않는다. */
export type TurnStreamText = { text: string; interimKeys: Set<string> };

export function interimKey(event: ChatEvent): string {
  // Python의 \s는 Unicode White_Space 외에 이 네 구분 문자도 공백으로 본다.
  // eslint-disable-next-line no-control-regex
  return event.interimId ? `id:${event.interimId}` : `text:${(event.text ?? "").replace(/[\p{White_Space}\u001c-\u001f]+/gu, " ").trim()}`;
}

/** 확인된 직전 구간만 빼고, 출처를 모르는 본문은 보존한다. */
export function applyStreamText(state: TurnStreamText, event: ChatEvent): boolean {
  if (event.type === "reset") {
    state.text = "";
    state.interimKeys.clear();
  } else if (event.type === "delta" && event.text) {
    state.text += event.text;
  } else if (event.type === "interim") {
    const key = interimKey(event);
    if (state.interimKeys.has(key)) return false;
    state.interimKeys.add(key);
    const length = event.streamedPrefixLength;
    if (event.alreadyStreamed === true && typeof length === "number" && Number.isInteger(length) &&
        length > 0 && length <= state.text.length) {
      state.text = state.text.slice(0, -length);
      return true;
    }
  }
  return false;
}
