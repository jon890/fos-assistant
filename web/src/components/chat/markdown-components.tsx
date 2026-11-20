"use client";

import type { Components } from "react-markdown";
import { cn } from "cn";
import { MermaidBlock } from "@/components/ui/mermaid-block";
import { CodeBlock } from "./code-block";
import { SOURCE_SECTION_CLASS } from "./markdown-plugins";

/**
 * 글자와 간격의 묶음이다. `comfortable` 은 대화 답과 스킬 미리보기, `compact` 는 작업 과정 블록이 쓴다.
 * 제목, 표, 인용, 구분선, 링크, 출처 절의 모양은 두 묶음이 같고 크기와 간격만 다르다(spec.md 의 REQ18, DECISION29).
 */
export const DENSITY = {
  comfortable: {
    root: "text-answer",
    p: "my-3",
    h1: "mt-6 mb-2 text-xl",
    h2: "mt-6 mb-2 text-lg",
    h3: "mt-5 mb-1.5 text-base",
    h4: "mt-4 mb-1 text-answer",
    list: "my-2.5 space-y-1.5 pl-6",
    table: "my-3.5",
    blockquote: "my-3",
    hr: "my-5",
    sources: "mt-6 pt-3",
  },
  compact: {
    root: "text-sm leading-6",
    p: "my-2",
    h1: "mt-3 mb-1 text-base",
    h2: "mt-3 mb-1 text-base",
    h3: "mt-2 mb-1 text-sm",
    h4: "mt-2 mb-1 text-sm",
    list: "my-2 space-y-1 pl-5",
    table: "my-3",
    blockquote: "my-2",
    hr: "my-3",
    sources: "mt-3 pt-2",
  },
} as const;

export type MarkdownDensity = keyof typeof DENSITY;

/**
 * 부품 묶음을 만든다. 묶음이 바뀌면 react-markdown 이 그 아래를 다시 붙이므로 `density` 가 같으면 같은 묶음을 쓴다.
 * 흐르는지는 `remarkOpenFence` 가 `data-open` 으로 알린다. 묶음에 넣으면 답이 끝날 때 그림이 다시 붙어 한 번 튄다.
 * 흐르는 답은 글자마다 다시 그려져, 묶음이 바뀌면 mermaid 그림이 매번 처음부터 다시 그려진다.
 */
export function markdownComponents(density: MarkdownDensity): Components {
  const size = DENSITY[density];
  const heading = "font-semibold leading-snug first:mt-0";
  return {
    a: ({ children: label, ...props }) => (
      <a
        {...props}
        target="_blank"
        rel="noopener noreferrer"
        className="text-info underline-offset-2 hover:underline"
      >
        {label}
      </a>
    ),
    h1: ({ children: text, ...props }) => (
      <h1 {...props} className={cn(heading, size.h1)}>
        {text}
      </h1>
    ),
    h2: ({ children: text, ...props }) => (
      <h2 {...props} className={cn(heading, size.h2)}>
        {text}
      </h2>
    ),
    h3: ({ children: text, ...props }) => (
      <h3 {...props} className={cn(heading, size.h3)}>
        {text}
      </h3>
    ),
    h4: ({ children: text, ...props }) => (
      <h4 {...props} className={cn(heading, size.h4)}>
        {text}
      </h4>
    ),
    h5: ({ children: text, ...props }) => (
      <h5 {...props} className={cn(heading, size.h4)}>
        {text}
      </h5>
    ),
    h6: ({ children: text, ...props }) => (
      <h6 {...props} className={cn(heading, size.h4)}>
        {text}
      </h6>
    ),
    strong: ({ children: text, ...props }) => (
      <strong {...props} className="font-semibold">
        {text}
      </strong>
    ),
    blockquote: ({ children: quote, ...props }) => (
      <blockquote
        {...props}
        className={cn(
          "border-l-2 border-border pl-3 text-muted-foreground",
          size.blockquote,
        )}
      >
        {quote}
      </blockquote>
    ),
    hr: ({ ...props }) => (
      <hr {...props} className={cn("border-border", size.hr)} />
    ),
    section: ({ children: part, className, ...props }) => (
      <section
        {...props}
        className={cn(
          className,
          className?.includes(SOURCE_SECTION_CLASS) &&
            "border-t border-border text-sm leading-6 text-muted-foreground [&_h1]:mt-0 [&_h1]:text-sm [&_h2]:mt-0 [&_h2]:text-sm [&_h3]:mt-0 [&_h3]:text-sm [&_h4]:mt-0 [&_h4]:text-sm",
          className?.includes(SOURCE_SECTION_CLASS) && size.sources,
        )}
      >
        {part}
      </section>
    ),
    table: ({ children: tableChildren, ...props }) => (
      <div
        className={cn(
          "max-w-full overflow-x-auto rounded-md border border-border",
          size.table,
        )}
      >
        <table
          {...props}
          className="w-max min-w-full border-collapse text-left text-sm [&_tr:last-child>td]:border-b-0"
        >
          {tableChildren}
        </table>
      </div>
    ),
    th: ({ children: cell, ...props }) => (
      <th
        {...props}
        className="border-b border-border bg-muted px-3 py-2 font-semibold"
      >
        {cell}
      </th>
    ),
    td: ({ children: cell, ...props }) => (
      <td {...props} className="border-b border-border px-3 py-2">
        {cell}
      </td>
    ),
    ul: ({ children: items, ...props }) => (
      <ul
        {...props}
        className={cn("list-disc marker:text-muted-foreground", size.list)}
      >
        {items}
      </ul>
    ),
    ol: ({ children: items, ...props }) => (
      <ol
        {...props}
        className={cn("list-decimal marker:text-muted-foreground", size.list)}
      >
        {items}
      </ol>
    ),
    p: ({ children: paragraph, ...props }) => (
      <p {...props} className={cn("first:mt-0 last:mb-0", size.p)}>
        {paragraph}
      </p>
    ),
    pre: ({ children: code }) => code,
    code: ({ className, children: code, ...props }) => {
      const language = /language-([^ ]+)/.exec(className ?? "")?.[1];
      const text = String(code).replace(/\n$/, "");
      const open = "data-open" in props;
      // 흐르는 중에 닫는 줄이 오지 않은 블록은 매 글자 그리기에 실패하므로 코드로 둔다.
      if (language === "mermaid" && !open) {
        return <MermaidBlock source={text} />;
      }
      if (language || String(code).endsWith("\n")) {
        return <CodeBlock code={text} language={language ?? "text"} />;
      }
      return (
        <code
          {...props}
          className="rounded-sm bg-muted px-1 py-0.5 font-mono text-[0.9em]"
        >
          {code}
        </code>
      );
    },
  };
}
