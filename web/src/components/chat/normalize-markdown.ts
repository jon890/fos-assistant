function cellsOf(line: string): string[] | null {
  if (/^(?: {4}|\t| {0,3}>)/.test(line)) return null;

  const cells: string[] = [];
  let start = 0;
  let backslashes = 0;
  for (let index = 0; index < line.length; index++) {
    const character = line[index];
    if (character === "|" && backslashes % 2 === 0) {
      cells.push(line.slice(start, index).trim());
      start = index + 1;
    }
    backslashes = character === "\\" ? backslashes + 1 : 0;
  }
  if (cells.length === 0) return null;
  cells.push(line.slice(start).trim());
  if (cells[0] === "") cells.shift();
  if (cells.at(-1) === "") cells.pop();
  return cells.length >= 2 ? cells : null;
}

function isDelimiter(cells: string[]): boolean {
  return cells.every((cell) => /^:?-+:?$/.test(cell));
}

/** 구분 줄이 빠진 표만 보완하고 코드와 원문 줄바꿈은 보존한다. */
export function normalizeMarkdown(source: string): string {
  const lines = source.split(/\r?\n/);
  const newline = source.includes("\r\n") ? "\r\n" : "\n";
  const result: string[] = [];
  let fence: { character: string; length: number } | null = null;
  let previousCells: string[] | null = null;
  let tableColumns: number | null = null;

  for (const line of lines) {
    const containerContent = line
      .replace(/^(?: {0,3}> ?)+/, "")
      .replace(/^ {0,3}(?:[-+*]|\d+[.)]) +/, "");
    const marker = /^ {0,3}(`{3,}|~{3,})(.*)$/.exec(containerContent);
    if (fence) {
      result.push(line);
      if (
        marker &&
        marker[1][0] === fence.character &&
        marker[1].length >= fence.length &&
        marker[2].trim() === ""
      ) {
        fence = null;
      }
      previousCells = null;
      tableColumns = null;
      continue;
    }
    if (marker) {
      fence = { character: marker[1][0], length: marker[1].length };
      result.push(line);
      previousCells = null;
      tableColumns = null;
      continue;
    }

    const cells = cellsOf(line);
    if (tableColumns !== null && cells) {
      result.push(line);
      continue;
    }
    tableColumns = null;
    const headerCells = previousCells;
    const continuesRows =
      cells !== null &&
      headerCells !== null &&
      cells.length === headerCells.length;
    if (continuesRows && !isDelimiter(cells) && !isDelimiter(headerCells)) {
      result.push(`| ${cells.map(() => "---").join(" | ")} |`);
      // 처음 두 행을 확인하면 표로 확정한다. 뒤의 미완성 스트림 행은 판정에 쓰지 않는다.
      previousCells = null;
      tableColumns = cells.length;
      result.push(line);
      continue;
    }
    result.push(line);
    if (continuesRows && isDelimiter(cells)) {
      previousCells = null;
      tableColumns = cells.length;
    } else {
      previousCells = cells;
    }
  }
  return result.join(newline);
}
