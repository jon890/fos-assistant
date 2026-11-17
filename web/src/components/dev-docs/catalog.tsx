"use client";

import Link from "next/link";
import { useState } from "react";
import type { DocGroup } from "@/lib/dev-docs/catalog";
import {
  filterCatalog,
  type FilteredEntry,
  type FilteredSection,
} from "@/lib/dev-docs/filter";

function docHref(repoPath: string): string {
  return `/dev-docs/${repoPath.split("/").map(encodeURIComponent).join("/")}`;
}

const LINK_CLASS =
  "block rounded-md px-2 py-1 text-sm hover:bg-accent hover:text-foreground";

function DocLink({
  entry,
  currentPath,
}: {
  entry: FilteredEntry;
  currentPath?: string;
}) {
  const current = entry.path === currentPath;
  return (
    <Link
      href={docHref(entry.path)}
      prefetch={false}
      aria-current={current ? "page" : undefined}
      className={`${LINK_CLASS} ${current ? "bg-accent font-medium text-foreground" : "text-muted-foreground"}`}
    >
      {entry.title}
      {entry.status ? (
        <span className="ml-1 text-xs text-muted-foreground">
          · {entry.status}
        </span>
      ) : null}
    </Link>
  );
}

function EntryRow({
  entry,
  currentPath,
}: {
  entry: FilteredEntry;
  currentPath?: string;
}) {
  if (!entry.children) {
    return (
      <li>
        <DocLink entry={entry} currentPath={currentPath} />
      </li>
    );
  }
  const open =
    entry.expanded ||
    entry.children.some((child) => child.path === currentPath);
  return (
    <li>
      <DocLink entry={entry} currentPath={currentPath} />
      <details open={open} className="ml-2">
        <summary className="cursor-pointer px-2 text-xs text-muted-foreground">
          스펙과 설계
        </summary>
        <ul className="ml-2">
          {entry.children.map((child) => (
            <li key={child.path}>
              <DocLink entry={child} currentPath={currentPath} />
            </li>
          ))}
        </ul>
      </details>
    </li>
  );
}

function SectionList({
  section,
  showLabel,
  currentPath,
}: {
  section: FilteredSection;
  showLabel: boolean;
  currentPath?: string;
}) {
  return (
    <div className="mb-2">
      {showLabel ? (
        <p className="px-2 text-xs font-medium text-muted-foreground">
          {section.label}
        </p>
      ) : null}
      <ul>
        {section.entries.map((entry) => (
          <EntryRow key={entry.path} entry={entry} currentPath={currentPath} />
        ))}
      </ul>
      {section.archived ? (
        <details className="ml-2">
          <summary className="cursor-pointer px-2 text-xs text-muted-foreground">
            보관
          </summary>
          <ul>
            {section.archived.map((entry) => (
              <EntryRow
                key={entry.path}
                entry={entry}
                currentPath={currentPath}
              />
            ))}
          </ul>
        </details>
      ) : null}
    </div>
  );
}

/** 「개발 문서」 의 목록과 찾기 칸이다. 찾기는 받은 목록 안에서만 한다. */
export function DevDocsCatalog({
  groups,
  currentPath,
}: {
  groups: DocGroup[];
  currentPath?: string;
}) {
  const [query, setQuery] = useState("");
  const shown = filterCatalog(groups, query);
  return (
    <nav aria-label="문서 목록" className="flex flex-col gap-3">
      <input
        type="search"
        value={query}
        onChange={(event) => setQuery(event.target.value)}
        aria-label="문서 찾기"
        placeholder="문서 이름"
        className="rounded-md border border-border bg-background px-3 py-2 text-sm"
      />
      {shown.length === 0 ? (
        <p className="px-2 text-sm text-muted-foreground">맞는 문서가 없어요</p>
      ) : (
        shown.map((group) => (
          <section key={group.key} aria-label={group.label}>
            <h2 className="mb-1 px-2 text-sm font-semibold">{group.label}</h2>
            {group.sections.map((section) => (
              <SectionList
                key={section.label}
                section={section}
                showLabel={group.sections.length > 1}
                currentPath={currentPath}
              />
            ))}
          </section>
        ))
      )}
    </nav>
  );
}
