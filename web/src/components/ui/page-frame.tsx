import Link from "next/link";
import type { ReactNode } from "react";
import { ArrowLeft } from "lucide-react";
import { Skeleton } from "@/components/ui/skeleton";

type Props = {
  title: ReactNode;
  /** 그 화면이 무엇인지 알리는 한 줄이다. 상세 화면은 두지 않는다 */
  description?: ReactNode;
  /** 머리 오른쪽의 주 단추다. 없으면 자리를 그리지 않는다 */
  action?: ReactNode;
  /** 상세 화면의 뒤로 가기다. 돌아갈 화면의 주소와 이름이다 */
  back?: { href: string; label: string };
  /** 읽는 중의 뼈대로 그린다. 머리는 그대로이고 본문에 뼈대를 둔다 */
  busy?: boolean;
  /** 제목을 아직 모른다. 제목 자리에 뼈대를 둔다 */
  titlePending?: boolean;
  children: ReactNode;
};

/**
 * 사이드바와 설정에서 여는 화면의 머리와 본문 폭이다(docs/features/app-shell/design.md 의 「같은 머리 틀」).
 *
 * <p>화면이 폭과 `h1` 을 따로 갖지 않게 이 부품 하나가 맡는다. 폭을 고르는 prop 은 두지 않는다.
 * 머리는 줄바꿈하는 flex 라 폭이 모자라면 주 단추가 제목 아래로 내려가고 서로 겹치지 않는다.
 */
export function PageFrame({
  title,
  description,
  action,
  back,
  busy = false,
  titlePending = false,
  children,
}: Props) {
  return (
    <div
      data-slot="page-frame"
      data-testid={busy ? "page-skeleton" : undefined}
      aria-busy={busy ? "true" : undefined}
      className="mx-auto w-full max-w-4xl"
    >
      {back ? (
        <Link
          href={back.href}
          data-slot="page-back"
          className="mb-1 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft aria-hidden="true" className="size-4 shrink-0" />
          {back.label}
        </Link>
      ) : null}
      {/* 화면 위 막대가 이미 header 다. 머리 줄은 landmark 가 아니라 div 로 둔다. */}
      <div
        data-slot="page-head"
        className="mb-4 flex flex-wrap items-start justify-between gap-x-3 gap-y-2"
      >
        <div className="min-w-0 flex-1 basis-56">
          {titlePending ? (
            <Skeleton className="h-7 w-48" />
          ) : (
            <h1 className="text-xl font-semibold break-words">{title}</h1>
          )}
          {description ? (
            <p
              data-slot="page-description"
              className="mt-1 text-sm text-muted-foreground"
            >
              {description}
            </p>
          ) : null}
        </div>
        {action ? (
          <div
            data-slot="page-action"
            // 폭이 모자라 제목 아래 줄로 내려가도 오른쪽 끝에 붙는다.
            className="ml-auto flex shrink-0 flex-wrap items-center justify-end gap-2"
          >
            {action}
          </div>
        ) : null}
      </div>
      <div data-slot="page-body">{children}</div>
    </div>
  );
}
