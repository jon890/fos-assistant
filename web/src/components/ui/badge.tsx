import type { ReactNode } from "react";

type Props = {
  children: ReactNode;
  emphasis?: boolean;
};

export function Badge({ children, emphasis = false }: Props) {
  return (
    <span
      className={`inline-flex w-fit items-center rounded-full border px-2 py-0.5 text-xs ${
        emphasis ? "border-foreground font-semibold text-foreground" : "border-border bg-surface text-muted"
      }`}
    >
      {children}
    </span>
  );
}
