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
import { Notice } from "@/components/ui/notice";
import { Textarea } from "@/components/ui/textarea";
import {
  hasBodyAfterFrontmatter,
  indexedDescriptionLength,
  isCountableDescription,
  MAX_DESCRIPTION_CHARS,
  MAX_NEW_DESCRIPTION_CHARS,
  SKILL_NAME_PATTERN,
  type SkillDetailView,
  type SkillListView,
} from "@/lib/skill";

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
const TEXT_LIKE_TYPES = [
  "application/json",
  "application/yaml",
  "application/x-yaml",
];

/** 맨 위 `---` 줄과 그다음 `---` 줄 사이가 앞머리다. 첫 번째 묶음이 앞머리 안쪽이다. */
const FRONTMATTER = /^---[ \t]*\r?\n([\s\S]*?)\r?\n---[ \t]*(\r?\n|$)/;

/**
 * 백엔드의 저장 한도와 같다. 글자 수는 UTF-16 코드 단위로 센다.
 * backend/src/main/java/com/bifos/assistant/skill/application/SkillService.java 의 같은 이름 상수와 함께 고친다.
 */
const MAX_FILES = 20;
const MAX_CHARS_PER_FILE = 100_000;
const MAX_TOTAL_BYTES = 1_048_576;

const NEW_SKILL_TEMPLATE = "---\nname: \ndescription: \n---\n\n";

/** 이 화면에서만 뜻이 정해지는 오류 코드의 문구다. 나머지는 공용 문구를 쓴다. */
const SAVE_FAILURES: Record<string, string> = {
  FORBIDDEN: "이 에이전트의 스킬을 관리할 수 없어요.",
  HERMES_UNAVAILABLE:
    "저장하지 못했어요. 바뀐 내용이 반영되지 않았을 수 있으니 다시 저장해 주세요.",
  // 백엔드 메시지는 영어라서 화면에 그대로 보이지 않게 한다. 화면이 먼저 거르지 못한 경우에만 여기까지 온다.
  VALIDATION_FAILED:
    "스킬 내용이 저장 규칙에 맞지 않아요. 앞머리와 파일을 확인해 주세요.",
};

function isTextFile(file: File, text: string): boolean {
  const lowerName = file.name.toLowerCase();
  const typeAllowed =
    file.type === "" ||
    file.type.startsWith("text/") ||
    TEXT_LIKE_TYPES.includes(file.type);
  return (
    typeAllowed &&
    TEXT_EXTENSIONS.some((extension) => lowerName.endsWith(extension)) &&
    !text.includes("\u0000")
  );
}

/**
 * 앞머리 안쪽에서 줄 맨 앞의 `key:` 줄을 찾아 인라인 값을 읽는다. 그 줄이 없으면 null 이다.
 * `multiline` 은 값이 `|`, `>` 이거나 다음 줄이 들여쓰기로 이어진다는 뜻이다.
 * 플로 매핑(`{name: x}`)이나 따옴표 친 키(`"name": x`)처럼 서버 YAML 파서만 읽는 형태는 찾지 못하고 null 이다.
 * 화면은 명백한 오류만 거르고, 나머지는 서버가 다시 검사한다.
 */
