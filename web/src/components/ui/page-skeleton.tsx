import { Skeleton } from "@/components/ui/skeleton";

export type PageSkeletonShape = "cards" | "list" | "table" | "editor" | "tree" | "chat";
export type PageSkeletonWidth = "2xl" | "3xl" | "4xl" | "5xl";

type Props = {
  shape: PageSkeletonShape;
  width: PageSkeletonWidth;
  title?: boolean;
  description?: boolean;
};

/**
 * Tailwind 가 클래스를 찾을 수 있도록 문자열을 그대로 적는다. 템플릿으로 조립하면 빌드가 이 클래스를
 * 찾지 못해 뼈대의 최대 폭이 실제 화면과 어긋난다.
 */
const WIDTH_CLASS: Record<PageSkeletonWidth, string> = {
  "2xl": "max-w-2xl",
  "3xl": "max-w-3xl",
  "4xl": "max-w-4xl",
  "5xl": "max-w-5xl",
};

/** 제목과, 있으면 그 아래 설명 문단 자리다. `page.tsx` 의 `h1` 여백과 같게 맞춘다. */
function TitleBlock({ description }: { description: boolean }) {
  return (
    <>
      <Skeleton className={`h-7 w-48 ${description ? "mb-2" : "mb-6"}`} />
      {description ? <Skeleton className="mb-6 h-5 w-full max-w-2xl" /> : null}
    </>
  );
}

/**
 * 카드 목록이다. `width` 가 `4xl` 이면 `/admin/agents` 의 `AgentForm` 자리를 카드 위에 먼저 둔다.
 * `2xl` 은 `/agents` 처럼 한 줄짜리 카드라 낮고, `4xl` 은 관리 카드처럼 정보가 많아 더 높다.
 */
function CardsBody({ width }: { width: PageSkeletonWidth }) {
  const isAdminWidth = width === "4xl";
  const cardHeight = isAdminWidth ? "h-40" : "h-16";
  return (
    <>
      {isAdminWidth ? <Skeleton className="mb-8 h-64 w-full" /> : null}
      <div className="grid gap-3">
        <Skeleton className={`${cardHeight} w-full`} />
        <Skeleton className={`${cardHeight} w-full`} />
        <Skeleton className={`${cardHeight} w-full`} />
      </div>
    </>
  );
}

/** `/memory`, `/admin/people` 처럼 한 줄짜리 항목이 세로로 쌓이는 목록이다. */
function ListBody() {
  return (
    <div className="grid gap-3">
      <Skeleton className="h-14 w-full" />
      <Skeleton className="h-14 w-full" />
      <Skeleton className="h-14 w-full" />
      <Skeleton className="h-14 w-full" />
      <Skeleton className="h-14 w-full" />
    </div>
  );
}

/** `/usage` 다. `MonthlySummary` 같은 합계 칸 하나와 표 줄 다섯이다. */
function TableBody() {
  return (
    <>
      <div className="mb-6 grid gap-5 rounded-md border border-border bg-surface p-4 sm:grid-cols-2 md:grid-cols-3">
        <Skeleton className="h-14 w-full" />
        <Skeleton className="h-14 w-full" />
        <Skeleton className="h-14 w-full" />
      </div>
      <div className="grid gap-2">
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
      </div>
    </>
  );
}

/** `/agents/{code}` 다. `PersonaEditor` 의 긴 입력칸(`rows={16}`) 자리다. */
function EditorBody() {
  return <Skeleton className="h-96 w-full" />;
}

/** `/executions/{id}` 다. 제목은 이 컴포넌트의 `title` 이 아니라 요약 자리 안에 직접 둔다. */
function TreeBody() {
  return (
    <div>
      <Skeleton className="mb-4 h-7 w-56" />
      <div className="mb-6 grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-3">
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
      </div>
      <div className="grid gap-2">
        <Skeleton className="h-6 w-3/4" />
        <Skeleton className="ml-6 h-6 w-2/3" />
        <Skeleton className="ml-6 h-6 w-2/3" />
        <Skeleton className="ml-12 h-6 w-1/2" />
      </div>
    </div>
  );
}

/**
 * `/c/{id}` 다. `chat-panel.tsx` 의 세로 배치(머리 줄, 메시지 자리, 맨 아래 입력창 자리)를 따른다.
 * 가운데 두 줄은 `message-list.tsx` 가 메시지를 읽는 동안 그리는 뼈대와 높이·간격이 같다.
 */
function ChatSkeleton() {
  return (
    <div data-testid="page-skeleton" aria-busy="true" className="flex h-full min-h-0 flex-col">
      <div className="border-b border-border pb-3">
        <Skeleton className="h-4 w-32" />
      </div>
      <div className="min-h-0 flex-1 overflow-hidden px-1 py-3">
        <div className="mx-auto flex w-full max-w-3xl flex-col gap-5">
          <Skeleton className="h-[4.25rem]" />
          <Skeleton className="h-[4.25rem]" />
        </div>
      </div>
      <div className="mx-auto w-full max-w-3xl pt-3">
        <Skeleton className="h-12 w-full rounded-3xl" />
      </div>
    </div>
  );
}

export function PageSkeleton({ shape, width, title = false, description = false }: Props) {
  if (shape === "chat") return <ChatSkeleton />;

  return (
    <div data-testid="page-skeleton" aria-busy="true" className={`mx-auto w-full ${WIDTH_CLASS[width]}`}>
      {title ? <TitleBlock description={description} /> : null}
      {shape === "cards" ? <CardsBody width={width} /> : null}
      {shape === "list" ? <ListBody /> : null}
      {shape === "table" ? <TableBody /> : null}
      {shape === "editor" ? <EditorBody /> : null}
      {shape === "tree" ? <TreeBody /> : null}
    </div>
  );
}
