// 파일 공간 화면의 순수 함수다. 미리보기 확장자와 상한은 `docs/code-architecture.md` 의 「본문 머리글」 표와 같다.
// Control Plane 도 같은 상한으로 413 을 내므로 화면은 상한을 넘는 파일을 요청하지 않는다.

export type WorkspaceEntryKind = "DIRECTORY" | "FILE" | "LINK" | "OTHER";

export type PreviewKind = "text" | "table" | "image" | "html" | "none";

const MIB = 1024 * 1024;

const TEXT_EXTENSIONS = new Set(
  (
    "txt md markdown log json jsonl yaml yml toml ini cfg conf env py js mjs cjs ts tsx jsx " +
    "java kt go rs rb sh bash zsh sql xml svg scss css"
  ).split(" "),
);

const IMAGE_EXTENSIONS = new Set(["png", "jpg", "jpeg", "gif", "webp"]);

/** 확장자마다 미리보기 종류와 크기 상한이다. 확장자가 없는 이름은 글이다. */
function previewRule(name: string): { kind: PreviewKind; max: number } | null {
  const dot = name.lastIndexOf(".");
  if (dot < 0) return { kind: "text", max: MIB };
  // 맨 앞의 . 하나뿐인 이름(.env)은 그 뒤 전체가 확장자다. lastIndexOf 가 0 이면 그렇게 읽힌다.
  const extension = name.slice(dot + 1).toLowerCase();
  if (extension === "html" || extension === "htm")
    return { kind: "html", max: 5 * MIB };
  if (IMAGE_EXTENSIONS.has(extension)) return { kind: "image", max: 20 * MIB };
  if (extension === "csv" || extension === "tsv")
    return { kind: "table", max: MIB };
  if (TEXT_EXTENSIONS.has(extension)) return { kind: "text", max: MIB };
  return null;
}

/** 이름과 크기로 미리보기 종류를 정한다. 크기를 모르거나 상한을 넘으면 미리보기가 없다. */
export function previewKind(name: string, size: number | null): PreviewKind {
  const rule = previewRule(name);
  if (rule === null || size === null || size > rule.max) return "none";
  return rule.kind;
}

/** 파일 본문을 받는 웹 서버 라우트 주소다. 조각마다 따로 싼다. */
export function fileUrl(path: string, download = false): string {
  const encoded = path.split("/").map(encodeURIComponent).join("/");
  return `/api/workspace/files/${encoded}${download ? "?download=1" : ""}`;
}

/** 디렉터리 경로 뒤에 이름 하나를 붙인다. 빈 경로는 사용자 디렉터리 자체다. */
export function joinPath(dir: string, name: string): string {
  return dir === "" ? name : `${dir}/${name}`;
}

/**
 * 경로의 모든 조각을 주소 조각으로 쓸 수 있는가. `%`, `;`, `\` 가 든 이름은 Control Plane 의 요청 방화벽이 주소에서
 * 거절하므로, 그런 디렉터리 안의 파일도 미리보기와 내려받기를 열 수 없다.
 */
export function addressable(path: string): boolean {
  return !/[%;\\]/.test(path);
}

/** 경로 줄의 조각이다. 맨 앞은 사용자 디렉터리 자체인 「파일 공간」 이다. 빈 조각은 건너뛴다. */
export function crumbs(path: string): { name: string; path: string }[] {
  const result = [{ name: "파일 공간", path: "" }];
  let current = "";
  for (const name of path.split("/").filter((part) => part !== "")) {
    current = joinPath(current, name);
    result.push({ name, path: current });
  }
  return result;
}

/** `/files` 화면의 주소다. 연 디렉터리는 `path`, 미리 보는 파일은 `file` 인자에 둔다. */
export function explorerHref(path: string, file?: string): string {
  const query = new URLSearchParams();
  if (path !== "") query.set("path", path);
  if (file !== undefined) query.set("file", file);
  const search = query.toString();
  return search === "" ? "/files" : `/files?${search}`;
}

/**
 * 쉼표나 탭으로 나눈 글을 줄과 칸으로 읽는다. RFC 4180 의 따옴표, 이중 따옴표, 따옴표 안 줄바꿈만 다룬다.
 *
 * <p>`maxRows` 줄까지만 돌려주고 더 있으면 `truncated` 가 참이다. 끝의 빈 줄은 줄로 세지 않는다.
 */
export function parseDelimited(
  text: string,
  delimiter: "," | "\t",
  maxRows: number,
): { rows: string[][]; truncated: boolean } {
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = "";
  let quoted = false;
  // 이 칸에서 글자를 읽었는지다. 빈 따옴표 칸(`""`)도 읽은 것으로 센다. 따옴표는 칸의 첫 글자일 때만 감싸기다.
  let started = false;
  let index = 0;

  const endCell = () => {
    row.push(cell);
    cell = "";
    started = false;
  };
  const endRow = () => {
    endCell();
    rows.push(row);
    row = [];
  };

  while (index < text.length) {
    const char = text[index];
    if (quoted) {
      if (char === '"' && text[index + 1] === '"') {
        cell += '"';
        index += 2;
        continue;
      }
      if (char === '"') quoted = false;
      else cell += char;
      index += 1;
      continue;
    }
    if (char === '"' && !started) quoted = started = true;
    else if (char === delimiter) endCell();
    else if (char === "\r" && text[index + 1] === "\n") {
      endRow();
      index += 1;
    } else if (char === "\n" || char === "\r") endRow();
    else {
      cell += char;
      started = true;
    }
    if (rows.length > maxRows) break;
    index += 1;
  }
  if (rows.length <= maxRows && (started || row.length > 0)) endRow();

  const truncated = rows.length > maxRows;
  return { rows: truncated ? rows.slice(0, maxRows) : rows, truncated };
}

/** 바이트 수를 읽기 쉬운 크기로 바꾼다. 1 KB 는 1,024 바이트이고, 반올림해 1,024 가 되면 다음 단위로 보인다. */
export function formatSize(bytes: number): string {
  const units = ["B", "KB", "MB", "GB", "TB"];
  let unit = 0;
  while (unit < 4 && Number((bytes / 1024 ** unit).toFixed(1)) >= 1024) unit++;
  const value = unit === 0 ? `${bytes}` : (bytes / 1024 ** unit).toFixed(1);
  return `${value} ${units[unit]}`;
}
