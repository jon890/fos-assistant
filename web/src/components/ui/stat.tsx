import type { ReactNode } from "react";
import { cn } from "cn";
import { Card } from "@/components/ui/card";

type Props = {
  label: string;
  value: ReactNode;
  detail?: string;
  className?: string;
};

/**
 * 합계 칸 하나다. `Card` 의 테두리와 바탕과 안쪽 여백을 갖는다.
 * 부르는 쪽의 `<dl>` 이 감싸므로 `<dt>` 와 `<dd>` 를 그대로 둔다.
 */
export function Stat({ label, value, detail, className }: Props) {
  return (
    <Card className={cn("min-w-0 gap-0 px-4", className)}>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="mt-1 break-words text-2xl font-semibold tracking-tight">{value}</dd>
      {detail ? <p className="mt-1 text-xs text-muted-foreground">{detail}</p> : null}
    </Card>
  );
}
