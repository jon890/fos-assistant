type Props = {
  className?: string;
};

export function Skeleton({ className = "" }: Props) {
  return (
    <span
      aria-hidden="true"
      className={`block animate-pulse rounded-md bg-surface motion-reduce:animate-none ${className}`}
    />
  );
}
