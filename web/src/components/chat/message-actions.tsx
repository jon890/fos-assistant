import { CopyButton } from "@/components/ui/copy-button";

export function MessageActions({ content, latest }: { content: string; latest: boolean }) {
  return (
    <div className={`mt-2 flex ${latest ? "md:opacity-100" : "md:opacity-0 md:group-hover:opacity-100 md:group-focus-within:opacity-100"}`}>
      <CopyButton text={content} label="답 복사" />
    </div>
  );
}
