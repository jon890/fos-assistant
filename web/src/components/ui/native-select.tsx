import * as React from "react";
import { cn } from "cn";

/**
 * 네이티브 `<select>` 에 `Input` 과 같은 테두리와 높이를 입힌다.
 * 폼이 `FormData` 로 값을 읽고 브라우저 검사가 `selectOption` 을 쓰므로 Radix `Select` 로 바꾸지 않는다(ADR-023).
 * 펼침 화살표는 브라우저의 것을 그대로 둔다.
 */
function NativeSelect({ className, ...props }: React.ComponentProps<"select">) {
  return (
    <select
      data-slot="native-select"
      className={cn(
        "h-8 w-full min-w-0 rounded-md border border-input bg-transparent px-2.5 py-1 text-base transition-colors outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:cursor-not-allowed disabled:bg-input/50 disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 md:text-sm",
        className,
      )}
      {...props}
    />
  );
}

export { NativeSelect };
