import { Skeleton } from "@/components/ui/skeleton";

export type PageSkeletonShape = "cards" | "list" | "table" | "editor" | "tree" | "chat";
export type PageSkeletonWidth = "2xl" | "3xl" | "4xl" | "5xl";
export type PageSkeletonForm = "memory" | "person" | "agent";
export type PageSkeletonDescription = "usage" | "memory" | "agent" | "person" | "persona";

type Props = {
  shape: PageSkeletonShape;
  width: PageSkeletonWidth;
  title?: boolean;
  /** 제목 아래 설명 문단이 있는 화면이면 그 화면이다. 화면마다 접히는 줄 수가 다르다. */
  description?: PageSkeletonDescription;
  /** 목록 위에 입력 폼이 먼저 오는 화면이면 그 폼이다. */
  form?: PageSkeletonForm;
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

/**
 * 입력 폼 자리의 높이다. 390px 와 1280px 에서 측정한 실제 폼 높이이고, `md` 에서 입력칸이 두 줄로 나뉘어 낮아진다.
 * 아래 여백은 세 폼 모두 `mb-8` 이다. 폼의 칸이 바뀌면 이 값도 다시 측정한다.
 */
const FORM_CLASS: Record<PageSkeletonForm, string> = {
  memory: "h-[24.875rem] md:h-[19.875rem]",
  person: "h-[22.375rem] md:h-[12.875rem]",
  agent: "h-[46.875rem] md:h-[29.875rem]",
};

function FormBlock({ form }: { form: PageSkeletonForm }) {
  return <Skeleton className={`mb-8 w-full ${FORM_CLASS[form]}`} />;
}

/**
 * 설명 문단(`max-w-2xl text-sm leading-6`)의 줄마다 둘 자리다. 한 줄은 24px 이다.
 * 390px 와 1280px 에서 측정한 실제 줄 수에 맞춰, 좁은 폭에서만 접히는 줄은 `md:hidden` 으로 둔다.
 * 설명 글이 바뀌면 이 값도 다시 측정한다.
 */
const DESCRIPTION_LINES: Record<PageSkeletonDescription, readonly string[]> = {
  usage: ["flex", "flex", "flex md:hidden"],
  memory: ["flex"],
  agent: ["flex", "flex", "flex md:hidden"],
  person: ["flex", "flex md:hidden"],
  persona: ["flex", "flex md:hidden"],
};

/** 제목과, 있으면 그 아래 설명 문단 자리다. `page.tsx` 의 `h1` 여백과 같게 맞춘다. */
function TitleBlock({ description }: { description?: PageSkeletonDescription }) {
  return (
    <>
      <Skeleton className={`h-7 w-48 ${description ? "mb-2" : "mb-6"}`} />
      {description ? (
        <div className="mb-6 max-w-2xl">
          {DESCRIPTION_LINES[description].map((lineClass, index) => (
            <div key={index} className={`h-6 items-center ${lineClass}`}>
              <Skeleton className="h-4 w-full" />
            </div>
          ))}
        </div>
      ) : null}
    </>
  );
}

/**
 * 카드 목록이다. 관리자는 입력 폼과 관리 카드가 있어 더 높고, 일반 사용자의 `/agents` 는
 * 한 줄짜리 `Card`(위아래 `py-4` 와 글자 한 줄)라 56px 다. `Card` 의 테두리는 `ring` 이라 높이에 들지 않는다.
 */
function CardsBody({ tall }: { tall: boolean }) {
  const cardHeight = tall ? "h-40" : "h-14";
  return (
    <>
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

/**
 * `/usage` 다. `UsageTabs` 의 탭 줄, `MonthlySummary` 의 합계 칸 셋과 그 아래 안내 한 줄, 표 줄 다섯이다.
 * 탭 줄은 링크 한 줄(`py-2` 와 글자 한 줄, 아래 테두리 2px)이라 38px 이다.
 * 합계 칸 하나는 `Stat` 의 `Card`(위아래 `py-4`, 이름표·값·설명 세 줄)라 104px 다. `Card` 의 테두리는 `ring` 이라 높이에 들지 않는다.
 */
function TableBody() {
  return (
    <>
      <Skeleton className="mb-6 h-[2.375rem] w-full" />
      <div className="mb-6 grid gap-3 sm:grid-cols-2 md:grid-cols-3">
        <Skeleton className="h-26 w-full" />
        <Skeleton className="h-26 w-full" />
        <Skeleton className="h-26 w-full" />
        <Skeleton className="h-4 w-2/3 sm:col-span-2 md:col-span-3" />
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

/** `/agents/{code}` 다. `PersonaEditor` 의 긴 입력칸 자리다. `rows={16}` 에 `leading-6` 과 `py-2`, 테두리를 더해 402px 다. */
function EditorBody() {
  return <Skeleton className="h-[25.125rem] w-full" />;
}

/**
 * `/executions/{id}` 다. 제목은 이 컴포넌트의 `title` 이 아니라 요약 자리 안에 직접 둔다.
 * 요약의 첫 줄은 상태 `Badge` 가 있어 42px 이고 나머지 줄은 40px 다. 좁은 폭은 두 칸, `sm` 부터 세 칸이 첫 줄이다.
 */
function TreeBody() {
  return (
    <div>
      <Skeleton className="mb-4 h-7 w-56" />
      <div className="mb-6 grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-3">
        <Skeleton className="h-[2.625rem] w-full" />
        <Skeleton className="h-[2.625rem] w-full" />
        <Skeleton className="h-10 w-full sm:h-[2.625rem]" />
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
 * `/chat/{id}` 다. `chat-panel.tsx` 의 세로 배치(머리 줄, 메시지 자리, 맨 아래 입력창 자리)를 따른다.
 * 가운데 두 줄은 `message-list.tsx` 가 메시지를 읽는 동안 그리는 뼈대와 높이·간격이 같다.
 */
function ChatSkeleton({ width }: { width: PageSkeletonWidth }) {
  return (
    <div data-testid="page-skeleton" aria-busy="true" className="flex h-full min-h-0 flex-col">
      <div className="border-b border-border pb-3">
        <Skeleton className="h-4 w-32" />
      </div>
      <div className="min-h-0 flex-1 overflow-hidden px-1 py-3">
        <div className={`mx-auto flex w-full flex-col gap-5 ${WIDTH_CLASS[width]}`}>
          <Skeleton className="h-[4.25rem]" />
          <Skeleton className="h-[4.25rem]" />
        </div>
      </div>
      <div className={`mx-auto w-full pt-3 ${WIDTH_CLASS[width]}`}>
        <Skeleton className="h-12 w-full rounded-3xl" />
      </div>
    </div>
  );
}

export function PageSkeleton({ shape, width, title = false, description, form }: Props) {
  if (shape === "chat") return <ChatSkeleton width={width} />;

  return (
    <div data-testid="page-skeleton" aria-busy="true" className={`mx-auto w-full ${WIDTH_CLASS[width]}`}>
      {title ? <TitleBlock description={description} /> : null}
      {form ? <FormBlock form={form} /> : null}
      {shape === "cards" ? <CardsBody tall={form === "agent"} /> : null}
      {shape === "list" ? <ListBody /> : null}
      {shape === "table" ? <TableBody /> : null}
      {shape === "editor" ? <EditorBody /> : null}
      {shape === "tree" ? <TreeBody /> : null}
    </div>
  );
}
