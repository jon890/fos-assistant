import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "cn"
import { Slot } from "radix-ui"

const badgeVariants = cva(
  // 고침: 알약 모서리(rounded-full)로 짧은 표시임을 드러낸다. 고정 높이와 font-medium 을 빼 글자 크기가 높이를 정하게 한다.
  "group/badge inline-flex w-fit shrink-0 items-center justify-center gap-1 overflow-hidden rounded-full border border-transparent px-2 py-0.5 text-xs whitespace-nowrap transition-all focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 has-data-[icon=inline-end]:pr-1.5 has-data-[icon=inline-start]:pl-1.5 aria-invalid:border-destructive aria-invalid:ring-destructive/20 [&>svg]:pointer-events-none [&>svg]:size-3!",
  {
    variants: {
      variant: {
        // 고침: 강조 표시다. 실패나 사용 중지처럼 눈에 띄어야 하는 상태를 글자색 테두리와 굵은 글자로 그린다.
        default: "border-foreground font-semibold text-foreground",
        secondary:
          "bg-secondary text-secondary-foreground [a]:hover:bg-secondary/80",
        destructive:
          // 고침: 밝기 모드는 토큰이 나누므로 dark: 덮어쓰기를 뺐다.
          "bg-destructive/10 text-destructive focus-visible:ring-destructive/20 [a]:hover:bg-destructive/20",
        // 고침: 흐린 표시다. 테두리 위에 muted 바탕과 muted-foreground 글자를 둔다.
        outline:
          "border-border bg-muted text-muted-foreground",
        ghost:
          // 고침: 밝기 모드는 토큰이 나누므로 dark: 덮어쓰기를 뺐다.
          "hover:bg-muted hover:text-muted-foreground",
        link: "text-primary underline-offset-4 hover:underline",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  }
)

function Badge({
  className,
  variant = "default",
  asChild = false,
  ...props
}: React.ComponentProps<"span"> &
  VariantProps<typeof badgeVariants> & { asChild?: boolean }) {
  const Comp = asChild ? Slot.Root : "span"

  return (
    <Comp
      data-slot="badge"
      data-variant={variant}
      className={cn(badgeVariants({ variant }), className)}
      {...props}
    />
  )
}

export { Badge, badgeVariants }
