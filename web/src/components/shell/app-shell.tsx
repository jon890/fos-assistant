"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type Dispatch,
  type SetStateAction,
} from "react";
import { PanelLeft } from "lucide-react";
import { AdminShell } from "./admin-shell";
import { LAST_CONVERSATION_KEY } from "./back-to-chat";
import {
  ConversationsProvider,
  useConversations,
} from "./conversations-provider";
import { shellRoleState } from "./role-state";
import { ScreenTransition } from "./screen-transition";
import { isSettingsArea } from "./settings-area";
import { SettingsShell } from "./settings-shell";
import {
  AppNameContext,
  ShellAccountContext,
  useAppName,
} from "./shell-account";
import { Sidebar } from "./sidebar";
import {
  readCollapsed,
  subscribeCollapsed,
  updateCollapsed,
} from "./sidebar-collapsed";
import { NotificationsProvider } from "@/components/notification/notifications-provider";
import { MobileTopBar } from "./mobile-top-bar";
import { useShortcuts } from "./use-shortcuts";
import { cn } from "cn";
import { TooltipButton } from "@/components/ui/tooltip-button";
import { Sheet, SheetContent, SheetTitle } from "@/components/ui/sheet";
import { TooltipProvider } from "@/components/ui/tooltip";
import { useMediaQuery } from "@/components/ui/use-media-query";
import { fetchMe, type ClientMe } from "@/lib/me-client";
import { hasBrowserSession } from "@/lib/sign-out-client";
import { NEW_CONVERSATION_PATH } from "@/lib/room";

/** 좁은 화면 위 막대의 제목이다. 화면이 정하지 않았으면 `null` 이고 앱 이름을 보인다 */
const TitleContext = createContext<Dispatch<
  SetStateAction<string | null>
> | null>(null);
/** 레이아웃이 한 번 읽은 사용자 이름이다. 화면마다 다시 읽지 않고 여기서 꺼낸다. 읽지 못했으면 null 이다 */
const DisplayNameContext = createContext<string | null>(null);
/** 역할이 `ADMIN` 인지다. `useAdminView()` 만 읽는다. 관리자 영역 안인지는 그 훅이 경로로 따로 본다. */
const AdminContext = createContext(false);

function isAdminArea(pathname: string): boolean {
  return pathname === "/admin" || pathname.startsWith("/admin/");
}

/** 관리자 전용 표시를 그릴지 정한다. 역할이 `ADMIN` 이어도 관리자 영역 밖에서는 거짓이다. */
export function useAdminView(): boolean {
  const isAdmin = useContext(AdminContext);
  return isAdminArea(usePathname()) && isAdmin;
}

export function useShellDisplayName(): string | null {
  return useContext(DisplayNameContext);
}

export function useShellTitle(title: string | null): void {
  const setTitle = useContext(TitleContext);
  useEffect(() => {
    setTitle?.(title || null);
    return () => setTitle?.(null);
  }, [setTitle, title]);
}

