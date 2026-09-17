import type { ReactNode } from "react";

type Props = {
  label: string;
  value: ReactNode;
  detail?: string;
  className?: string;
};

export function Stat({ label, value, detail, className = "" }: Props) {
  return (
    <div className={`min-w-0 border-l-2 border-foreground pl-3 ${className}`}>
      <dt className="text-xs text-muted">{label}</dt>
      <dd className="mt-1 break-words text-2xl font-semibold tracking-tight">{value}</dd>
      {detail ? <p className="mt-1 text-xs text-muted">{detail}</p> : null}
    </div>
  );
}
