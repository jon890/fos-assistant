import {
  BookOpen,
  Bot,
  FolderOpen,
  Globe,
  Plug,
  ShieldQuestion,
  type LucideIcon,
} from "lucide-react";

export type SettingsLink = { href: string; label: string; icon: LucideIcon };
export type SettingsGroup = { label: string; links: readonly SettingsLink[] };

/**
 * 「설정」 화면의 묶음과 줄이다(docs/features/app-shell/spec.md 의 REQ4, DECISION9).
 *
 * <p>설정 틀과 테스트가 이 배열 하나를 읽는다. 「예약 작업」 과 「기억」 은 사이드바에 두므로 넣지 않는다.
 */
export const SETTINGS_GROUPS: readonly SettingsGroup[] = [
  { label: "비서", links: [{ href: "/agents", label: "에이전트", icon: Bot }] },
  {
    label: "연결",
    links: [
      { href: "/connections", label: "외부 서비스 연결", icon: Plug },
      { href: "/browser", label: "내 브라우저", icon: Globe },
    ],
  },
  {
    label: "내 데이터",
    links: [{ href: "/files", label: "파일 공간", icon: FolderOpen }],
  },
  {
    label: "도움말",
    links: [
      { href: "/dev-docs", label: "개발 문서", icon: BookOpen },
      { href: "/privacy", label: "데이터 보관 안내", icon: ShieldQuestion },
    ],
  },
];

export const SETTINGS_PATH = "/settings";

function under(pathname: string, href: string): boolean {
  return pathname === href || pathname.startsWith(`${href}/`);
}

/**
 * 설정 틀에서 보일 경로인지 정한다. `/privacy` 는 로그인했을 때만 설정 틀이고 아니면 공개 화면 틀이다.
 */
export function isSettingsArea(pathname: string, hasSession: boolean): boolean {
  if (pathname === "/privacy") return hasSession;
  if (under(pathname, SETTINGS_PATH)) return true;
  return SETTINGS_GROUPS.some((group) =>
    group.links.some(
      (link) => link.href !== "/privacy" && under(pathname, link.href),
    ),
  );
}

/**
 * 경로에서 골라진 줄의 주소를 정한다. `/settings` 는 처음 보이는 「에이전트」 다.
 */
export function selectedSettingsHref(pathname: string): string | null {
  if (under(pathname, SETTINGS_PATH)) return "/agents";
  for (const group of SETTINGS_GROUPS)
    for (const link of group.links)
      if (under(pathname, link.href)) return link.href;
  return null;
}
