import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "cn";
import {
  CircleCheck,
  CircleX,
  Info,
  TriangleAlert,
  type LucideIcon,
} from "lucide-react";

const noticeVariants = cva(
  "flex items-start gap-2 rounded-md px-3 py-2 text-sm",
  {
    variants: {
      variant: {
        info: "bg-info-soft text-info",
        success: "bg-success-soft text-success",
        warning: "bg-warning-soft text-warning",
        error: "bg-destructive-soft text-destructive",
        // 뜻 없는 회색 상자다. 아이콘을 두지 않는다.
        neutral: "bg-muted text-foreground",
      },
    },
    defaultVariants: {
      variant: "neutral",
    },
  },
);

type NoticeVariant = NonNullable<
  VariantProps<typeof noticeVariants>["variant"]
>;

/** 색만으로 뜻을 전하지 않도록 변형마다 아이콘을 하나 둔다. */
const ICONS: Record<Exclude<NoticeVariant, "neutral">, LucideIcon> = {
  info: Info,
  success: CircleCheck,
  warning: TriangleAlert,
  error: CircleX,
};

type NoticeProps = React.ComponentProps<"div"> & { variant?: NoticeVariant };

/**
 * 안내와 오류를 알리는 상자다. 동작이 없고 색과 아이콘만 변형이 정한다.
 *
 * <p>`role` 은 정하지 않는다. 바로 읽혀야 하는 오류인지는 부르는 쪽이 알고 `role="alert"` 를 준다.
 */
function Notice({
  variant = "neutral",
  className,
  children,
  ...props
}: NoticeProps) {
  const Icon = variant === "neutral" ? null : ICONS[variant];
  return (
    <div
      data-slot="notice"
      data-variant={variant}
      className={cn(noticeVariants({ variant }), className)}
      {...props}
    >
      {Icon ? (
        // 첫 줄 글자의 가운데에 맞춘다. 글이 여러 줄이어도 아이콘은 첫 줄에 머문다.
        <Icon aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
      ) : null}
      {/* 안에 단추나 긴 낱말이 들어와도 상자를 넘기지 않게 남은 폭만 쓴다. */}
      <div className="min-w-0 flex-1">{children}</div>
    </div>
  );
}

export { Notice, noticeVariants };
