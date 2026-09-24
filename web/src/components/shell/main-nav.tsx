"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";

const LINKS = [
  { href: "/agents", label: "에이전트" },
  { href: "/memory", label: "기억" },
  { href: "/usage", label: "사용량" },
] as const;

export function MainNav({ isAdmin, onNavigate }: { isAdmin: boolean; onNavigate(): void }) {
  const pathname = usePathname();
  const [proposalCount, setProposalCount] = useState(0);
  useEffect(() => {
    void fetch("/api/memories", { cache: "no-store" })
      .then((response) => response.ok ? response.json() : [])
      .then((memories: { status: string }[]) =>
        setProposalCount(memories.filter((memory) => memory.status === "PROPOSED").length))
      .catch(() => setProposalCount(0));
  }, [pathname]);
  useEffect(() => {
    const update = (event: Event) => setProposalCount((event as CustomEvent<number>).detail);
    window.addEventListener("memory-proposal-count", update);
    return () => window.removeEventListener("memory-proposal-count", update);
  }, []);

  const links = isAdmin
    ? [...LINKS, { href: "/admin/agents", label: "에이전트 관리" },
      { href: "/admin/people", label: "사람 관리" }]
    : LINKS;
  return (
    <nav aria-label="주요 화면" className="flex flex-col gap-1">
      {links.map((link) => (
        <Link key={link.href} href={link.href} onClick={onNavigate}
          aria-current={pathname === link.href || pathname.startsWith(`${link.href}/`) ? "page" : undefined}
          className={`rounded-md px-3 py-2 text-sm hover:bg-surface-raised hover:text-foreground ${
            pathname === link.href || pathname.startsWith(`${link.href}/`)
              ? "bg-surface-raised font-medium text-foreground" : "text-muted"
          }`}
        >
          {link.label}{link.href === "/memory" && proposalCount > 0 ? (
            <span className="ml-2 rounded-full bg-surface px-1.5 py-0.5 text-xs text-foreground"
              data-testid="memory-proposal-count">{proposalCount}</span>
          ) : null}
        </Link>
      ))}
    </nav>
  );
}
