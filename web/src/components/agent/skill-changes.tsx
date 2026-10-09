import type { SkillDetailView } from "@/lib/skill";
import type { SkillFileEntry } from "./skill-files-editor";

/** 바뀐 파일마다 저장 전 원문과 저장할 내용을 함께 확인한다. */
export function SkillChanges({
  initial,
  body,
  files,
}: {
  initial: SkillDetailView | null;
  body: string;
  files: SkillFileEntry[];
}) {
  const before = new Map([
    ...(initial ? [["SKILL.md", initial.body] as const] : []),
    ...(initial?.files ?? []).map((file) => [file.path, file.content] as const),
  ]);
  const after = new Map([
    ["SKILL.md", body],
    ...files.map(
      (file) =>
        [
          `${file.directory}/${file.fileName}`,
          file.content ??
            before.get(`${file.directory}/${file.fileName}`) ??
            "",
        ] as const,
    ),
  ]);
  const changed = [...new Set([...before.keys(), ...after.keys()])].filter(
    (filePath) => before.get(filePath) !== after.get(filePath),
  );
  return (
    <section
      aria-label="저장 전 바뀐 점"
      className="mt-6 rounded-md border border-border p-3"
    >
      <h2 className="text-sm font-semibold">저장 전 바뀐 점</h2>
      {changed.length === 0 ? (
        <p className="mt-2 text-sm text-muted-foreground">
          바뀐 내용이 없어요.
        </p>
      ) : (
        changed.map((filePath) => (
          <details key={filePath} className="mt-2">
            <summary className="cursor-pointer text-sm break-all">
              {filePath} ·{" "}
              {after.has(filePath)
                ? before.has(filePath)
                  ? "수정"
                  : "추가"
                : "삭제"}
            </summary>
            <div className="mt-2 grid gap-3 sm:grid-cols-2">
              {[
                { label: "저장 전", content: before.get(filePath) },
                { label: "저장할 내용", content: after.get(filePath) },
              ].map(({ label, content }) => (
                <div key={label}>
                  <h3 className="text-xs font-medium">{label}</h3>
                  <pre className="mt-1 max-h-64 overflow-auto rounded-md bg-muted p-2 text-xs whitespace-pre-wrap break-all">
                    {content ?? "파일이 없어요."}
                  </pre>
                </div>
              ))}
            </div>
          </details>
        ))
      )}
    </section>
  );
}
