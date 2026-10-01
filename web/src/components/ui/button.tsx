import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "cn";
import { LoaderCircle } from "lucide-react";
import { Slot } from "radix-ui";

const buttonVariants = cva(
  // 고침: 모서리를 rounded-md 로. 바탕 밖 투명 테두리(border-transparent)를 빼 단추 높이가 여백만으로 정해지게 한다. aria-invalid 의 dark: 덮어쓰기도 뺐다.
  "group/button inline-flex shrink-0 items-center justify-center rounded-md text-sm font-medium whitespace-nowrap transition-all outline-none select-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 active:not-aria-[haspopup]:translate-y-px disabled:pointer-events-none disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
  {
    variants: {
      variant: {
        // 고침: 마우스 올림과 누름을 primary-strong 으로 칠한다.
        default:
          "bg-primary text-primary-foreground hover:bg-primary-strong active:bg-primary-strong",
        // 고침: 두 밝기 모드 모두 테두리는 border, 바탕은 비워 둔다. dark: 덮어쓰기와 bg-background 를 뺐다.
        outline:
          "border border-border hover:bg-muted hover:text-foreground aria-expanded:bg-muted aria-expanded:text-foreground",
        secondary:
          "bg-secondary text-secondary-foreground hover:bg-[color-mix(in_oklch,var(--secondary),var(--foreground)_5%)] aria-expanded:bg-secondary aria-expanded:text-secondary-foreground",
        // 고침: 밝기 모드는 토큰이 나누므로 dark: 마우스 올림 덮어쓰기를 뺐다.
        ghost:
          "hover:bg-muted hover:text-foreground aria-expanded:bg-muted aria-expanded:text-foreground",
        // 고침: destructive 바탕에 destructive-foreground 글자로 칠한다. 두 밝기 모드의 대비를 토큰 짝이 보장한다.
        destructive:
          "bg-destructive text-destructive-foreground hover:bg-destructive/90 focus-visible:ring-destructive/20",
        link: "text-primary underline-offset-4 hover:underline",
      },
      size: {
        // 고침: 고정 높이 대신 control 토큰 여백으로 크기를 정한다.
        default: "gap-1.5 px-control-x-md py-control-y-md",
        xs: "h-6 gap-1 rounded-[min(var(--radius-md),10px)] px-2 text-xs in-data-[slot=button-group]:rounded-lg has-data-[icon=inline-end]:pr-1.5 has-data-[icon=inline-start]:pl-1.5 [&_svg:not([class*='size-'])]:size-3",
        // 고침: 고정 높이 대신 control 토큰 여백으로 크기를 정한다.
        sm: "gap-1 px-control-x-sm py-control-y-sm [&_svg:not([class*='size-'])]:size-3.5",
        lg: "h-9 gap-1.5 px-2.5 has-data-[icon=inline-end]:pr-2 has-data-[icon=inline-start]:pl-2",
        icon: "size-8",
        "icon-xs":
          "size-6 rounded-[min(var(--radius-md),10px)] in-data-[slot=button-group]:rounded-lg [&_svg:not([class*='size-'])]:size-3",
        "icon-sm":
          "size-7 rounded-[min(var(--radius-md),12px)] in-data-[slot=button-group]:rounded-lg",
        "icon-lg": "size-9",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  },
);

function Button({
  className,
  variant = "default",
  size = "default",
  asChild = false,
  type,
  disabled,
  loading = false,
  loadingText,
  children,
  ...props
}: React.ComponentProps<"button"> &
  VariantProps<typeof buttonVariants> & {
    asChild?: boolean;
    /** 고침: 보내는 동안 단추를 잠그고 회전 표시를 앞에 둔다. */
    loading?: boolean;
    /** 보내는 동안 보일 글자다. 주지 않으면 폭이 줄지 않게 원래 글자를 그대로 둔다. */
    loadingText?: string;
  }) {
  const Comp = asChild ? Slot.Root : "button";
  const busy = loading && !asChild;

  return (
    <Comp
      data-slot="button"
      data-variant={variant}
      data-size={size}
      // 고침: 폼 안에서 뜻하지 않게 보내지 않도록 단추의 type 기본값을 "button" 으로 둔다.
      type={asChild ? type : (type ?? "button")}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      className={cn(buttonVariants({ variant, size, className }))}
      {...props}
    >
      {busy ? (
        <>
          <LoaderCircle
            aria-hidden="true"
            className="animate-spin motion-reduce:animate-none"
          />
          {loadingText ?? children}
        </>
      ) : (
        children
      )}
    </Comp>
  );
}

export { Button, buttonVariants };
