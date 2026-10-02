/**
 * Hermes 가 공개 문서에서 쓰는 provider slug 와 화면에 그릴 이름이다.
 * 실행 기록 화면에는 Hermes 목록이 없어 이 표로 바꾼다. 새 provider 가 생기면 한 줄을 더한다.
 */
const PROVIDER_LABELS: Record<string, string> = {
  "openai-codex": "ChatGPT 구독",
  openai: "OpenAI",
  anthropic: "Anthropic",
  openrouter: "OpenRouter",
  nvidia: "NVIDIA",
  google: "Google",
  gemini: "Google",
};

/**
 * 표에 있는 provider id 만 화면에 그릴 이름으로 바꾼다. 모르는 id 와 빈 값은 null 이다.
 * 관리자의 숨김 편집처럼 모르는 id 를 그대로 보여야 하는 자리가 쓴다.
 */
export function knownProviderLabel(
  provider: string | null | undefined,
): string | null {
  const id = provider?.trim();
  if (!id) return null;
  return Object.hasOwn(PROVIDER_LABELS, id) ? PROVIDER_LABELS[id] : null;
}

/** provider id 를 화면에 그릴 이름으로 바꾼다. 모르는 id 는 「다른 제공사」 다. 비어 있으면 null 이다. */
export function providerLabel(
  provider: string | null | undefined,
): string | null {
  if (!provider?.trim()) return null;
  return knownProviderLabel(provider) ?? "다른 제공사";
}
