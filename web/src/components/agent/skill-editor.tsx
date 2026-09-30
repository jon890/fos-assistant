"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useId, useState } from "react";
import { Markdown } from "@/components/chat/markdown";
import { describeError, describeFailure } from "@/components/error-message";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { Textarea } from "@/components/ui/textarea";
import { SKILL_NAME_PATTERN, type SkillDetailView, type SkillListView } from "@/lib/skill";

type Props = {
  code: string;
  /** 고칠 스킬이다. 새 스킬이면 null 이고 이름을 입력받는다. */
  initial: SkillDetailView | null;
};

type Directory = "references" | "templates";

/** 참고 파일 한 줄이다. `content` 가 없으면 서버가 지금 갖고 있는 내용을 그대로 둔다. */
type FileEntry = {
  key: number;
  directory: Directory;
  fileName: string;
  size: number;
  content?: string;
};

/** 백엔드가 받는 참고 파일 이름이다. `SkillStore` 의 경로 규칙과 같다. */
const FILE_NAME_PATTERN = /^[a-z0-9][a-z0-9._-]{0,99}$/;

const TEXT_EXTENSIONS = [".md", ".txt", ".json", ".yaml", ".yml", ".csv"];

/** 브라우저가 이 확장자에 붙이는 형식이다. `.json` 과 `.yaml` 은 `text/` 로 시작하지 않는 형식을 받을 수 있다. */
const TEXT_LIKE_TYPES = ["application/json", "application/yaml", "application/x-yaml"];

const FRONTMATTER = /^---\r?\n[\s\S]*?\r?\n---[ \t]*(\r?\n|$)/;

const NEW_SKILL_TEMPLATE = "---\nname: \ndescription: \n---\n\n";

/** 이 화면에서만 뜻이 정해지는 오류 코드의 문구다. 나머지는 공용 문구를 쓴다. */
const SAVE_FAILURES: Record<string, string> = {
  FORBIDDEN: "이 에이전트의 스킬을 관리할 수 없어요.",
  HERMES_UNAVAILABLE: "저장하지 못했어요. 바뀐 내용이 반영되지 않았을 수 있으니 다시 저장해 주세요.",
};

function isTextFile(file: File, text: string): boolean {
  const lowerName = file.name.toLowerCase();
  const typeAllowed = file.type === "" || file.type.startsWith("text/") || TEXT_LIKE_TYPES.includes(file.type);
  return typeAllowed && TEXT_EXTENSIONS.some((extension) => lowerName.endsWith(extension)) && !text.includes("\u0000");
}

function formatSize(bytes: number): string {
  return bytes < 1024 ? `${bytes}바이트` : `${(bytes / 1024).toFixed(1)}KB`;
}

function entriesOf(initial: SkillDetailView | null): FileEntry[] {
  return (initial?.files ?? []).map((file, index) => {
    const [directory, fileName] = file.path.split("/") as [Directory, string];
    return { key: index, directory, fileName, size: file.size };
  });
}