function ShellBody({
  children,
  signedIn,
  hasSession,
}: {
  children: React.ReactNode;
  signedIn: boolean;
  hasSession: boolean;
}) {
  const pathname = usePathname();
  const inAdminArea = isAdminArea(pathname);
  const inSettingsArea = isSettingsArea(pathname, hasSession);
  const { startNew } = useConversations();
  const [drawerOpen, setDrawerOpen] = useState(false);
  // 서버에서 그린 첫 그림과 hydration 동안은 펴 둔다. 저장한 값은 그 뒤에 읽는다.
  const collapsed = useSyncExternalStore(
    subscribeCollapsed,
    readCollapsed,
    () => false,
  );
  const appName = useAppName();
  const [title, setTitle] = useState<string | null>(null);
  // 붙박이 사이드바와 서랍이 각자 검색칸을 가진다. 서랍이 닫히면 서랍 쪽 ref 는 null 이 된다.
  const pinnedSearchRef = useRef<HTMLInputElement>(null);
  const drawerSearchRef = useRef<HTMLInputElement>(null);
  /** 단축키로 서랍을 열었으면 서랍이 열릴 때 첫 링크 대신 검색칸에 초점을 준다 */
  const focusSearchOnOpen = useRef(false);
  // 첫 그림에서 폭을 모르면(null) 붙박이 사이드바를 그리고 CSS 가 좁은 폭에서 숨긴다.
  const wide = useMediaQuery("(min-width: 768px)");

  const toggleSidebar = useCallback(() => {
    if (window.matchMedia("(min-width: 768px)").matches)
      updateCollapsed(!collapsed);
    else setDrawerOpen((open) => !open);
  }, [collapsed]);
  const focusSearch = useCallback(() => {
    if (window.matchMedia("(min-width: 768px)").matches) {
      updateCollapsed(false);
      requestAnimationFrame(() => pinnedSearchRef.current?.focus());
    } else if (drawerSearchRef.current) {
      drawerSearchRef.current.focus();
    } else {
      focusSearchOnOpen.current = true;
      setDrawerOpen(true);
    }
  }, []);
  // 관리자 틀과 설정 틀에는 사이드바와 검색칸이 없어 단축키가 닿을 곳이 없다.
  useShortcuts(
    signedIn && !inAdminArea && !inSettingsArea,
    startNew,
    toggleSidebar,
    focusSearch,
  );

  // 지난 그림의 경로와 폭이다. 바뀐 것을 그리는 중에 알아채 서랍을 닫는다.
  const [seen, setSeen] = useState({ pathname, wide });
  if (seen.pathname !== pathname || seen.wide !== wide) {
    setSeen({ pathname, wide });
    // 서랍은 옮기는 동안 열어 둔다. 누른 줄의 회전 표시와 「옮기는 중」 안내가 옮기는 내내 서랍 안에 남는다.
    // 경로가 바뀌면 여기서 닫는다.
    // 서랍이 열린 채 넓은 폭이 되면 닫아 둔다. 그대로 두면 다시 좁힐 때 서랍이 저절로 열린다.
    if (seen.pathname !== pathname || (seen.wide !== wide && wide)) {
      setDrawerOpen(false);
    }
  }
  // 목적지가 지금 경로와 같으면 경로가 바뀌지 않으므로 곧바로 닫는다. 사진을 먼저 올려 주소만 바꾼 경우가
  // 있어 usePathname 대신 주소창의 경로와 견준다.
  const closeIfSamePath = useCallback((href: string) => {
    if (href === window.location.pathname) setDrawerOpen(false);
  }, []);
  const drawerShown = drawerOpen && wide !== true;

  if (!signedIn) {
    return (
      <main
        aria-busy={wide === null}
        className="mx-auto min-h-0 w-full flex-1 overflow-y-auto px-4 py-5"
      >
        {children}
      </main>
    );
  }

  if (inAdminArea)
    return <AdminShell ready={wide !== null}>{children}</AdminShell>;

  if (inSettingsArea)
    return <SettingsShell ready={wide !== null}>{children}</SettingsShell>;

  return (
    <TitleContext.Provider value={setTitle}>
      <div className="flex h-full min-h-0 min-w-0 flex-1">
        {/* 좁은 폭에서는 CSS 가 숨긴다. 서랍이 열린 동안은 같은 사이드바가 두 벌 생기지 않게 그리지 않는다. */}
        {wide === false && drawerShown ? null : (
          <aside
            aria-label="사이드바"
            className={cn(
              "hidden w-64 shrink-0 border-r border-border bg-muted",
              !collapsed && "md:block",
            )}
          >
            <Sidebar
              onNavigate={closeIfSamePath}
              searchRef={pinnedSearchRef}
              onCollapse={() => updateCollapsed(true)}
            />
          </aside>
        )}
        <Sheet open={drawerShown} onOpenChange={setDrawerOpen}>
          <SheetContent
            side="left"
            closeLabel="사이드바 닫기"
            className="gap-0 bg-muted data-[side=left]:w-72 data-[side=left]:sm:max-w-72"
            onOpenAutoFocus={(event) => {
              if (!focusSearchOnOpen.current) return;
              focusSearchOnOpen.current = false;
              event.preventDefault();
              drawerSearchRef.current?.focus();
            }}
            onEscapeKeyDown={(event) => {
              // Radix 는 document 캡처 단계에서 Esc 를 먼저 받는다. 이름 입력칸은 Esc 로 편집만 취소하므로 서랍을 닫지 않는다.
              if (
                event.target instanceof Element &&
                event.target.matches("[data-rename-input]")
              )
                event.preventDefault();
            }}
          >
            <SheetTitle className="sr-only">사이드바</SheetTitle>
            <aside aria-label="사이드바" className="h-full min-h-0">
              {/* 닫히며 사라지는 동안에는 붙박이 쪽이 안내 영역을 갖는다. 안내 영역이 한 번에 하나만 있다. */}
              <Sidebar
                onNavigate={closeIfSamePath}
                searchRef={drawerSearchRef}
                onCollapse={() => updateCollapsed(true)}
                showStatus={drawerOpen}
                inDrawer
              />
            </aside>
          </SheetContent>
        </Sheet>
        <div className="flex min-h-0 min-w-0 flex-1 flex-col">
          {collapsed ? (
            <header className="hidden h-14 shrink-0 items-center gap-3 border-b border-border px-4 md:flex">
              <TooltipButton
                label="사이드바 펴기"
                onClick={() => updateCollapsed(false)}
              >
                <PanelLeft aria-hidden="true" />
              </TooltipButton>
              <Link
                href={NEW_CONVERSATION_PATH}
                onClick={startNew}
                className="rounded-md px-2 py-1.5 text-sm hover:bg-muted"
              >
                새 대화
              </Link>
            </header>
          ) : null}
          <MobileTopBar
            title={title ?? appName}
            appName={appName}
            onOpenDrawer={() => setDrawerOpen(true)}
            onStartNew={startNew}
          />
          {/* 서버의 첫 그림에는 아직 동작이 붙지 않았다. 폭을 읽은 뒤 준비 상태를 낭독기에도 알린다. */}
          <main
            aria-busy={wide === null}
            className="mx-auto min-h-0 w-full flex-1 overflow-y-auto px-4 py-5"
          >
            <ScreenTransition>{children}</ScreenTransition>
          </main>
        </div>
      </div>
    </TitleContext.Provider>
  );
}

