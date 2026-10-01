import * as React from "react";
import { cn } from "cn";
import { ChevronDown } from "lucide-react";

/**
 * 네이티브 `<select>` 에 `Input` 과 같은 테두리와 높이를 입힌다.
 * 폼이 `FormData` 로 값을 읽고 브라우저 검사가 `selectOption` 을 쓰므로 Radix `Select` 로 바꾸지 않는다(ADR-023).
 * 브라우저마다 다른 펼침 화살표는 가리고 오른쪽에 같은 아이콘을 그린다.
 */
function NativeSelect({ className, ...props }: React.ComponentProps<"select">) {
  return (
    // select 가 `w-auto` 를 받으면 감싼 요소도 내용 폭을 따라야 아이콘이 select 의 오른쪽 끝에 놓인다.
    <span className="relative block w-full min-w-0 has-[>.w-auto]:w-auto">
      <select
        data-slot="native-select"
        className={cn(
          "h-8 w-full min-w-0 appearance-none rounded-md border border-input bg-transparent py-1 pr-8 pl-2.5 text-base transition-colors outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:cursor-not-allowed disabled:bg-input/50 disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 md:text-sm",
          className,
        )}
        {...props}
      />
      <ChevronDown
        aria-hidden="true"
        className="pointer-events-none absolute top-1/2 right-2.5 size-4 -translate-y-1/2 text-muted-foreground"
      />
    </span>
  );
}

export { NativeSelect };
