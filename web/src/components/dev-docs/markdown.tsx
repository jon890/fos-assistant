"use client";

import Link from "next/link";
import { useEffect, useMemo, type ReactNode } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { createHeadingIds, headingText } from "@/lib/dev-docs/headings";
import { resolveDocLink } from "@/lib/dev-docs/links";

type HeadingTag = "h1" | "h2" | "h3" | "h4" | "h5" | "h6";

const HEADING_CLASS: Record<HeadingTag, string> = {
  h1: "mt-2 mb-4 text-2xl font-semibold",
  h2: "mt-8 mb-3 border-b border-border pb-1 text-xl font-semibold",
  h3: "mt-6 mb-2 text-lg font-semibold",
  h4: "mt-4 mb-2 font-semibold",
  h5: "mt-4 mb-2 text-sm font-semibold",
  h6: "mt-4 mb-2 text-sm text-muted-foreground",
};

/**
 * 개발 문서 본문이다. react-markdown 의 기본값(HTML 을 그리지 않음, 기본 주소 검사)을 바꾸지 않는다(rule-57).
 * 링크는 목록 안이면 화면 안, 밖이면 같은 커밋의 GitHub 로 보낸다. 그림은 그리지 않고 링크로 바꾼다.
 * 채팅의 Markdown 은 모든 링크를 새 탭으로 보내고 흐르는 답의 효과가 있어 따로 둔다(design.md 의 DECISION3).
 */
export function DevDocMarkdown({
  markdown,
  fromPath,
  known,
  commit,
}: {
  markdown: string;
  fromPath: string;
  known: string[];
  commit: string | null;
}) {
  const knownSet = useMemo(() => new Set(known), [known]);
  // 문서가 바뀔 때만 새로 시작한다. 같은 문서를 다시 그릴 때 -1 이 늘지 않게 그릴 때마다 새로 만든다.
  const headingId = createHeadingIds();

  useEffect(() => {
    if (!location.hash) return;
    let id: string;
    try {
      id = decodeURIComponent(location.hash.slice(1));
    } catch {
      return;
    }
    document.getElementById(id)?.scrollIntoView();
  }, [markdown]);

  const heading = (Tag: HeadingTag, children: ReactNode) => (
    <Tag id={headingId(headingText(children))} className={HEADING_CLASS[Tag]}>
      {children}
    </Tag>
  );

  return (
    <article className="min-w-0 text-sm leading-6">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          h1: ({ children }) => heading("h1", children),
          h2: ({ children }) => heading("h2", children),
          h3: ({ children }) => heading("h3", children),
          h4: ({ children }) => heading("h4", children),
          h5: ({ children }) => heading("h5", children),
          h6: ({ children }) => heading("h6", children),
          a: ({ href, children }) => {
            const link = resolveDocLink(href ?? "", fromPath, knownSet, commit);
            const className = "underline underline-offset-2";
            if (link.kind === "blocked") return <span>{children}</span>;
            if (link.kind === "anchor")
              return (
                <a href={`#${link.hash}`} className={className}>
                  {children}
                </a>
              );
            if (link.kind === "internal")
              return (
                <Link href={link.href} prefetch={false} className={className}>
                  {children}
                </Link>
              );
            return (
              <a
                href={link.href}
                target="_blank"
                rel="noopener noreferrer"
                className={className}
              >
                {children}
              </a>
            );
          },
          img: ({ src, alt }) => {
            const link =
              typeof src === "string"
                ? resolveDocLink(src, fromPath, knownSet, commit)
                : ({ kind: "blocked" } as const);
            const label = `그림: ${alt || "이름 없음"}`;
            if (link.kind === "blocked" || link.kind === "anchor")
              return <span>{label}</span>;
            return (
              <a
                href={link.href}
                target="_blank"
                rel="noopener noreferrer"
                className="underline underline-offset-2"
              >
                {label}
              </a>
            );
          },
          table: ({ children }) => (
            <div className="my-3 max-w-full overflow-x-auto">
              <table className="w-max min-w-full border-collapse text-left">
                {children}
              </table>
            </div>
          ),
          th: ({ children }) => (
            <th className="border border-border bg-muted px-3 py-2 font-semibold">
              {children}
            </th>
          ),
          td: ({ children }) => (
            <td className="border border-border px-3 py-2">{children}</td>
          ),
          ul: ({ children }) => (
            <ul className="my-2 list-disc space-y-1 pl-5">{children}</ul>
          ),
          ol: ({ children }) => (
            <ol className="my-2 list-decimal space-y-1 pl-5">{children}</ol>
          ),
          p: ({ children }) => (
            <p className="my-2 first:mt-0 last:mb-0">{children}</p>
          ),
          blockquote: ({ children }) => (
            <blockquote className="my-2 border-l-2 border-border pl-3 text-muted-foreground">
              {children}
            </blockquote>
          ),
          pre: ({ children }) => children,
          code: ({ className, children }) => {
            const language = /language-([^ ]+)/.exec(className ?? "")?.[1];
            const source = String(children);
            if (language || source.endsWith("\n")) {
              const text = source.replace(/\n$/, "");
              return (
                <pre className="my-3 overflow-x-auto rounded-md border border-border bg-muted p-3 font-mono text-xs leading-5">
                  <code>{text}</code>
                </pre>
              );
            }
            return (
              <code className="rounded-sm bg-muted px-1 py-0.5 font-mono text-[0.9em]">
                {children}
              </code>
            );
          },
        }}
      >
        {markdown}
      </ReactMarkdown>
    </article>
  );
}
