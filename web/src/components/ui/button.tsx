import type { ButtonHTMLAttributes } from "react";

type ButtonVariant = "primary" | "secondary" | "ghost";
type ButtonSize = "sm" | "md";

type Props = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant;
  size?: ButtonSize;
};

const variantClasses: Record<ButtonVariant, string> = {
  primary: "bg-brand text-on-brand hover:bg-brand-strong active:bg-brand-strong",
  secondary: "border border-border bg-transparent text-foreground hover:bg-surface-raised active:bg-surface",
  ghost: "bg-transparent text-foreground hover:bg-surface-raised active:bg-surface",
};

const sizeClasses: Record<ButtonSize, string> = {
  sm: "px-control-x-sm py-control-y-sm text-sm",
  md: "px-control-x-md py-control-y-md text-sm",
};

export function Button({
  type = "button",
  variant = "primary",
  size = "md",
  className = "",
  ...props
}: Props) {
  return (
    <button
      type={type}
      className={`inline-flex items-center justify-center rounded-md font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${variantClasses[variant]} ${sizeClasses[size]} ${className}`}
      {...props}
    />
  );
}
