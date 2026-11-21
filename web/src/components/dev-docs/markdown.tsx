"use client";

import Link from "next/link";
import { useEffect, useMemo, type ReactNode } from "react";
import ReactMarkdown, { type Components } from "react-markdown";
import { rehypeHeadingIds } from "@/lib/dev-docs/headings";
import { resolveDocLink } from "@/lib/dev-docs/links";
import { MermaidBlock } from "@/components/ui/mermaid-block";
import { MARKDOWN_SYNTAX_PLUGINS } from "@/lib/markdown-syntax";

type HeadingTag = "h1" | "h2" | "h3" | "h4" | "h5" | "h6";

const HEADING_CLASS: Record<HeadingTag, string> = {
  h1: "mt-2 mb-4 text-2xl font-semibold",
  h2: "mt-8 mb-3 border-b border-border pb-1 text-xl font-semibold",
  h3: "mt-6 mb-2 text-lg font-semibold",
  h4: "mt-4 mb-2 font-semibold",
  h5: "mt-4 mb-2 text-sm font-semibold",
  h6: "mt-4 mb-2 text-sm text-muted-foreground",
};

/** 제목 id 는 rehypeHeadingIds 가 붙인 값을 그대로 쓴다. */
function heading(Tag: HeadingTag, id: string | undefined, children: ReactNode) {
  return (
    <Tag id={id} className={HEADING_CLASS[Tag]}>
      {children}
    </Tag>
  );
}

function docComponents(
  fromPath: string,
  knownSet: ReadonlySet<string>,
  commit: string | null,
): Components {
  return {
    h1: ({ id, children }) => heading("h1", id, children),
    h2: ({ id, children }) => heading("h2", id, children),
    h3: ({ id, children }) => heading("h3", id, children),
    h4: ({ id, children }) => heading("h4", id, children),
    h5: ({ id, children }) => heading("h5", id, children),
    h6: ({ id, children }) => heading("h6", id, children),
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
        if (language === "mermaid") return <MermaidBlock source={text} />;
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
  };
}

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
  // 부모가 다시 그려도 제목과 그림 블록이 다시 마운트되지 않게 부품 묶음을 고정한다.
  const components = useMemo(
    () => docComponents(fromPath, knownSet, commit),
    [fromPath, knownSet, commit],
  );

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

  return (
    <article className="min-w-0 text-sm leading-6">
      <ReactMarkdown
        remarkPlugins={MARKDOWN_SYNTAX_PLUGINS}
        rehypePlugins={[rehypeHeadingIds]}
        components={components}
      >
        {markdown}
      </ReactMarkdown>
    </article>
  );
}
