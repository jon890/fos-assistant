// 「내 파일」 화면의 순수 함수다. 날짜 구분, 종류, 미리보기 방식, 보이는 이름을 정한다(스펙 workspace REQ10, REQ11, REQ14).
// `web/test/unit/my-files.test.ts` 가 `node --test` 로 직접 읽으므로 import 는 상대 경로만 쓴다.

export type FileKind = "IMAGE" | "DOCUMENT" | "TABLE";

/** 미리보기를 그리는 방식이다. `none` 은 「미리보기가 없어요」 와 내려받기다. */
export type FilePreview = "image" | "html" | "table" | "text" | "none";

/** 글과 표 미리보기가 받는 최대 크기다. 실행 공간 미리보기의 상한과 같다. */
export const TEXT_PREVIEW_MAX_BYTES = 1024 * 1024;

export const KIND_LABELS: Record<FileKind, string> = {
  IMAGE: "그림",
  DOCUMENT: "문서",
  TABLE: "표",
};

function extension(path: string): string {
  const name = path.slice(path.lastIndexOf("/") + 1);
  const dot = name.lastIndexOf(".");
  return dot > 0 ? name.slice(dot + 1).toLowerCase() : "";
}

/**
 * 경로로 미리보기 방식을 정한다. 글과 표는 상한을 넘으면 받지 않는다.
 * 엑셀, 워드, 파워포인트, PDF 는 미리보기가 없다(스펙 DECISION18).
 */
export function filePreview(path: string, byteSize: number): FilePreview {
  const ext = extension(path);
  if (["png", "jpg", "jpeg", "gif", "webp"].includes(ext)) return "image";
  if (ext === "html") return "html";
  const textual = ["md", "txt", "css"].includes(ext)
    ? "text"
    : ["csv", "tsv"].includes(ext)
      ? "table"
      : null;
  if (textual === null || byteSize > TEXT_PREVIEW_MAX_BYTES) return "none";
  return textual;
}

/** 목록이 보이는 이름이다. 제목이 있으면 제목, 비서가 만든 그림은 확장자를 뗀 이름, `index.html` 이면 폴더 이름이다. */
export function displayName(path: string, title?: string | null): string {
  const given = title?.trim();
  if (given) return given;
  const parts = path.split("/").filter((part) => part !== "");
  const name = parts.at(-1) ?? path;
  if (name.toLowerCase() === "index.html" && parts.length > 1)
    return parts.at(-2) as string;
  if (path.startsWith("generated/") && extension(path) !== "") {
    return name.slice(0, name.lastIndexOf("."));
  }
  return name;
}

/** 날짜 구분 하나다. `key` 는 같은 구분의 줄을 묶는 값이다. */
export type DateGroup = { key: string; label: string };

function startOfDay(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

/**
 * 넣은 시각이 드는 날짜 구분이다. 기기의 시간대와 달력을 따르고 한 주는 월요일에 시작한다.
 * 맞는 구분이 여럿이면 가장 앞의 하나다. 「오늘」, 「이번 주」, 「이번 달」, 그 앞은 달마다다.
 */
export function dateGroup(value: string, now: Date): DateGroup {
  const at = new Date(value);
  const today = startOfDay(now);
  if (at >= today) return { key: "today", label: "오늘" };
  const weekStart = new Date(today);
  weekStart.setDate(today.getDate() - ((today.getDay() + 6) % 7));
  if (at >= weekStart) return { key: "week", label: "이번 주" };
  const monthStart = new Date(today.getFullYear(), today.getMonth(), 1);
  if (at >= monthStart) return { key: "month", label: "이번 달" };
  return {
    key: `${at.getFullYear()}-${at.getMonth() + 1}`,
    label: `${at.getFullYear()}년 ${at.getMonth() + 1}월`,
  };
}

/** 줄을 날짜 구분으로 묶는다. 줄의 차례(최신순)를 그대로 지킨다. */
export function groupByDate<T extends { vaultedAt: string }>(
  items: T[],
  now: Date,
): { group: DateGroup; items: T[] }[] {
  const groups: { group: DateGroup; items: T[] }[] = [];
  for (const item of items) {
    const group = dateGroup(item.vaultedAt, now);
    const last = groups.at(-1);
    if (last && last.group.key === group.key) last.items.push(item);
    else groups.push({ group, items: [item] });
  }
  return groups;
}

/** 줄에 보이는 날짜다. 올해면 연도를 뺀다. */
export function shortDate(value: string, now: Date): string {
  const at = new Date(value);
  const day = `${at.getMonth() + 1}월 ${at.getDate()}일`;
  return at.getFullYear() === now.getFullYear()
    ? day
    : `${at.getFullYear()}년 ${day}`;
}

/** 전체 용량처럼 딱 떨어지는 크기는 소수점을 뗀다(5 GB). 화면 글자는 25MB, 5GB 처럼 적는다(스펙 DECISION9). */
export function roundSize(formatted: string): string {
  return formatted.replace(/\.0 /, " ");
}

/**
 * 받은 글 바이트를 글자로 읽는다. UTF-8 이 아니면 EUC-KR 로 읽는다. 한글 윈도우에서 만든 CSV 가 깨지지 않게 한다.
 */
export function decodeText(bytes: ArrayBuffer): string {
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    return new TextDecoder("euc-kr").decode(bytes);
  }
}
