"use client";

import { memo, useMemo } from "react";
import ReactMarkdown from "react-markdown";
import { cn } from "cn";
import { normalizeMarkdown } from "@/components/chat/normalize-markdown";
import { MARKDOWN_SYNTAX_PLUGINS } from "@/lib/markdown-syntax";
import {
  DENSITY,
  markdownComponents,
  type MarkdownDensity,
} from "./markdown-components";
import {
  rehypeSoftBreaks,
  rehypeSourceSection,
  remarkOpenFence,
} from "./markdown-plugins";
import { rehypeRevealFade, type TailFade } from "./reveal-fade";

export type { MarkdownDensity };

/**
 * 마크다운 글을 그린다. 글이 같고 `fades` 가 없으면 부모가 다시 그려도 다시 읽지 않는다.
 * `fades` 는 막 보이기 시작한 글 끝의 구간이다. 흐르는 답이 새 낱말을 흐릿하게 그릴 때만 준다.
 * 줄바꿈 한 번은 줄바꿈으로 보이고, 글이 「출처」 인 제목부터 다음 제목 앞까지는 출처 절로 나눠 보인다.
 * `streaming` 이면 닫는 줄이 아직 오지 않은 mermaid 블록을 그리지 않고 코드로 둔다.
 */
export const Markdown = memo(function Markdown({
  children,
  fades,
  density = "comfortable",
  streaming = false,
}: {
  children: string;
  fades?: TailFade[];
  density?: MarkdownDensity;
  streaming?: boolean;
}) {
  const source = normalizeMarkdown(children);
  const components = useMemo(() => markdownComponents(density), [density]);
  return (
    <div className={cn("min-w-0 break-words", DENSITY[density].root)}>
      <ReactMarkdown
        remarkPlugins={[
          ...MARKDOWN_SYNTAX_PLUGINS,
          [remarkOpenFence, { streaming }],
        ]}
        rehypePlugins={
          // 출처 절은 흐림이 제목 글을 span 으로 쪼개기 전에 찾고, 줄바꿈은 흐림이 위치로 구간을 찾은 뒤에 바꾼다.
          fades && fades.length > 0
            ? [
                rehypeSourceSection,
                [rehypeRevealFade, { fades, sourceLength: source.length }],
                rehypeSoftBreaks,
              ]
            : [rehypeSourceSection, rehypeSoftBreaks]
        }
        components={components}
      >
        {source}
      </ReactMarkdown>
    </div>
  );
});
