import * as React from "react";
import { cn } from "cn";
import { LoaderCircle } from "lucide-react";

type SwitchProps = Omit<React.ComponentProps<"button">, "onChange"> & {
  checked: boolean;
  onCheckedChange(next: boolean): void;
  /** 저장 요청을 보내는 동안이다. 누를 수 없지만 색은 그대로 두고 손잡이에 회전 표시를 그린다. */
  loading?: boolean;
};

/**
 * 누르는 즉시 저장되는 켜고 끄기다. 접근성 이름은 부르는 쪽이 `aria-label` 로 준다.
 * 체크박스가 아니라 단추로 만든다. 저장 중 표시를 손잡이 안에 그려야 하기 때문이다.
 */
export function Switch({
  checked,
  onCheckedChange,
  loading = false,
  disabled,
  className,
  onClick,
  ...props
}: SwitchProps) {
  return (
    <button
      type="button"
      role="switch"
      data-slot="switch"
      aria-checked={checked}
      aria-busy={loading || undefined}
      data-loading={loading || undefined}
      disabled={disabled || loading}
      onClick={(event) => {
        onClick?.(event);
        if (!event.defaultPrevented) onCheckedChange(!checked);
      }}
      className={cn(
        "inline-flex h-6 w-10 shrink-0 items-center rounded-full p-0.5 transition-colors duration-fast outline-none",
        "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring",
        // 켜짐과 꺼짐의 색이 이미 달라 투명도를 써도 뜻이 흐려지지 않는다. 저장 중에는 바래지 않는다.
        "disabled:cursor-not-allowed disabled:not-data-loading:opacity-60",
        checked ? "bg-primary" : "bg-input",
        className,
      )}
      {...props}
    >
      <span
        className={cn(
          "flex size-5 items-center justify-center rounded-full bg-card text-muted-foreground transition-transform duration-fast",
          checked && "translate-x-4",
        )}
      >
        {loading ? (
          <LoaderCircle
            aria-hidden="true"
            className="size-3 animate-spin motion-reduce:animate-none"
          />
        ) : null}
      </span>
      {loading ? <span className="sr-only">저장 중</span> : null}
    </button>
  );
}
