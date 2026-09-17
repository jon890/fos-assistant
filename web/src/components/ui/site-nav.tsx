"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const LINKS = [
  { href: "/", label: "대화" },
  { href: "/usage", label: "사용량" },
] as const;

export function SiteNav({ isAdmin }: { isAdmin: boolean }) {
  const pathname = usePathname();
  const links = isAdmin
    ? [...LINKS, { href: "/admin/agents", label: "에이전트 관리" }]
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
            {link.label}
          </Link>
        );
      })}
    </nav>
  );
}
