"use client";

import { useId, useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { describeError } from "@/components/error-message";
import { GROUP_VISIBILITY, PRIVATE_VISIBILITY, type AdminAgent, type AgentView } from "@/lib/agent";

type ErrorPayload = { code: string; message: string };

/** `backend` 의 이름 칸 길이(`agent.name`)와 같다. */
const MAX_NAME_CHARS = 100;

/**
 * 「새 에이전트」 단추와 그 대화상자다. 이름과 공개 범위만 받고, 성격과 도구는 만든 뒤 상세에서 고친다.
 *
 * <p>만드는 데 몇 초 걸리므로 요청이 도는 동안 단추를 막고 창도 닫히지 않게 한다. 성공하면 상세로 옮겨 가며
 * 이 창이 사라지므로 그때까지 진행 중인 채로 둔다.
 */
export function CreateAgentDialog() {
  const router = useRouter();
  const id = useId();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [visibility, setVisibility] = useState<AdminAgent["visibility"]>(PRIVATE_VISIBILITY);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function changeOpen(next: boolean) {
    if (creating) return;
    setOpen(next);
    if (next) return;
    setName("");
    setVisibility(PRIVATE_VISIBILITY);
    setError(null);
  }

  async function create(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setCreating(true);
    setError(null);
    try {
      const response = await fetch("/api/agents", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name: name.trim(), visibility }),
      });
      if (!response.ok) {
        const payload = (await response.json()) as ErrorPayload;
        setError(payload.code === "AGENT_LIMIT_REACHED"
          ? "에이전트는 5개까지 만들 수 있어요"
          : describeError(payload.code, payload.message));
        setCreating(false);
        return;
      }
      const created = (await response.json()) as AgentView;
      router.push(`/agents/${created.code}`);
    } catch {
      setError(describeError("HERMES_UNAVAILABLE", "연결할 수 없어요."));
      setCreating(false);
    }
  }

  return (
    <>
      <Button onClick={() => changeOpen(true)}>새 에이전트</Button>
      <Dialog open={open} onOpenChange={changeOpen}>
        <DialogContent showCloseButton={!creating}>
          <form onSubmit={(event) => void create(event)} className="grid gap-4">
            <DialogHeader>
              <DialogTitle>새 에이전트</DialogTitle>
              <DialogDescription>
                이름을 정하면 에이전트를 만들어요. 성격과 도구는 만든 뒤에 고칠 수 있어요.
              </DialogDescription>
            </DialogHeader>
            <div className="grid gap-1.5">
              <Label htmlFor={`${id}-name`}>이름</Label>
              <Input
                id={`${id}-name`}
                value={name}
                maxLength={MAX_NAME_CHARS}
                required
                disabled={creating}
                onChange={(event) => setName(event.target.value)}
              />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor={`${id}-visibility`}>공개 범위</Label>
              <NativeSelect
                id={`${id}-visibility`}
                value={visibility}
                disabled={creating}
                onChange={(event) => setVisibility(event.target.value as AdminAgent["visibility"])}
              >
                <option value={PRIVATE_VISIBILITY}>나만</option>
                <option value={GROUP_VISIBILITY}>그룹 공개</option>
              </NativeSelect>
            </div>
            {error ? <p role="alert" className="rounded-md bg-muted p-3 text-sm">{error}</p> : null}
            <DialogFooter>
              <Button type="button" variant="outline" disabled={creating} onClick={() => changeOpen(false)}>취소</Button>
              <Button type="submit" loading={creating} loadingText="만드는 중…" disabled={name.trim() === ""}>만들기</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}
