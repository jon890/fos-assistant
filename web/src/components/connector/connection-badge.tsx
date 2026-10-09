import type { ComponentProps } from "react";
import { Badge } from "@/components/ui/badge";
import { connectionStatusLabel, type ConnectionStatus } from "@/lib/connection";

/** 목록과 상세 화면에서 연결 상태를 같은 글자와 의미 색으로 표시한다. */
export function ConnectionBadge({
  status,
  ...props
}: { status: ConnectionStatus } & Omit<
  ComponentProps<typeof Badge>,
  "variant"
>) {
  return (
    <Badge
      {...props}
      variant={
        status === "READY"
          ? "success"
          : status === "PENDING"
            ? "warning"
            : "outline"
      }
    >
      {connectionStatusLabel(status)}
    </Badge>
  );
}
