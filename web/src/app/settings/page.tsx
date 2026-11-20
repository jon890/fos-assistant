import AgentsPage from "@/app/agents/page";

/**
 * 「설정」 화면이다(docs/features/app-shell/spec.md 의 REQ4).
 *
 * <p>묶음 목록은 설정 틀이 그리고, 본문은 처음 골라진 「에이전트」 다. 휴대폰에서는 틀이 본문을 숨기고 목록만 보인다.
 */
export default function SettingsPage(props: {
  searchParams: Promise<{ new?: string | string[] }>;
}) {
  return <AgentsPage {...props} />;
}
