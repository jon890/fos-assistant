"use client";

import Link from "next/link";
import { useRef, useState, useSyncExternalStore } from "react";
import { useTheme } from "next-themes";
import { LogOut, Settings, ShieldCheck, UserRound } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { requestSignOut } from "@/lib/sign-out-client";
import { useShellAccount } from "./shell-account";

/** 「화면 밝기」 가 고르는 값이다. `next-themes` 의 값과 화면 이름을 함께 둔다. */
const THEMES = [
  { value: "light", label: "밝게" },
  { value: "dark", label: "어둡게" },
  { value: "system", label: "기기 설정" },
] as const;

function subscribeNothing(): () => void {
  return () => {};
}

/**
 * 사이드바 맨 아래의 이름 줄과 이름 메뉴다(docs/features/app-shell/spec.md 의 REQ3, REQ6).
 *
 * <p>메뉴 항목은 고르는 순간 메뉴가 닫히므로 로그아웃 실패 안내는 메뉴 밖, 이름 줄 위에 둔다.
 * 역할을 읽지 못했으면 안내와 「다시 읽기」 를 메뉴 단추와 형제로 둔다. 단추 안에 단추를 넣지 않는다.
 */
export function AccountMenu({
  onNavigate,
}: {
  /** 링크를 누를 때 그 목적지를 받는다. 사이드바의 `onNavigate` 와 같다 */
  onNavigate(href: string): void;
}) {
  const account = useShellAccount();
  const { theme, setTheme } = useTheme();
  // 서버와 hydration 동안은 저장된 밝기를 모르므로 라디오를 고르지 않은 채 둔다.
  const mounted = useSyncExternalStore(
    subscribeNothing,
    () => true,
    () => false,
  );
  const [signOutFailed, setSignOutFailed] = useState(false);
  /** 로그아웃 요청이 도는 중인지다. 도는 동안 다시 눌러도 요청을 겹쳐 보내지 않는다 */
  const signingOut = useRef(false);

  async function signOut(): Promise<void> {
    if (signingOut.current) return;
    signingOut.current = true;
    setSignOutFailed(false);
    if (await requestSignOut()) {
      // 문서째 새로 열어 클라이언트 라우터 캐시와 화면 상태를 함께 버린다.
      window.location.replace("/signin");
      return;
    }
    setSignOutFailed(true);
    signingOut.current = false;
  }

  const failed = account.state === "failed";
  const name = account.displayName;

  return (
    <div className="mt-3 shrink-0">
      {signOutFailed ? (
        <p role="alert" className="mb-2 px-3 text-xs text-destructive">
          로그아웃하지 못했어요. 아직 로그인된 상태예요
        </p>
      ) : null}
      <div className="flex items-center gap-2">
        {failed ? (
          <span className="flex min-w-0 flex-1 items-center gap-2 px-3 text-xs text-muted-foreground">
            <span className="min-w-0 truncate">계정 정보를 읽지 못했어요</span>
            <Button
              variant="outline"
              size="sm"
              className="shrink-0 bg-background hover:bg-accent"
              onClick={account.retry}
            >
              다시 읽기
            </Button>
          </span>
        ) : null}
        <DropdownMenu modal={false}>
          <DropdownMenuTrigger asChild>
            <Button
              variant="ghost"
              aria-label={name && !failed ? `계정 메뉴, ${name}` : "계정 메뉴"}
              className={
                failed
                  ? "shrink-0 px-2 hover:bg-accent"
                  : "min-w-0 flex-1 justify-start gap-2 px-3 font-normal hover:bg-accent"
              }
            >
              <UserRound aria-hidden="true" className="size-4 shrink-0" />
              {failed ? null : (
                <span
                  className="min-w-0 truncate text-sm text-muted-foreground"
                  title={name ?? undefined}
                >
                  {name ?? "계정"}
                </span>
              )}
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent side="top" align="start" className="w-56">
            {name ? (
              <DropdownMenuLabel className="truncate font-medium">
                {name}
              </DropdownMenuLabel>
            ) : null}
            <DropdownMenuItem asChild>
              <Link href="/settings" onClick={() => onNavigate("/settings")}>
                <Settings aria-hidden="true" />
                설정
              </Link>
            </DropdownMenuItem>
            {account.state === "admin" ? (
              <DropdownMenuItem asChild>
                <Link
                  href="/admin"
                  data-testid="admin-entry"
                  onClick={() => onNavigate("/admin")}
                >
                  <ShieldCheck aria-hidden="true" />
                  관리자 화면
                </Link>
              </DropdownMenuItem>
            ) : null}
            <DropdownMenuSeparator />
            <DropdownMenuLabel
              id="account-menu-theme"
              className="text-xs font-normal text-muted-foreground"
            >
              화면 밝기
            </DropdownMenuLabel>
            <DropdownMenuRadioGroup
              aria-labelledby="account-menu-theme"
              value={mounted ? (theme ?? "") : ""}
              onValueChange={setTheme}
            >
              {THEMES.map((option) => (
                <DropdownMenuRadioItem key={option.value} value={option.value}>
                  {option.label}
                </DropdownMenuRadioItem>
              ))}
            </DropdownMenuRadioGroup>
            <DropdownMenuSeparator />
            <DropdownMenuItem onSelect={() => void signOut()}>
              <LogOut aria-hidden="true" />
              로그아웃
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </div>
  );
}
