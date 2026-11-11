/**
 * 답 글을 한 번에 얼마만큼 보일지 끊는 규칙이다. 화면 상태를 모르는 순수 함수만 둔다.
 *
 * 끊는 단위는 띄어쓰기 단위 낱말이고, 코드 블록 안은 줄이다. 닫히지 않은 `**`, 인라인 코드의 백틱, 링크 괄호는
 * 닫는 글이 올 때까지 그 앞에서 멈추고, 닫는 글이 오면 열린 곳부터 닫는 곳까지를 한 번에 보인다.
 */

/** 이미 보인 글의 끝에서 코드 블록이 열려 있으면 그 여는 줄의 모양이다. */
export type FenceState = { char: string; length: number } | null;

/** 다음에 보일 덩어리의 끝이다. `line` 이 있으면 코드 블록을 여닫는 줄이거나 코드 블록 안의 줄 하나다. */
export type RevealUnit =
  | { end: number; line?: string }
  /** 보일 덩어리가 아직 다 오지 않았다. 낱말 끝에 공백이 오거나 잠시 멈추면 풀린다. */
  | { hold: "partial" }
  /** 닫히지 않은 기호나 끝나지 않은 코드 줄이 있다. 닫는 글이 오거나 끝 사건이 올 때까지 멈춘다. */
  | { hold: "marker" };

const FENCE_LINE = /^ {0,3}(`{3,}|~{3,})(.*)$/;
const PARAGRAPH_BREAK = /\n[ \t]*\n/g;
/** 낱말 하나를 찾으려고 한 번에 살피는 글자 수다. 공백 없이 이보다 길게 이어지면 거기서 끊는다. */
const LOOKAHEAD = 400;

let segmenter: Intl.Segmenter | null | undefined;

function wordSegmenter(): Intl.Segmenter | null {
  if (segmenter === undefined) {
    segmenter =
      typeof Intl !== "undefined" && typeof Intl.Segmenter === "function"
        ? new Intl.Segmenter("ko", { granularity: "word" })
        : null;
  }
  return segmenter;
}

/** 코드 블록의 여닫는 줄 하나를 만난 뒤의 상태다. */
export function nextFence(fence: FenceState, line: string): FenceState {
  const match = FENCE_LINE.exec(line);
  if (match === null) return fence;
  const marker = match[1];
  if (fence === null) return { char: marker[0], length: marker.length };
  if (
    marker[0] === fence.char &&
    marker.length >= fence.length &&
    match[2].trim() === ""
  )
    return null;
  return fence;
}

/** `end` 까지 보였을 때 코드 블록이 열려 있는지 처음부터 센다. 줄이 끝난 곳까지만 본다. */
export function fenceAt(text: string, end: number): FenceState {
  let fence: FenceState = null;
  let start = 0;
  while (start < end) {
    const newline = text.indexOf("\n", start);
    if (newline === -1 || newline >= end) break;
    fence = nextFence(fence, text.slice(start, newline));
    start = newline + 1;
  }
  return fence;
}

type Piece = { index: number; text: string; wordLike: boolean };

function piecesOf(slice: string): Piece[] {
  const words = wordSegmenter();
  if (words !== null) {
    return Array.from(words.segment(slice), (part) => ({
      index: part.index,
      text: part.segment,
      wordLike: part.isWordLike === true,
    }));
  }
  return Array.from(slice.matchAll(/\s+|\S+/g), (part) => ({
    index: part.index,
    text: part[0],
    wordLike: false,
  }));
}

/**
 * 낱말 하나의 끝을 찾는다. 공백 뒤에서 끊고 줄바꿈 뒤에서도 끊는다.
 * 공백 없이 낱말끼리 붙어 있는 글(한자, 가나)은 낱말 경계에서 끊는다.
 * `complete` 가 거짓이면 글이 거기서 끝나 뒤에 더 올 수 있다.
 */
function wordEnd(slice: string): { end: number; complete: boolean } {
  let sawSpace = false;
  let previousWord = false;
  for (const piece of piecesOf(slice)) {
    const space = /^\s+$/.test(piece.text);
    if (sawSpace && !space) return { end: piece.index, complete: true };
    if (space) {
      const newline = piece.text.indexOf("\n");
      if (newline >= 0)
        return { end: piece.index + newline + 1, complete: true };
      sawSpace = true;
      previousWord = false;
      continue;
    }
    if (piece.wordLike && previousWord)
      return { end: piece.index, complete: true };
    previousWord = piece.wordLike;
  }
  return { end: slice.length, complete: sawSpace };
}

function paragraphBreakAfter(text: string, from: number): number {
  PARAGRAPH_BREAK.lastIndex = from;
  const match = PARAGRAPH_BREAK.exec(text);
  return match === null ? -1 : match.index;
}

type Marker =
  | { state: "closed"; end: number }
  | { state: "open" }
  | { state: "literal"; width: number };

/** 닫는 글을 같은 문단 안에서 찾았는지에 따라 결과를 정한다. 문단이 끝났는데 없으면 글자 그대로 쓴 것이다. */
function markerResult(
  text: string,
  at: number,
  width: number,
  closeEnd: number,
): Marker {
  const paragraph = paragraphBreakAfter(text, at);
  if (closeEnd !== -1 && (paragraph === -1 || closeEnd <= paragraph))
    return { state: "closed", end: closeEnd };
  return paragraph === -1 ? { state: "open" } : { state: "literal", width };
}

function markerAt(text: string, at: number): Marker | null {
  const character = text[at];
  if (character === "*" && text[at + 1] === "*") {
    const close = text.indexOf("**", at + 2);
    return markerResult(text, at, 2, close === -1 ? -1 : close + 2);
  }
  if (character === "`") {
    let width = 1;
    while (text[at + width] === "`") width++;
    let search = at + width;
    while (search < text.length) {
      const start = text.indexOf("`", search);
      if (start === -1) break;
      let run = 1;
      while (text[start + run] === "`") run++;
      if (run === width) return markerResult(text, at, width, start + run);
      search = start + run;
    }
    return markerResult(text, at, width, -1);
  }
  if (character === "[") {
    const bracket = text.indexOf("]", at + 1);
    if (bracket === -1) return markerResult(text, at, 1, -1);
    // 닫는 대괄호 바로 뒤가 아직 안 왔으면 링크인지 알 수 없다.
    if (bracket + 1 >= text.length) return { state: "open" };
    if (text[bracket + 1] !== "(") return { state: "literal", width: 1 };
    const close = text.indexOf(")", bracket + 2);
    return markerResult(text, at, 1, close === -1 ? -1 : close + 1);
  }
  return null;
}

