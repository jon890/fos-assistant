const REASONS = [
  "page_loading",
  "title_missing",
  "title_unready",
  "title_ambiguous",
  "frame_only",
  "login_required",
  "draft_notice",
  "popup",
  "ready",
] as const;
export type OpenReason = (typeof REASONS)[number];
export type OpenSample = {
  reason: OpenReason;
  page: "editor" | "login" | "other";
  host: "blog" | "login" | "other";
  document_state: "loading" | "interactive" | "complete" | "other";
  page_focused: boolean;
  page_visible: boolean;
  candidates: number;
  prepared: number;
  sized: number;
  editable: number;
  visible: number;
  frames: number;
  frame_candidates: number;
  editors: number;
  input_frames: number;
  buffer_ready: boolean;
};
export type OpenDiagnostics = { attempts: number; last: OpenSample };
const count = (value: unknown, max = 100) =>
  typeof value === "number" && Number.isFinite(value)
    ? Math.max(0, Math.min(max, Math.floor(value)))
    : 0;
const choice = <T extends string>(
  value: unknown,
  choices: readonly T[],
  fallback: T,
): T => (choices.includes(value as T) ? (value as T) : fallback);

/** 브라우저 원문과 알 수 없는 키를 버리고 허용한 분류와 개수만 복사한다. */
export function filterOpenDiagnostics(
  value: unknown,
): OpenDiagnostics | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value))
    return undefined;
  const raw = value as Record<string, unknown>;
  if (!raw.last || typeof raw.last !== "object" || Array.isArray(raw.last))
    return undefined;
  const s = raw.last as Record<string, unknown>;
  return {
    attempts: count(raw.attempts, 40),
    last: {
      reason: choice(s.reason, REASONS, "page_loading"),
      page: choice(s.page, ["editor", "login", "other"], "other"),
      host: choice(s.host, ["blog", "login", "other"], "other"),
      document_state: choice(
        s.document_state,
        ["loading", "interactive", "complete", "other"],
        "other",
      ),
      page_focused: s.page_focused === true,
      page_visible: s.page_visible === true,
      candidates: count(s.candidates),
      prepared: count(s.prepared),
      sized: count(s.sized),
      editable: count(s.editable),
      visible: count(s.visible),
      frames: count(s.frames),
      frame_candidates: count(s.frame_candidates),
      editors: count(s.editors),
      input_frames: count(s.input_frames),
      buffer_ready: s.buffer_ready === true,
    },
  };
}
