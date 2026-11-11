/**
 * 새로 보인 낱말을 흐릿하게 그리는 rehype 플러그인이다.
 *
 * 마크다운을 다시 그릴 때마다 글 끝에서 센 구간(`TailFade`)에 걸친 글자만 `span` 으로 감싸 투명도를 준다.
 * 구간은 끝에서 센 거리라 앞에 줄이 끼어도 어긋나지 않는다. 코드는 글 그대로 다뤄야 하므로 건드리지 않는다.
 */

/** 글 끝에서 센 구간이다. 구간의 처음과 끝이 글 끝에서 몇 글자 앞인지 적고, `progress` 는 0 에서 1 로 또렷해진다. */
export type TailFade = {
  startFromEnd: number;
  endFromEnd: number;
  progress: number;
};

type HastNode = {
  type: string;
  value?: string;
  tagName?: string;
  properties?: Record<string, unknown>;
  children?: HastNode[];
  position?: { start: { offset?: number }; end: { offset?: number } };
};

export type RevealFadeOptions = {
  fades: TailFade[];
  /** 마크다운 읽기에 넘긴 글의 길이다. 글 끝의 위치를 이 길이로 잰다. */
  sourceLength: number;
};

const SKIPPED = new Set(["pre", "code"]);

/** 막 보인 글은 투명하고 흐리며 또렷해질수록 느리게 가까워진다. */
export function fadeStyle(progress: number): string {
  const eased = 1 - (1 - progress) ** 2;
  const opacity = 0.15 + 0.85 * eased;
  const blur = (1 - eased) * 1.5;
  return `opacity:${opacity.toFixed(2)};filter:blur(${blur.toFixed(2)}px)`;
}

function pieces(node: HastNode, fades: TailFade[], length: number): HastNode[] {
  const start = node.position?.start.offset;
  const end = node.position?.end.offset;
  const value = node.value ?? "";
  if (start === undefined || end === undefined || value.length === 0)
    return [node];
  const result: HastNode[] = [];
  let cursor = 0;
  const ranges = fades
    .map((fade) => ({
      from: Math.max(0, length - fade.startFromEnd - start),
      to: Math.min(value.length, length - fade.endFromEnd - start),
      progress: fade.progress,
    }))
    .filter((range) => range.to > range.from && range.from < value.length)
    .sort((a, b) => a.from - b.from);
  for (const range of ranges) {
    const from = Math.max(range.from, cursor);
    if (range.to <= from) continue;
    if (from > cursor)
      result.push({ type: "text", value: value.slice(cursor, from) });
    result.push({
      type: "element",
      tagName: "span",
      properties: { style: fadeStyle(range.progress) },
      children: [{ type: "text", value: value.slice(from, range.to) }],
    });
    cursor = range.to;
  }
  if (cursor === 0) return [node];
  if (cursor < value.length)
    result.push({ type: "text", value: value.slice(cursor) });
  return result;
}

function walk(node: HastNode, options: RevealFadeOptions) {
  if (node.children === undefined) return;
  if (node.tagName !== undefined && SKIPPED.has(node.tagName)) return;
  node.children = node.children.flatMap((child) => {
    if (child.type === "text")
      return pieces(child, options.fades, options.sourceLength);
    walk(child, options);
    return [child];
  });
}

/** `rehypePlugins` 에 `[rehypeRevealFade, 옵션]` 으로 넘긴다. */
export function rehypeRevealFade(options: RevealFadeOptions) {
  return (tree: HastNode) => {
    if (options.fades.length > 0) walk(tree, options);
  };
}