/** 스킬의 `SKILL.md` 와 참고 파일을 쓰고 저장한다. 저장이 끝나면 에이전트 상세로 돌아간다. */
export function SkillEditor({ code, initial }: Props) {
  const router = useRouter();
  const nameId = useId();
  const fileId = useId();
  const [name, setName] = useState("");
  const [body, setBody] = useState(initial?.body ?? NEW_SKILL_TEMPLATE);
  const [previewing, setPreviewing] = useState(false);
  const [files, setFiles] = useState<FileEntry[]>(() => entriesOf(initial));
  const [nextKey, setNextKey] = useState(files.length);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const isNew = initial === null;

  async function addFiles(selected: FileList | null) {
    if (!selected) return;
    setFileError(null);
    const rejected: string[] = [];
    const added: FileEntry[] = [];
    let key = nextKey;
    for (const file of Array.from(selected)) {
      const content = await file.text();
      if (!isTextFile(file, content)) {
        rejected.push(`${file.name}: 텍스트 파일만 올릴 수 있어요.`);
      } else if (!FILE_NAME_PATTERN.test(file.name)) {
        rejected.push(`${file.name}: 파일 이름은 소문자, 숫자, 점, 밑줄, 하이픈만 쓸 수 있고 100자까지예요.`);
      } else {
        added.push({
          key: key++,
          directory: "references",
          fileName: file.name,
          size: new TextEncoder().encode(content).length,
          content,
        });
      }
    }
    setNextKey(key);
    // 같은 경로를 다시 올리면 먼저 있던 파일을 바꾼다.
    setFiles((current) => [
      ...current.filter((entry) => !added.some((next) => next.directory === entry.directory && next.fileName === entry.fileName)),
      ...added,
    ]);
    if (rejected.length > 0) setFileError(rejected.join(" "));
  }

  function changeDirectory(key: number, directory: Directory) {
    setFiles((current) => current.map((entry) => entry.key === key ? { ...entry, directory } : entry));
  }

  function removeFile(key: number) {
    setFiles((current) => current.filter((entry) => entry.key !== key));
  }

  /** 저장하기 전에 화면이 볼 수 있는 오류를 찾는다. 없으면 null 이다. */
  async function checkBeforeSave(skillName: string): Promise<string | null> {
    if (isNew) {
      if (!SKILL_NAME_PATTERN.test(skillName) || skillName === "new") {
        return "스킬 이름은 소문자, 숫자, 하이픈만 쓸 수 있고 64자까지예요. new 는 쓸 수 없어요.";
      }
    }
    const paths = files.map((entry) => `${entry.directory}/${entry.fileName}`);
    if (new Set(paths).size !== paths.length) return "같은 경로의 참고 파일이 둘 이상 있어요.";
    if (isNew) {
      // 저장 요청은 같은 이름이 있으면 덮어쓴다. 새 스킬이 남의 스킬을 지우지 않게 먼저 목록을 읽는다.
      let response: Response;
      try {
        response = await fetch(`/api/agents/${code}/skills`, { cache: "no-store" });
      } catch {
        return describeError("HERMES_UNAVAILABLE", "연결할 수 없어요.");
      }
      if (!response.ok) return describeFailure(response, SAVE_FAILURES);
      const list = (await response.json()) as SkillListView;
      if (list.skills.some((skill) => skill.source === "UPLOADED" && skill.name === skillName)) {
        return "이미 같은 이름의 스킬이 있어요.";
      }
    }
    return null;
  }

  async function save() {
    const skillName = isNew ? name.trim() : initial.name;
    setSaving(true);
    setError(null);
    try {
      const problem = await checkBeforeSave(skillName);
      if (problem !== null) {
        setError(problem);
        setSaving(false);
        return;
      }
      const response = await fetch(`/api/agents/${code}/skills/${skillName}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          skillMd: body,
          files: files.map((entry) => {
            const path = `${entry.directory}/${entry.fileName}`;
            return entry.content === undefined ? { path } : { path, content: entry.content };
          }),
        }),
      });
      if (!response.ok) {
        setError(await describeFailure(response, SAVE_FAILURES));
        setSaving(false);
        return;
      }
      // 성공하면 상세로 옮겨 가며 이 화면이 사라지므로, 그때까지 단추를 잠근 채로 둔다.
      router.push(`/agents/${code}`);
    } catch {
      setError(SAVE_FAILURES.HERMES_UNAVAILABLE!);
      setSaving(false);
    }
  }

  return (
    <div className="mx-auto w-full max-w-3xl">
      <h1 className="mb-2 text-xl font-semibold">{isNew ? "스킬 추가" : `${initial.name} 스킬`}</h1>
      <p className="mb-6 text-sm leading-6 text-muted-foreground">
        저장하면 이 에이전트가 다음 실행부터 이 스킬을 쓸 수 있어요.
      </p>
      {isNew ? (
        <div className="mb-4">
          <Label htmlFor={nameId} className="mb-2">스킬 이름</Label>
          <Input
            id={nameId}
            value={name}
            onChange={(event) => setName(event.target.value)}
            autoComplete="off"
            placeholder="weekly-plan"
          />
          <p className="mt-1 text-xs text-muted-foreground">
            소문자, 숫자, 하이픈으로 64자까지 쓸 수 있어요. 대화에서 이 이름으로 스킬을 불러요.
          </p>
        </div>
      ) : null}
      <div className="mb-2 flex items-center justify-between gap-3">
        <h2 className="text-sm font-semibold">SKILL.md</h2>
        <div className="flex gap-2">
          <Button size="sm" variant={previewing ? "outline" : "default"} aria-pressed={!previewing}
            onClick={() => setPreviewing(false)}>
            편집
          </Button>
          <Button size="sm" variant={previewing ? "default" : "outline"} aria-pressed={previewing}
            onClick={() => setPreviewing(true)}>
            미리보기
          </Button>
        </div>
      </div>
      {previewing ? (
        <div aria-label="SKILL.md 미리보기" role="region" className="min-h-64 rounded-md border border-border p-3">
          <Markdown>{body.replace(FRONTMATTER, "")}</Markdown>
        </div>
      ) : (
        // Textarea 의 기본 field-sizing-content 는 rows 를 무시해 편집창이 작게 시작하므로 고정으로 되돌린다.
        <Textarea
          value={body}
          onChange={(event) => setBody(event.target.value)}
          rows={16}
          aria-label="SKILL.md 본문"
          className="field-sizing-fixed font-mono leading-6"
        />
      )}
      <p className="mt-1 text-xs text-muted-foreground">
        맨 위 --- 사이의 name 은 스킬 이름과 같아야 하고 description 이 필요해요.
      </p>

      <section aria-label="참고 파일" className="mt-6">
        <h2 className="text-sm font-semibold">참고 파일</h2>
        <p className="mt-1 text-xs text-muted-foreground">
          md, txt, json, yaml, yml, csv 파일을 올릴 수 있어요. 최대 20개예요.
        </p>
        {files.length > 0 ? (
          <ul className="mt-2 divide-y divide-border rounded-md border border-border">
            {files.map((entry) => {
              const path = `${entry.directory}/${entry.fileName}`;
              return (
                <li key={entry.key} className="flex items-center justify-between gap-3 p-3">
                  <div className="flex min-w-0 flex-wrap items-center gap-2">
                    {entry.content === undefined ? (
                      <span className="text-sm break-all">{path}</span>
                    ) : (
                      <>
                        <NativeSelect
                          value={entry.directory}
                          onChange={(event) => changeDirectory(entry.key, event.target.value as Directory)}
                          aria-label={`${entry.fileName} 위치`}
                          className="w-auto"
                        >
                          <option value="references">references/</option>
                          <option value="templates">templates/</option>
                        </NativeSelect>
                        <span className="text-sm break-all">{entry.fileName}</span>
                      </>
                    )}
                    <span className="text-xs text-muted-foreground">{formatSize(entry.size)}</span>
                  </div>
                  <Button size="sm" variant="ghost" aria-label={`${path} 빼기`} onClick={() => removeFile(entry.key)}>
                    빼기
                  </Button>
                </li>
              );
            })}
          </ul>
        ) : (
          <p className="mt-2 text-sm text-muted-foreground">아직 참고 파일이 없어요.</p>
        )}
        <div className="mt-3">
          <Label htmlFor={fileId} className="mb-2">참고 파일 올리기</Label>
          <Input
            id={fileId}
            type="file"
            multiple
            accept={TEXT_EXTENSIONS.join(",")}
            onChange={(event) => {
              const input = event.currentTarget;
              void addFiles(input.files).finally(() => { input.value = ""; });
            }}
          />
        </div>
        {fileError ? <p role="alert" className="mt-2 rounded-md bg-muted p-3 text-sm">{fileError}</p> : null}
      </section>

      {error ? <p role="alert" className="mt-4 rounded-md bg-muted p-3 text-sm break-all">{error}</p> : null}
      <div className="mt-4 flex gap-2">
        <Button loading={saving} loadingText="저장 중" disabled={isNew && name.trim() === ""} onClick={() => void save()}>
          저장
        </Button>
        <Button asChild variant="outline">
          <Link href={`/agents/${code}`}>취소</Link>
        </Button>
      </div>
    </div>
  );
}
