"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";

const LINKS = [
  { href: "/", label: "대화" },
  { href: "/memory", label: "기억" },
  { href: "/usage", label: "사용량" },
] as const;

export function SiteNav({ isAdmin }: { isAdmin: boolean }) {
  const pathname = usePathname();
  const [proposalCount, setProposalCount] = useState(0);
  useEffect(() => {
    void fetch("/api/memories", { cache: "no-store" })
      .then((response) => response.ok ? response.json() : [])
      .then((memories: { status: string }[]) => setProposalCount(memories.filter((memory) => memory.status === "PROPOSED").length))
      .catch(() => setProposalCount(0));
  }, [pathname]);
  useEffect(() => {
    const update = (event: Event) => setProposalCount((event as CustomEvent<number>).detail);
    window.addEventListener("memory-proposal-count", update);
    return () => window.removeEventListener("memory-proposal-count", update);
  }, []);
  const links = isAdmin
    ? [...LINKS, { href: "/admin/agents", label: "에이전트 관리" }, { href: "/admin/people", label: "사람 관리" }]
    : LINKS;
  return (
    <nav aria-label="주요 화면" className="flex min-w-0 flex-nowrap items-center gap-2 overflow-x-auto whitespace-nowrap sm:gap-3">
      {links.map((link) => {
        const current = link.href === "/" ? pathname === "/" : pathname.startsWith(link.href);
        return (
          <Link
            key={link.href}
            href={link.href}
            aria-current={current ? "page" : undefined}
            className={`shrink-0 rounded-md px-2 py-1.5 sm:px-3 ${current ? "bg-brand text-on-brand" : "text-muted hover:bg-surface-raised hover:text-foreground"}`}
          >
            {link.label}{link.href === "/memory" && proposalCount > 0 ? <span className="ml-1 rounded-full bg-surface px-1.5 py-0.5 text-xs text-foreground" data-testid="memory-proposal-count">{proposalCount}</span> : null}
          </Link>
        );
      })}
    </nav>
  );
}
