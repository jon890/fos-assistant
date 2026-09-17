"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const LINKS = [
  { href: "/", label: "대화" },
  { href: "/usage", label: "사용량" },
  { href: "/admin/agents", label: "에이전트 관리" },
] as const;

export function SiteNav() {
  const pathname = usePathname();
  return (
    <nav aria-label="주요 화면" className="flex min-w-0 items-center gap-1 overflow-x-auto">
      {LINKS.map((link) => {
        const current = link.href === "/" ? pathname === "/" : pathname.startsWith(link.href);
        return (
          <Link
            key={link.href}
            href={link.href}
            aria-current={current ? "page" : undefined}
            className={`shrink-0 rounded-md px-3 py-1.5 ${current ? "bg-surface font-medium text-foreground" : "text-muted"}`}
          >
            {link.label}
          </Link>
        );
      })}
    </nav>
  );
}
