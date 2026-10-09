"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useId, useState } from "react";
import {
  Bot,
  Brain,
  CalendarClock,
  ChartColumn,
  FolderOpen,
  Globe,
  Plug,
} from "lucide-react";
import { fetchMemories } from "@/lib/memory-api";
import { NavPending } from "./nav-pending";

const LINKS = [
  { href: "/agents", label: "에이전트", icon: Bot },
  { href: "/connections", label: "외부 서비스 연결", icon: Plug },
  { href: "/tasks", label: "예약 작업", icon: CalendarClock },
  { href: "/memory", label: "기억", icon: Brain },
  { href: "/usage", label: "사용량", icon: ChartColumn },
] as const;

/** 「고급」 묶음의 링크다. 매일 쓰는 화면이 아니라 에이전트가 쓰는 자리를 들여다보는 화면이라 위 메뉴와 나눈다. */
const ADVANCED_LINKS = [
  { href: "/files", label: "파일 공간", icon: FolderOpen },
  { href: "/browser", label: "내 브라우저", icon: Globe },
] as const;

function isCurrent(pathname: string, href: string): boolean {
  return pathname === href || pathname.startsWith(`${href}/`);
}

function linkClass(current: boolean): string {
  return `flex items-center gap-2 rounded-md px-3 py-2 text-sm hover:bg-accent hover:text-foreground ${
    current ? "bg-accent font-medium text-foreground" : "text-muted-foreground"
  }`;
}

export function MainNav({ onNavigate }: { onNavigate(href: string): void }) {
  const pathname = usePathname();
  // 사이드바가 넓은 화면과 좁은 화면의 시트에 함께 그려져도 묶음 이름의 id 가 겹치지 않게 한다.
  const advancedId = useId();
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
          aria-current={isCurrent(pathname, link.href) ? "page" : undefined}
          className={linkClass(isCurrent(pathname, link.href))}
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
      {/* 「고급」 은 링크가 아니라 묶음의 이름이다. 낭독기는 아래 링크를 이 이름의 묶음으로 읽는다. 접고 펴지 않는다. */}
      <div
        role="group"
        aria-labelledby={advancedId}
        className="mt-2 flex flex-col gap-1"
      >
        <p id={advancedId} className="px-3 py-1 text-xs text-muted-foreground">
          고급
        </p>
        {ADVANCED_LINKS.map((link) => (
          <Link
            key={link.href}
            href={link.href}
            onClick={() => onNavigate(link.href)}
            aria-current={isCurrent(pathname, link.href) ? "page" : undefined}
            className={`ml-3 ${linkClass(isCurrent(pathname, link.href))}`}
          >
            <link.icon aria-hidden="true" className="size-4 shrink-0" />
            {link.label}
            <NavPending />
          </Link>
        ))}
      </div>
    </nav>
  );
}
