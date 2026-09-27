"use client";

import { useEffect, useState, type ComponentType } from "react";
import { useTheme } from "next-themes";
import { Moon, Sun, SunMoon, type LucideProps } from "lucide-react";

const THEMES = ["light", "dark", "system"] as const;
type Theme = (typeof THEMES)[number];

const THEME_LABEL: Record<Theme, string> = {
  light: "밝음",
  dark: "어두움",
  system: "시스템",
};

const THEME_ICON: Record<Theme, ComponentType<LucideProps>> = {
  light: Sun,
  dark: Moon,
  system: SunMoon,
};

function isTheme(value: string | undefined): value is Theme {
  return THEMES.some((theme) => theme === value);
}

export function ThemeToggle() {
  const { theme, setTheme } = useTheme();
  const [mounted, setMounted] = useState(false);

  useEffect(() => setMounted(true), []);

  if (!mounted || !isTheme(theme)) {
    return <span className="size-8 shrink-0" aria-hidden="true" />;
  }

  const nextTheme = THEMES[(THEMES.indexOf(theme) + 1) % THEMES.length];
  const Icon = THEME_ICON[theme];
  return (
    <button
      type="button"
      onClick={() => setTheme(nextTheme)}
      className="flex size-8 shrink-0 items-center justify-center rounded-md border border-border"
      aria-label={`밝기 모드: ${THEME_LABEL[theme]}. 다음은 ${THEME_LABEL[nextTheme]}`}
      title={`밝기 모드: ${THEME_LABEL[theme]}`}
    >
      <Icon aria-hidden="true" className="size-4" />
    </button>
  );
}
