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

/** provider id 를 화면에 그릴 이름으로 바꾼다. 모르는 id 는 「다른 제공사」 다. 비어 있으면 null 이다. */
export function providerLabel(
  provider: string | null | undefined,
): string | null {
  const id = provider?.trim();
  if (!id) return null;
  return Object.hasOwn(PROVIDER_LABELS, id)
    ? PROVIDER_LABELS[id]
    : "다른 제공사";
}
