/**
 * 로그인 화면의 포인터와 키를 `POST /api/browser/screen/input` 본문으로 바꾼다.
 * 본문 모양은 아래 `ScreenInput` 이 갖는다. 서버는 같은 칸과 범위를 `UserBrowserDtos.ScreenInputRequest` 에서 검사하므로
 * 상한을 바꾸면 함께 고친다.
 */

export type ScreenInput =
  | { type: "mouse"; action: "down" | "up" | "move"; x: number; y: number }
  | { type: "wheel"; x: number; y: number; deltaY: number }
  | { type: "key"; key: string }
  | { type: "text"; text: string }
  | { type: "navigate"; url: string }
  | { type: "back" }
  | { type: "reload" }
  | { type: "tab"; id: string }
  | { type: "resize"; width: number; height: number };

type Box = { left: number; top: number; width: number; height: number };
type Point = { clientX: number; clientY: number };

/** 서버가 받는 특수 키다. 나머지 글자는 `text` 로 보낸다. */
const KEYS = new Set([
  "Enter",
  "Backspace",
  "Tab",
  "Escape",
  "ArrowLeft",
  "ArrowUp",
  "ArrowRight",
  "ArrowDown",
  "Delete",
]);

/** 휠 한 번의 `deltaY` 상한이다. */
const MAX_WHEEL = 2000;

/** 터치가 이만큼(CSS 픽셀) 넘게 움직이면 누르기가 아니라 끌기다. */
export const TAP_SLOP = 10;

/** 한 번에 보내는 글자 수 상한이다. */
export const MAX_TEXT = 500;

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

/** 그림 안의 비율 좌표다. 그림 밖은 가장자리로 자른다. */
export function ratio(point: Point, box: Box): { x: number; y: number } {
  return {
    x: box.width > 0 ? clamp((point.clientX - box.left) / box.width, 0, 1) : 0,
    y: box.height > 0 ? clamp((point.clientY - box.top) / box.height, 0, 1) : 0,
  };
}

export function mouse(
  action: "down" | "up" | "move",
  point: Point,
  box: Box,
): ScreenInput {
  return { type: "mouse", action, ...ratio(point, box) };
}

/** 마우스 휠이다. 화면의 휠 값을 상한으로 자른다. */
export function wheel(point: Point, box: Box, deltaY: number): ScreenInput {
  return {
    type: "wheel",
    ...ratio(point, box),
    deltaY: Math.round(clamp(deltaY, -MAX_WHEEL, MAX_WHEEL)),
  };
}

/** 휠 값을 CSS 픽셀로 바꾼다. 줄 단위면 16배, 쪽 단위면 그림 높이배다. */
export function wheelDelta(
  deltaY: number,
  deltaMode: number,
  pageHeight: number,
): number {
  if (deltaMode === 1) return deltaY * 16;
  if (deltaMode === 2) return deltaY * pageHeight;
  return deltaY;
}

/**
 * 터치 끌기를 휠로 바꾼다. 위로 끌면 아래로 굴린다.
 * 그림에서 끈 거리를 프레임의 CSS 픽셀로 늘린다.
 */
export function dragWheel(
  start: Point,
  fromY: number,
  toY: number,
  box: Box,
  frameHeight: number,
): ScreenInput {
  const scale =
    box.height > 0 && frameHeight > 0 ? frameHeight / box.height : 1;
  return wheel(start, box, (fromY - toY) * scale);
}

/** 터치를 움직임 없이 뗐는가. */
export function isTap(start: Point, end: Point): boolean {
  return (
    Math.hypot(end.clientX - start.clientX, end.clientY - start.clientY) <=
    TAP_SLOP
  );
}

/** 특수 키면 입력이고 아니면 `null` 이다. */
export function screenKey(key: string): ScreenInput | null {
  return KEYS.has(key) ? { type: "key", key } : null;
}

/**
 * 글자를 상한 길이(UTF-16 단위, 서버의 `String.length()` 와 같다)로 나눈다.
 * 이모지 같은 서로게이트 쌍은 쪼개지 않는다. 빈 글자는 보내지 않는다.
 */
export function textInputs(text: string): ScreenInput[] {
  const inputs: ScreenInput[] = [];
  let chunk = "";
  for (const char of text) {
    if (chunk.length + char.length > MAX_TEXT) {
      inputs.push({ type: "text", text: chunk });
      chunk = "";
    }
    chunk += char;
  }
  if (chunk) inputs.push({ type: "text", text: chunk });
  return inputs;
}

/** 그림 칸의 폭으로 정한 브라우저 크기다. 높이는 폭의 1.5배이고 둘 다 서버가 받는 범위로 자른다. */
export function resizeFor(width: number): ScreenInput {
  const w = Math.round(clamp(width, 320, 1600));
  return {
    type: "resize",
    width: w,
    height: Math.round(clamp(w * 1.5, 320, 2000)),
  };
}

/**
 * 화면이 열 수 있는 주소면 `new URL` 로 정규화한 주소이고 아니면 `null` 이다.
 * `http` 와 `https` 이고 host 가 있어야 하며 2048자를 넘지 않아야 한다.
 */
export function webUrl(value: string | null | undefined): string | null {
  if (!value) return null;
  try {
    const url = new URL(value);
    const web =
      (url.protocol === "http:" || url.protocol === "https:") &&
      url.hostname !== "";
    return web && url.href.length <= 2048 ? url.href : null;
  } catch {
    return null;
  }
}
