/**
 * 화면 색을 비교하는 검사가 함께 쓰는 계산이다.
 *
 * <p>spec 파일끼리 import 하면 그 파일의 검사가 두 번 등록되므로 계산만 여기에 둔다.
 */

/** `#rgb`, `#rrggbb`, `rgb(...)` 를 세 채널 값으로 읽는다. */
export function parseRgb(color: string): [number, number, number] {
  if (/^#[\da-f]{3}$/i.test(color)) {
    return color.slice(1).split("").map((channel) => Number.parseInt(channel.repeat(2), 16)) as [number, number, number];
  }
  if (/^#[\da-f]{6}$/i.test(color)) {
    return color.slice(1).match(/.{2}/g)!.map((channel) => Number.parseInt(channel, 16)) as [number, number, number];
  }
  const channels = color.match(/\d+(?:\.\d+)?/g)?.slice(0, 3).map(Number);
  if (!channels || channels.length !== 3) throw new Error(`RGB 색을 읽지 못했다: ${color}`);
  return channels as [number, number, number];
}

function luminance(color: string): number {
  const [red, green, blue] = parseRgb(color).map((channel) => {
    const value = channel / 255;
    return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
}

/** WCAG 대비율이다. 본문 글자는 4.5 이상이어야 한다. */
export function contrast(foreground: string, background: string): number {
  const values = [luminance(foreground), luminance(background)].sort((left, right) => right - left);
  return (values[0] + 0.05) / (values[1] + 0.05);
}
