"use client";

import { useRef } from "react";
import { X } from "lucide-react";
import { cn } from "cn";
import { focusWithoutTooltip, TooltipButton } from "@/components/ui/tooltip-button";
import { Sheet, SheetContent, SheetTitle } from "@/components/ui/sheet";
import { useMediaQuery } from "@/components/ui/use-media-query";

/**
 * iframe 에 주는 권한이다. 스크립트 실행 권한은 주지 않는다(ADR-027).
 *
 * <p>`allow-same-origin` 이 있어야 HTML 이 상대 경로로 부르는 사진에 로그인 쿠키가 간다. 스크립트가 돌지 않으므로
 * 같은 출처여도 문서가 앱의 API 를 부르지 못한다. `target="_blank"` 링크는 새 탭에서 sandbox 없이 열린다.
 */
const FRAME_SANDBOX = "allow-same-origin allow-popups allow-popups-to-escape-sandbox";

type Props = { conversationId: string; path: string; onClose(): void };

/** 대화 결과물 폴더 안의 파일을 웹 서버 라우트로 받는 주소다. 조각마다 따로 싼다. */
function artifactUrl(conversationId: string, path: string): string {
  return `/api/chat/conversations/${conversationId}/files/${path.split("/").map(encodeURIComponent).join("/")}`;
}

/**
 * 답이 만든 HTML 을 옆 패널에 띄운다. 작업 과정 패널과 같은 자리를 쓴다.
 *
 * <p>띄우기 전에 파일을 한 번 더 받아 확인하지 않는다. 두 번 받게 되고, 연 사이 지워졌으면 iframe 안에 서버의 오류
 * 응답이 그대로 보인다. 초점이 iframe 안에 있으면 Esc 가 이 창까지 오지 않아 닫기 단추로 닫는다.
 */
export function ArtifactPanel({ conversationId, path, onClose }: Props) {
  // lg 이상은 대화 옆에 붙어 대화를 계속 쓸 수 있다. 그보다 좁으면 전체 폭 Sheet 로 대화를 덮는다.
  // 첫 그림에서 폭을 모르면(null) 옆에 붙는 모양으로 그린다.
  const wide = useMediaQuery("(min-width: 1024px)");
  const closeRef = useRef<HTMLButtonElement>(null);
  const name = path.split("/").at(-1) ?? path;
  const src = artifactUrl(conversationId, path);

  const header = (title: React.ReactNode) => (
    <header className="flex min-w-0 items-center gap-2 border-b border-border px-4 py-3">
      {title}
      <a href={src} target="_blank" rel="noreferrer" data-testid="artifact-open-tab"
        className="shrink-0 text-xs text-muted-foreground underline underline-offset-4">새 탭으로 열기</a>
      <TooltipButton ref={closeRef} label="결과물 닫기" onClick={onClose}><X aria-hidden="true" /></TooltipButton>
    </header>
  );
  const body = (
    // 결과물 HTML 은 제 배경을 적지 않는 일이 많다. 어두운 테마에서도 글이 읽히도록 흰 바탕에 띄운다.
    <iframe key={src} title={name} sandbox={FRAME_SANDBOX} src={src} data-testid="artifact-frame"
      className="min-h-0 w-full flex-1 border-0 bg-white" />
  );
  const titleClass = "min-w-0 flex-1 truncate text-sm font-semibold";

  if (wide !== false) {
    return (
      <aside data-testid="artifact-panel" role="complementary" aria-label="결과물"
        className={cn(
          "relative flex min-w-0 flex-col",
          "w-[min(48rem,50vw)] shrink-0",
          "border-l border-border bg-background",
        )}>
        {header(<h2 className={titleClass} title={path}>{name}</h2>)}
        {body}
      </aside>
    );
  }

  return (
    <Sheet open onOpenChange={(open) => { if (!open) onClose(); }}>
      {/* 닫기 단추는 머리의 「결과물 닫기」 하나만 둔다. */}
      <SheetContent side="right" data-testid="artifact-panel" showCloseButton={false} aria-describedby={undefined}
        onOpenAutoFocus={(event) => {
          // Radix 의 기본 초점 주기는 첫 단추의 Tooltip 을 열어 첫 Esc 를 Tooltip 이 가져간다. 닫기 단추에 풀이 없이 준다.
          event.preventDefault();
          focusWithoutTooltip(closeRef.current);
        }}
        className="min-w-0 gap-0 border-border bg-background data-[side=right]:w-full data-[side=right]:sm:max-w-none">
        {header(<SheetTitle className={titleClass} title={path}>{name}</SheetTitle>)}
        {body}
      </SheetContent>
    </Sheet>
  );
}