function frontmatterField(
  block: string,
  key: string,
): { value: string; multiline: boolean } | null {
  const lines = block.split(/\r?\n/);
  const pattern = new RegExp(`^${key}:(?:[ \\t](.*))?$`);
  const index = lines.findIndex((line) => pattern.test(line));
  if (index < 0) return null;
  const inline = (pattern.exec(lines[index]!)![1] ?? "").trim();
  const blockMarker = /^[>|]/.test(inline);
  const quoted = /^(["'])(.*)\1$/.exec(inline);
  const value = blockMarker
    ? ""
    : (quoted ? quoted[2]! : inline.replace(/(^|\s)#.*$/, "")).trim();
  const next = lines.slice(index + 1).find((line) => line.trim() !== "");
  return {
    value,
    multiline: blockMarker || (next !== undefined && /^\s/.test(next)),
  };
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
        rejected.push(
          `${file.name}: 파일 이름은 소문자, 숫자, 점, 밑줄, 하이픈만 쓸 수 있고 100자까지예요.`,
        );
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
      ...current.filter(
        (entry) =>
          !added.some(
            (next) =>
              next.directory === entry.directory &&
              next.fileName === entry.fileName,
          ),
      ),
      ...added,
    ]);
    if (rejected.length > 0) setFileError(rejected.join(" "));
  }

  function changeDirectory(key: number, directory: Directory) {
    setFiles((current) =>
      current.map((entry) =>
        entry.key === key ? { ...entry, directory } : entry,
      ),
    );
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
    if (new Set(paths).size !== paths.length)
      return "같은 경로의 참고 파일이 둘 이상 있어요.";
    const frontmatter = FRONTMATTER.exec(body);
    if (frontmatter === null)
      return "SKILL.md 맨 위에 --- 로 감싼 앞머리가 필요해요.";
    const name = frontmatterField(frontmatter[1]!, "name");
    if (
      name !== null &&
      !name.multiline &&
      name.value !== "" &&
      name.value !== skillName
    ) {
      return "앞머리의 name 이 스킬 이름과 같아야 해요.";
    }
    const description = frontmatterField(frontmatter[1]!, "description");
    if (
      description !== null &&
      !description.multiline &&
      description.value === ""
    ) {
      return "앞머리에 description 을 적어 주세요.";
    }
    if (!hasBodyAfterFrontmatter(body.slice(frontmatter[0].length))) {
      return "SKILL.md 앞머리 아래에 스킬 본문을 적어 주세요.";
    }
    if (
      description !== null &&
      isCountableDescription(description.value, description.multiline)
    ) {
      if (Array.from(description.value).length > MAX_DESCRIPTION_CHARS) {
        return `description 은 ${MAX_DESCRIPTION_CHARS.toLocaleString("ko-KR")}자까지 쓸 수 있어요.`;
      }
      const indexed = indexedDescriptionLength(description.value);
      if (isNew && indexed > MAX_NEW_DESCRIPTION_CHARS) {
        return `새 스킬의 description 은 ${MAX_NEW_DESCRIPTION_CHARS}자까지 쓸 수 있어요. 지금은 ${indexed}자예요. 자세한 설명은 본문에 적어 주세요.`;
      }
    }
    if (files.length > MAX_FILES)
      return `참고 파일은 ${MAX_FILES}개까지 둘 수 있어요.`;
    if (body.length > MAX_CHARS_PER_FILE)
      return `SKILL.md 는 ${MAX_CHARS_PER_FILE.toLocaleString("ko-KR")}자까지 쓸 수 있어요.`;
    const longFile = files.find(
      (entry) =>
        entry.content !== undefined &&
        entry.content.length > MAX_CHARS_PER_FILE,
    );
    if (longFile) {
      return `${longFile.fileName} 파일이 ${MAX_CHARS_PER_FILE.toLocaleString("ko-KR")}자를 넘어요. 파일 하나는 ${MAX_CHARS_PER_FILE.toLocaleString("ko-KR")}자까지예요.`;
    }
    // 새로 올리지 않은 파일은 서버가 알려 준 크기를 쓴다.
    const totalBytes =
      new TextEncoder().encode(body).length +
      files.reduce((sum, entry) => sum + entry.size, 0);
    if (totalBytes > MAX_TOTAL_BYTES) {
      return `SKILL.md 와 참고 파일을 합쳐 1MB 까지 저장할 수 있어요. 지금은 ${formatSize(totalBytes)}예요.`;
    }
    if (isNew) {
      // 저장 요청은 같은 이름이 있으면 덮어쓴다. 새 스킬이 남의 스킬을 지우지 않게 먼저 목록을 읽는다.
      let response: Response;
      try {
        response = await fetch(`/api/agents/${code}/skills`, {
          cache: "no-store",
        });
      } catch {
        return describeError("HERMES_UNAVAILABLE", "연결할 수 없어요.");
      }
      if (!response.ok) return describeFailure(response, SAVE_FAILURES);
      const list = (await response.json()) as SkillListView;
      if (
        list.skills.some(
          (skill) => skill.source === "UPLOADED" && skill.name === skillName,
        )
      ) {
        return "이미 같은 이름의 스킬이 있어요.";
      }
      // 옛 응답에는 한도가 없다. 서버가 최종으로 거절하므로 숫자가 아니면 개수는 보지 않는다.
      if (
        typeof list.uploadLimit === "number" &&
        list.skills.filter((skill) => skill.source === "UPLOADED").length >=
          list.uploadLimit
      ) {
        return `스킬은 에이전트마다 최대 ${list.uploadLimit}개까지 만들 수 있어요.`;
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
            return entry.content === undefined
              ? { path }
              : { path, content: entry.content };
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
      <h1 className="mb-2 text-xl font-semibold">
        {isNew ? "스킬 추가" : `${initial.name} 스킬`}
      </h1>
      <p className="mb-6 text-sm leading-6 text-muted-foreground">
        저장하면 이 에이전트가 다음 실행부터 이 스킬을 쓸 수 있어요.
      </p>
      {isNew ? (
        <div className="mb-4">
          <Label htmlFor={nameId} className="mb-2">
            스킬 이름
          </Label>
          <Input
            id={nameId}
            value={name}
            onChange={(event) => setName(event.target.value)}
            autoComplete="off"
            placeholder="weekly-plan"
          />
          <p className="mt-1 text-xs text-muted-foreground">
            소문자, 숫자, 하이픈으로 64자까지 쓸 수 있어요. 대화에서 이 이름으로
            스킬을 불러요.
          </p>
        </div>
      ) : null}
      <div className="mb-2 flex items-center justify-between gap-3">
        <h2 className="text-sm font-semibold">SKILL.md</h2>
        <div className="flex gap-2">
          <Button
            size="sm"
            variant={previewing ? "outline" : "default"}
            aria-pressed={!previewing}
            onClick={() => setPreviewing(false)}
          >
            편집
          </Button>
          <Button
            size="sm"
            variant={previewing ? "default" : "outline"}
            aria-pressed={previewing}
            onClick={() => setPreviewing(true)}
          >
            미리보기
          </Button>
        </div>
      </div>
      {previewing ? (
        <div
          aria-label="SKILL.md 미리보기"
          role="region"
          className="min-h-64 rounded-md border border-border p-3"
        >
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
        맨 위 --- 사이의 name 은 스킬 이름과 같아야 하고 description 이
        필요해요.
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
                <li
                  key={entry.key}
                  className="flex items-center justify-between gap-3 p-3"
                >
                  <div className="flex min-w-0 flex-wrap items-center gap-2">
                    {entry.content === undefined ? (
                      <span className="text-sm break-all">{path}</span>
                    ) : (
                      <>
                        <NativeSelect
                          value={entry.directory}
                          onChange={(event) =>
                            changeDirectory(
                              entry.key,
                              event.target.value as Directory,
                            )
                          }
                          aria-label={`${entry.fileName} 위치`}
                          className="w-auto"
                        >
                          <option value="references">references/</option>
                          <option value="templates">templates/</option>
                        </NativeSelect>
                        <span className="text-sm break-all">
                          {entry.fileName}
                        </span>
                      </>
                    )}
                    <span className="text-xs text-muted-foreground">
                      {formatSize(entry.size)}
                    </span>
                  </div>
                  <Button
                    size="sm"
                    variant="ghost"
                    aria-label={`${path} 빼기`}
                    onClick={() => removeFile(entry.key)}
                  >
                    빼기
                  </Button>
                </li>
              );
            })}
          </ul>
        ) : (
          <p className="mt-2 text-sm text-muted-foreground">
            아직 참고 파일이 없어요.
          </p>
        )}
        <div className="mt-3">
          <Label htmlFor={fileId} className="mb-2">
            참고 파일 올리기
          </Label>
          <Input
            id={fileId}
            type="file"
            multiple
            accept={TEXT_EXTENSIONS.join(",")}
            onChange={(event) => {
              const input = event.currentTarget;
              void addFiles(input.files).finally(() => {
                input.value = "";
              });
            }}
          />
        </div>
        {fileError ? (
          <Notice variant="error" role="alert" className="mt-2">
            {fileError}
          </Notice>
        ) : null}
      </section>

      {error ? (
        <Notice variant="error" role="alert" className="mt-4 break-all">
          {error}
        </Notice>
      ) : null}
      <div className="mt-4 flex gap-2">
        <Button
          loading={saving}
          loadingText="저장 중"
          disabled={isNew && name.trim() === ""}
          onClick={() => void save()}
        >
          저장
        </Button>
        <Button asChild variant="outline">
          <Link href={`/agents/${code}`}>취소</Link>
        </Button>
      </div>
    </div>
  );
}