/** 낱말 끝 `end` 가 열린 기호를 가르면 닫는 곳까지 늘리고, 닫는 글이 아직 없으면 그 앞에서 자른다. */
function reconcileMarkers(
  text: string,
  from: number,
  end: number,
  finishing: boolean,
): number | null {
  let reach = end;
  let at = from;
  while (at < reach) {
    const marker = markerAt(text, at);
    if (marker === null) {
      at++;
    } else if (marker.state === "closed") {
      reach = Math.max(reach, marker.end);
      at = marker.end;
    } else if (marker.state === "literal" || finishing) {
      at += marker.state === "literal" ? marker.width : 1;
    } else {
      return at > from ? at : null;
    }
  }
  return reach;
}

/**
 * `from` 에서 시작하는 다음 덩어리의 끝을 정한다.
 * `allowPartial` 은 끝 사건이 왔거나 글이 잠시 멈춰, 뒤에 공백이 없는 마지막 낱말도 보여도 되는 때다.
 * `finishing` 은 끝 사건이 온 뒤라 닫히지 않은 기호도 더는 기다리지 않는다.
 */
export function nextUnit(
  text: string,
  from: number,
  fence: FenceState,
  allowPartial: boolean,
  finishing: boolean,
): RevealUnit {
  if (from === 0 || text[from - 1] === "\n") {
    const newline = text.indexOf("\n", from);
    const line = text.slice(from, newline === -1 ? text.length : newline);
    if (fence !== null || FENCE_LINE.test(line)) {
      if (newline !== -1) return { end: newline + 1, line };
      // 줄이 끝나지 않은 코드는 잠시 멈춰도 보이지 않고, 줄이 끝나거나 끝 사건이 올 때까지 기다린다.
      return finishing ? { end: text.length, line } : { hold: "marker" };
    }
  }
  let limit = Math.min(text.length, from + LOOKAHEAD);
  if (limit < text.length && /[\ud800-\udbff]/.test(text[limit - 1])) limit++;
  const word = wordEnd(text.slice(from, limit));
  if (!word.complete && limit === text.length && !allowPartial)
    return { hold: "partial" };
  const end = reconcileMarkers(text, from, from + word.end, finishing);
  return end === null ? { hold: "marker" } : { end };
}
