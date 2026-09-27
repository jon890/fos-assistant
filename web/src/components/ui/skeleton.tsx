type Props = {
  className?: string;
};

export function Skeleton({ className = "" }: Props) {
  return (
    <span
      aria-hidden="true"
      className={`block animate-pulse rounded-md bg-muted motion-reduce:animate-none ${className}`}
    />
  );
}
