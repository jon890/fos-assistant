"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { Bot, Brain, CalendarClock, ChartColumn, Plug } from "lucide-react";
import { fetchMemories } from "@/lib/memory-api";
import { NavPending } from "./nav-pending";

const LINKS = [
  { href: "/agents", label: "에이전트", icon: Bot },
  { href: "/connections", label: "외부 서비스 연결", icon: Plug },
  { href: "/tasks", label: "예약 작업", icon: CalendarClock },
  { href: "/memory", label: "기억", icon: Brain },
  { href: "/usage", label: "사용량", icon: ChartColumn },
] as const;

export function MainNav({ onNavigate }: { onNavigate(href: string): void }) {
  const pathname = usePathname();
  const [proposalCount, setProposalCount] = useState(0);
  useEffect(() => {
    void fetchMemories()
      .then((response) => (response.ok ? response.json() : []))
      .then((memories: { status: string }[]) =>
        setProposalCount(
          memories.filter((memory) => memory.status === "PROPOSED").length,
        ),
      )
      .catch(() => setProposalCount(0));
  }, [pathname]);
  useEffect(() => {
    const update = (event: Event) =>
      setProposalCount((event as CustomEvent<number>).detail);
    window.addEventListener("memory-proposal-count", update);
    return () => window.removeEventListener("memory-proposal-count", update);
  }, []);

  return (
    <nav aria-label="주요 화면" className="flex flex-col gap-1">
      {LINKS.map((link) => (
        <Link
          key={link.href}
          href={link.href}
          onClick={() => onNavigate(link.href)}
          aria-current={
            pathname === link.href || pathname.startsWith(`${link.href}/`)
              ? "page"
              : undefined
          }
          className={`flex items-center gap-2 rounded-md px-3 py-2 text-sm hover:bg-accent hover:text-foreground ${
            pathname === link.href || pathname.startsWith(`${link.href}/`)
              ? "bg-accent font-medium text-foreground"
              : "text-muted-foreground"
          }`}
        >
          <link.icon aria-hidden="true" className="size-4 shrink-0" />
          {link.label}
          {link.href === "/memory" && proposalCount > 0 ? (
            <span
              className="ml-2 rounded-full bg-muted px-1.5 py-0.5 text-xs text-foreground"
              data-testid="memory-proposal-count"
            >
              {proposalCount}
            </span>
          ) : null}
          <NavPending />
        </Link>
      ))}
    </nav>
  );
}
