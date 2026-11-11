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
