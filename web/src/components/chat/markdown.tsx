"use client";

import { memo } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { normalizeMarkdown } from "@/components/chat/normalize-markdown";
import { CodeBlock } from "./code-block";
import { rehypeRevealFade, type TailFade } from "./reveal-fade";

/**
 * 마크다운 글을 그린다. 글이 같고 `fades` 가 없으면 부모가 다시 그려도 다시 읽지 않는다.
 * `fades` 는 막 보이기 시작한 글 끝의 구간이다. 흐르는 답이 새 낱말을 흐릿하게 그릴 때만 준다.
 */
export const Markdown = memo(function Markdown({
  children,
  fades,
}: {
  children: string;
  fades?: TailFade[];
}) {
  const source = normalizeMarkdown(children);
  return (
    <div className="min-w-0 text-sm leading-6">
      <ReactMarkdown
        remarkPlugins={[[remarkGfm, { singleTilde: false }]]}
        rehypePlugins={
          fades && fades.length > 0
            ? [[rehypeRevealFade, { fades, sourceLength: source.length }]]
            : []
        }
        components={{
          a: ({ children: label, ...props }) => (
            <a
              {...props}
              target="_blank"
              rel="noopener noreferrer"
              className="underline underline-offset-2"
            >
              {label}
            </a>
          ),
          table: ({ children: tableChildren, ...props }) => (
            <div className="my-3 max-w-full overflow-x-auto">
              <table
                {...props}
                className="w-max min-w-full border-collapse text-left"
              >
                {tableChildren}
              </table>
            </div>
          ),
          th: ({ children: cell, ...props }) => (
            <th
              {...props}
              className="border border-border bg-muted px-3 py-2 font-semibold"
            >
              {cell}
            </th>
          ),
          td: ({ children: cell, ...props }) => (
            <td {...props} className="border border-border px-3 py-2">
              {cell}
            </td>
          ),
          ul: ({ children: items, ...props }) => (
            <ul {...props} className="my-2 list-disc space-y-1 pl-5">
              {items}
            </ul>
          ),
          ol: ({ children: items, ...props }) => (
            <ol {...props} className="my-2 list-decimal space-y-1 pl-5">
              {items}
            </ol>
          ),
          p: ({ children: paragraph, ...props }) => (
            <p {...props} className="my-2 first:mt-0 last:mb-0">
              {paragraph}
            </p>
          ),
          pre: ({ children: code }) => code,
          code: ({ className, children: source, ...props }) => {
            const language = /language-([^ ]+)/.exec(className ?? "")?.[1];
            const text = String(source).replace(/\n$/, "");
            if (language || String(source).endsWith("\n")) {
              return <CodeBlock code={text} language={language ?? "text"} />;
            }
            return (
              <code
                {...props}
                className="rounded-sm bg-muted px-1 py-0.5 font-mono text-[0.9em]"
              >
                {source}
              </code>
            );
          },
        }}
      >
        {source}
      </ReactMarkdown>
    </div>
  );
});
