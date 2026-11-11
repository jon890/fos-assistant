/**
 * 에이전트 이름표의 값이다(docs/features/agent-team/spec.md 의 REQ1, DECISION3).
 *
 * 색 이름과 아이콘 목록은 backend 의 `AgentColor` 와 `AgentIntroduction.ICONS` 와 같아야 한다. 어긋나면 서버가 저장을 거절한다.
 * `test/unit/agent-badge.test.ts` 가 두 쪽의 글자를 대조한다.
 */

export const AGENT_COLORS = [
  "VIOLET",
  "BLUE",
  "TEAL",
  "GREEN",
  "AMBER",
  "ORANGE",
  "ROSE",
  "SLATE",
] as const;

export type AgentColor = (typeof AGENT_COLORS)[number];

/** 고를 수 있는 아이콘이다. 순서도 backend 목록과 같다. */
export const AGENT_ICONS = [
  "🤖",
  "🛍️",
  "🚆",
  "✉️",
  "📅",
  "💰",
  "✍️",
  "📚",
  "🏠",
  "🍳",
  "💪",
  "✈️",
  "🎵",
  "📷",
  "💻",
  "⭐",
  "🌱",
  "🧾",
  "🗺️",
  "🧸",
  "🩺",
  "🎮",
  "📝",
  "🔎",
] as const;

/** 소개의 상한이다. backend 의 `AgentIntroduction.MAX_DESCRIPTION_CHARS` 와 같다. */
export const AGENT_DESCRIPTION_MAX_CHARS = 60;

/** 이름의 상한이다. backend 의 `AgentIntroduction.MAX_NAME_CHARS` 와 같다. */
export const AGENT_NAME_MAX_CHARS = 100;

/**
 * 색마다 이름표의 바탕과 글자 class 다. 토큰은 `globals.css` 에 있다.
 * Tailwind 는 소스에 적힌 class 만 만들므로 글자를 조립하지 않고 그대로 적는다.
 */
const COLOR_CLASSES: Record<AgentColor, string> = {
  VIOLET: "bg-agent-violet-soft text-agent-violet",
  BLUE: "bg-agent-blue-soft text-agent-blue",
  TEAL: "bg-agent-teal-soft text-agent-teal",
  GREEN: "bg-agent-green-soft text-agent-green",
  AMBER: "bg-agent-amber-soft text-agent-amber",
  ORANGE: "bg-agent-orange-soft text-agent-orange",
  ROSE: "bg-agent-rose-soft text-agent-rose",
  SLATE: "bg-agent-slate-soft text-agent-slate",
};

/** 색 이름으로 읽을 수 있는 값인지 본다. 모르는 값은 색을 고르지 않은 것으로 본다. */
export function asAgentColor(value: unknown): AgentColor | null {
  return typeof value === "string" &&
    (AGENT_COLORS as readonly string[]).includes(value)
    ? (value as AgentColor)
    : null;
}

/** 색을 고르지 않은 에이전트의 색이다. 같은 `code` 는 언제나 같은 색이다. 코드를 모르면 회색이다. */
export function autoColor(code: string | null): AgentColor {
  if (!code) return "SLATE";
  let hash = 0;
  for (const char of code) {
    hash = (hash * 31 + char.codePointAt(0)!) >>> 0;
  }
  return AGENT_COLORS[hash % AGENT_COLORS.length]!;
}

/** 이름표에 그릴 색 class 다. 고른 색이 없으면 `code` 로 정한 자동 색을 쓴다. */
export function badgeColorClass(color: unknown, code: string | null): string {
  return COLOR_CLASSES[asAgentColor(color) ?? autoColor(code)];
}

/** 아이콘이 없을 때 그릴 이름 첫 글자다. 이름이 없으면 「?」 다. */
export function initialOf(name: string | null): string {
  // 이모지처럼 두 코드 단위로 된 글자를 반으로 자르지 않게 코드 포인트로 꺼낸다.
  const first = name ? Array.from(name.trim())[0] : undefined;
  return first ?? "?";
}

/** 할 수 있는 일을 한 줄로 이어 붙인다. 붙은 서비스가 먼저 온다. 없으면 빈 글이다. */
export function capabilitiesLine(
  capabilities: { connectors?: string[]; skills?: string[] } | undefined,
): string {
  return [
    ...(capabilities?.connectors ?? []),
    ...(capabilities?.skills ?? []),
  ].join(" · ");
}
