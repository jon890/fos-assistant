"use client";

import { useSyncExternalStore, type ComponentType } from "react";
import { useTheme } from "next-themes";
import { Moon, Sun, SunMoon, type LucideProps } from "lucide-react";
import { TooltipButton } from "@/components/ui/tooltip-button";

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

function subscribeNothing(): () => void {
  return () => {};
}

export function ThemeToggle() {
  const { theme, setTheme } = useTheme();
  // 서버와 hydration 동안은 저장된 테마를 모르므로 거짓이고, 브라우저에서 그린 뒤로는 참이다.
  const mounted = useSyncExternalStore(
    subscribeNothing,
    () => true,
    () => false,
  );

  if (!mounted || !isTheme(theme)) {
    return <span className="size-8 shrink-0" aria-hidden="true" />;
  }

  const nextTheme = THEMES[(THEMES.indexOf(theme) + 1) % THEMES.length];
  const Icon = THEME_ICON[theme];
  return (
    <TooltipButton
      label={`밝기 모드: ${THEME_LABEL[theme]}. 다음 선택: ${THEME_LABEL[nextTheme]}`}
      variant="outline"
      size="icon"
      onClick={() => setTheme(nextTheme)}
    >
      <Icon aria-hidden="true" />
    </TooltipButton>
  );
}