export function AppShell({
  role,
  displayName,
  appName,
  hasSession,
  children,
}: {
  /** 레이아웃이 읽은 역할이다. 읽지 못했으면 `null` 이다 */
  role: "ADMIN" | "MEMBER" | null;
  displayName?: string;
  /** 서버가 요청마다 환경에서 읽은 앱 이름이다 */
  appName: string;
  /** 레이아웃이 읽은 로그인 세션이 있는지다. `/privacy` 의 틀을 정한다 */
  hasSession: boolean;
  children: React.ReactNode;
}) {
  const pathname = usePathname();
  // `/privacy` 는 로그인 화면에서도 여는 공개 화면이라, 로그인했을 때만 설정 틀에서 보인다.
  const signedIn =
    pathname !== "/signin" && (pathname !== "/privacy" || hasSession);
  const inAdminArea = isAdminArea(pathname);
  /** 레이아웃이 역할을 읽지 못했을 때 브라우저에서 다시 읽은 결과다 */
  const [reread, setReread] = useState<ClientMe | null>(null);
  const [retried, setRetried] = useState(false);

  /** 「다시 읽기」 가 도는 중인지다. 도는 동안 다시 눌러도 요청을 겹쳐 보내지 않는다 */
  const retrying = useRef(false);

  const retry = useCallback(() => {
    if (retrying.current) return;
    retrying.current = true;
    setRetried(false);
    void fetchMe().then((me) => {
      retrying.current = false;
      setReread(me);
      setRetried(true);
    });
  }, []);
  // 레이아웃이 읽지 못했으면 올라온 뒤 한 번 다시 읽는다. 그래도 못 읽으면 실패로 두고 「다시 읽기」 를 기다린다.
  useEffect(() => {
    if (role !== null || !signedIn) return;
    let stale = false;
    void fetchMe().then((me) => {
      if (stale) return;
      setReread(me);
      setRetried(true);
    });
    return () => {
      stale = true;
    };
  }, [role, signedIn]);

  // 로그아웃한 뒤 뒤로 가기로 돌아온 문서는 브라우저가 bfcache 에서 그대로 꺼낼 수 있다. 브라우저마다 달라
  // 기대지 않고 세션을 확인해, 없거나 모르면 다시 읽는다. 다시 읽은 page 가 로그인 화면으로 보낸다.
  // 세션이 있으면 다시 읽지 않아 쓰던 글과 스크롤이 남는다(docs/features/app-shell/design.md 의 「로그아웃」).
  useEffect(() => {
    if (!signedIn) return;
    const reloadRestored = (event: PageTransitionEvent) => {
      if (!event.persisted) return;
      void hasBrowserSession().then((present) => {
        if (present !== true) window.location.reload();
      });
    };
    window.addEventListener("pageshow", reloadRestored);
    return () => window.removeEventListener("pageshow", reloadRestored);
  }, [signedIn]);

  // 관리자 영역에서 돌아갈 곳이다. 저장소를 막은 브라우저에서는 적지 않고 넘어간다.
  useEffect(() => {
    if (!pathname.startsWith("/chat/")) return;
    try {
      sessionStorage.setItem(LAST_CONVERSATION_KEY, pathname);
    } catch {
      /* 적지 못하면 돌아갈 곳은 새 대화 화면이다. */
    }
  }, [pathname]);

  const knownRole = role ?? reread?.role ?? null;
  const knownName = displayName || reread?.displayName || null;
  const account = useMemo(
    () => ({
      state: shellRoleState(knownRole, retried),
      displayName: knownName,
      retry,
    }),
    [knownRole, retried, knownName, retry],
  );

  return (
    <TooltipProvider>
      <AdminContext.Provider value={knownRole === "ADMIN"}>
        <DisplayNameContext.Provider value={knownName}>
          <ShellAccountContext.Provider value={account}>
            <AppNameContext.Provider value={appName}>
              {/* 관리자 영역에서는 대화 목록을 읽지 않는다. */}
              <ConversationsProvider enabled={signedIn && !inAdminArea}>
                <NotificationsProvider enabled={signedIn && !inAdminArea}>
                  <ShellBody signedIn={signedIn} hasSession={hasSession}>
                    {children}
                  </ShellBody>
                </NotificationsProvider>
              </ConversationsProvider>
            </AppNameContext.Provider>
          </ShellAccountContext.Provider>
        </DisplayNameContext.Provider>
      </AdminContext.Provider>
    </TooltipProvider>
  );
}
