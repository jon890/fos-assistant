/** 사람이 해야 하는 단계다. Control Plane 의 `HandoffNeed` 와 같은 목록이다. */
export type HandoffNeed = "LOGIN" | "CAPTCHA" | "DEVICE_CHECK";

export const HANDOFF_NEEDS: readonly HandoffNeed[] = [
  "LOGIN",
  "CAPTCHA",
  "DEVICE_CHECK",
];

/** 대화의 화면 넘기기 카드 하나다. 카드는 이 응답으로만 그린다. 모델이 쓴 글은 들어 있지 않다. */
export type Handoff = {
  handoffId: string;
  connectorId: string;
  connectorTitle: string;
  need: HandoffNeed;
  /** 플러그인이 남긴 「내 브라우저」 탭의 id 다. */
  tab: string;
  createdAt: string;
};

/**
 * 단계마다 카드 제목과 「다 했어요」 가 보내는 글이다. 제목은 Control Plane 의 `ConnectorHandoffs.TITLES` 와 같다.
 * 어휘는 `docs/adr/ADR-20261111-connector-handoff.md` 의 「결정」 이 갖는다.
 */
export const HANDOFF_TEXT: Record<
  HandoffNeed,
  { title: string; done: string }
> = {
  LOGIN: {
    title: "「내 브라우저」에서 로그인해 주세요",
    done: "「내 브라우저」에서 로그인을 마쳤어요. 멈춘 단계부터 이어서 해 주세요.",
  },
  CAPTCHA: {
    title: "「내 브라우저」에서 보안 확인을 풀어 주세요",
    done: "「내 브라우저」에서 보안 확인을 마쳤어요. 멈춘 단계부터 이어서 해 주세요.",
  },
  DEVICE_CHECK: {
    title: "「내 브라우저」에서 기기 인증을 해 주세요",
    done: "「내 브라우저」에서 기기 인증을 마쳤어요. 멈춘 단계부터 이어서 해 주세요.",
  },
};

/** 화면 입력 `tab` 의 `id` 와 같은 모양이다. */
export const HANDOFF_TAB_PATTERN = /^[A-Za-z0-9]{1,128}$/;

/** 「내 브라우저 열기」 가 갈 주소다. 그 탭을 고른 채 화면을 연다. */
export function handoffBrowserHref(handoff: Handoff): string {
  return `/browser?tab=${encodeURIComponent(handoff.tab)}`;
}

/** 응답 한 줄을 계약대로 읽는다. 모양이 틀린 줄은 버린다. */
export function safeHandoff(value: unknown): Handoff | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const row = value as Record<string, unknown>;
  const text = (key: string) =>
    typeof row[key] === "string" ? (row[key] as string) : null;
  const handoffId = text("handoffId");
  const connectorId = text("connectorId");
  const connectorTitle = text("connectorTitle");
  const need = text("need");
  const tab = text("tab");
  const createdAt = text("createdAt");
  if (
    !handoffId ||
    !connectorId ||
    !connectorTitle ||
    !createdAt ||
    !HANDOFF_NEEDS.includes(need as HandoffNeed) ||
    !tab ||
    !HANDOFF_TAB_PATTERN.test(tab)
  )
    return null;
  return {
    handoffId,
    connectorId,
    connectorTitle,
    need: need as HandoffNeed,
    tab,
    createdAt,
  };
}

/**
 * 패널이 넘긴 탭의 주소를 보는 상태다. `waiting` 은 첫 탭 목록을 기다리고, `watching` 은 기준 주소와 비교하며, `off` 는
 * 넘긴 탭이 처음부터 없어 묻지 않는다. 동작은 `docs/features/user-browser/design.md` 의 「주소 변화 판정」 이 갖는다.
 */
export type HandoffWatch =
  { phase: "waiting" } | { phase: "watching"; base: string } | { phase: "off" };

type TabUrl = { id: string; url: string };

/** `#` 뒤 조각만 다른 주소는 같은 화면으로 본다. */
function withoutHash(url: string): string {
  const index = url.indexOf("#");
  return index === -1 ? url : url.slice(0, index);
}

/** SSE 의 탭 목록 하나를 받아 다음 상태와, 끝났는지 물어야 하는지를 돌려준다. */
export function watchHandoffTab(
  state: HandoffWatch,
  tab: string,
  tabs: readonly TabUrl[],
): { state: HandoffWatch; changed: boolean } {
  const target = tabs.find((candidate) => candidate.id === tab);
  if (state.phase === "off") return { state, changed: false };
  if (state.phase === "waiting") {
    return target
      ? {
          state: { phase: "watching", base: withoutHash(target.url) },
          changed: false,
        }
      : { state: { phase: "off" }, changed: false };
  }
  return {
    state,
    changed: !target || withoutHash(target.url) !== state.base,
  };
}

/** 「아직이에요」 다. 지금 주소를 새 기준으로 삼고, 넘긴 탭이 이미 없으면 더 묻지 않는다. */
export function rebaseHandoffWatch(
  tab: string,
  tabs: readonly TabUrl[],
): HandoffWatch {
  const target = tabs.find((candidate) => candidate.id === tab);
  return target
    ? { phase: "watching", base: withoutHash(target.url) }
    : { phase: "off" };
}

/** 처음 읽은 뒤에 생긴 넘기기다. 받은 순서(최신 먼저)를 그대로 둔다. */
export function newHandoffs(
  seen: ReadonlySet<string>,
  handoffs: readonly Handoff[],
): Handoff[] {
  return handoffs.filter((handoff) => !seen.has(handoff.handoffId));
}
