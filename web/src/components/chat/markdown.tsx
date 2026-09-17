"use client";

import { useEffect, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

type HighlightedToken = {
  content: string;
  className: string;
};

type HighlightedLine = HighlightedToken[];
type Tokenizer = (code: string, language: string) => HighlightedLine[];

let tokenizerPromise: Promise<Tokenizer> | null = null;

const SUPPORTED_LANGUAGES = new Set([
  "bash",
  "java",
  "javascript",
  "json",
  "markdown",
  "python",
  "sql",
  "typescript",
]);

const LANGUAGE_ALIASES: Record<string, string> = {
  js: "javascript",
  jsx: "javascript",
  md: "markdown",
  py: "python",
  sh: "bash",
  shell: "bash",
  ts: "typescript",
  tsx: "typescript",
};

function tokenClass(scopes: string[]): string {
  if (scopes.some((scope) => scope.includes("comment"))) return "text-muted italic";
  if (scopes.some((scope) => scope.includes("string") || scope.includes("constant.numeric"))) {
    return "text-muted";
  }
  if (scopes.some((scope) => scope.includes("invalid"))) return "underline decoration-wavy";
  return "text-foreground";
}

function loadTokenizer(): Promise<Tokenizer> {
  if (tokenizerPromise) return tokenizerPromise;
  tokenizerPromise = Promise.all([
    import("shiki/core"),
    import("shiki/engine/javascript"),
    import("shiki/themes/github-dark.mjs"),
    import("shiki/langs/bash.mjs"),
    import("shiki/langs/java.mjs"),
    import("shiki/langs/javascript.mjs"),
    import("shiki/langs/json.mjs"),
    import("shiki/langs/markdown.mjs"),
    import("shiki/langs/python.mjs"),
    import("shiki/langs/sql.mjs"),
    import("shiki/langs/typescript.mjs"),
  ]).then(async ([{ createHighlighterCore }, { createJavaScriptRegexEngine }, theme, ...languages]) => {
    const highlighter = await createHighlighterCore({
      engine: createJavaScriptRegexEngine(),
      themes: [theme.default],
      langs: languages.map((loaded) => loaded.default),
    });
    return (code: string, language: string) => {
      const result = highlighter.codeToTokens(code, {
        lang: language,
        theme: "github-dark",
        includeExplanation: "scopeName",
      });
      return result.tokens.map((line) =>
        line.map((token) => ({
          content: token.content,
          className: tokenClass(
            token.explanation?.flatMap((entry) => entry.scopes.map((scope) => scope.scopeName)) ?? [],
          ),
        })),
      );
    };
  });
  return tokenizerPromise;
}

async function highlight(code: string, requestedLanguage: string): Promise<HighlightedLine[]> {
  const language = LANGUAGE_ALIASES[requestedLanguage] ?? requestedLanguage;
  if (!SUPPORTED_LANGUAGES.has(language)) return [];

  const tokenize = await loadTokenizer();
  return tokenize(code, language);
}

function CodeBlock({ code, language }: { code: string; language: string }) {
  const [lines, setLines] = useState<HighlightedLine[]>([]);

  useEffect(() => {
    let active = true;
    setLines([]);
    void highlight(code, language).then((highlighted) => {
      if (active) setLines(highlighted);
    });
    return () => {
      active = false;
    };
  }, [code, language]);

  return (
    <pre className="my-3 overflow-x-auto rounded-md bg-surface p-3 font-mono text-sm leading-6">
      <code className="block min-w-max">
        {lines.length > 0
          ? lines.map((line, lineIndex) => (
              <span key={lineIndex} className="block min-h-6">
                {line.map((token, tokenIndex) => (
                  <span key={tokenIndex} className={token.className}>
                    {token.content}
                  </span>
                ))}
              </span>
            ))
          : code}
      </code>
    </pre>
  );
}

export function Markdown({ children }: { children: string }) {
  return (
    <div className="min-w-0 text-sm leading-6">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
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
              <table {...props} className="w-max min-w-full border-collapse text-left">
                {tableChildren}
              </table>
            </div>
          ),
          th: ({ children: cell, ...props }) => (
            <th {...props} className="border border-border bg-surface px-3 py-2 font-semibold">
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
              <code {...props} className="rounded bg-surface px-1 py-0.5 font-mono text-[0.9em]">
                {source}
              </code>
            );
          },
        }}
      >
        {children}
      </ReactMarkdown>
    </div>
  );
}
