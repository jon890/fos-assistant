"use client";

import { useId, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import type { MemoryCollectionOption } from "@/lib/memory-document";
import { issueServiceToken } from "@/lib/service-token-api";

/** 고를 수 있는 만료다. 가장 긴 것이 1년이고 만료 없음은 없다(ADR-056). */
const EXPIRY_CHOICES = [
  { days: 30, label: "30일" },
  { days: 90, label: "90일" },
  { days: 365, label: "1년" },
];
const DEFAULT_EXPIRY_DAYS = 90;

export function ServiceTokenForm({
  collections,
  onIssued,
}: {
  collections: MemoryCollectionOption[];
  /** 발급한 토큰의 원문을 받는다. 원문은 여기서 저장하지 않는다. */
  onIssued(token: string): Promise<void>;
}) {
  const [expiresInDays, setExpiresInDays] = useState(DEFAULT_EXPIRY_DAYS);
  /** 고른 영역과 그 영역의 민감 허용이다. 영역마다 따로 고르고 모두 허용하는 길은 없다. */
  const [grants, setGrants] = useState<Record<string, boolean>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const id = useId();
  const selected = Object.keys(grants);

  function toggle(key: string, checked: boolean) {
    setGrants((current) => {
      const next = { ...current };
      if (checked) next[key] = false;
      else delete next[key];
      return next;
    });
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const element = event.currentTarget;
    const form = new FormData(element);
    setBusy(true);
    setError("");
    try {
      const result = await issueServiceToken({
        label: String(form.get("label")),
        expiresInDays,
        collections: selected.map((collection) => ({
          collection,
          allowSensitive: grants[collection],
        })),
      });
      if (!result.ok) {
        setError(result.message);
        return;
      }
      element.reset();
      setExpiresInDays(DEFAULT_EXPIRY_DAYS);
      setGrants({});
      await onIssued(result.data.token);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form
      onSubmit={(event) => void submit(event)}
      className="mb-4 rounded-md border border-border p-4"
    >
      <h3 className="font-semibold">새 토큰</h3>
      <div className="mt-4 grid gap-4 md:grid-cols-2">
        <div className="grid gap-1.5">
          <Label htmlFor={`${id}-label`}>이름</Label>
          <Input id={`${id}-label`} name="label" required maxLength={100} />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor={`${id}-expiry`}>만료</Label>
          <NativeSelect
            id={`${id}-expiry`}
            value={expiresInDays}
            onChange={(event) => setExpiresInDays(Number(event.target.value))}
          >
            {EXPIRY_CHOICES.map((choice) => (
              <option key={choice.days} value={choice.days}>
                {choice.label}
              </option>
            ))}
          </NativeSelect>
        </div>
      </div>
      <fieldset className="mt-4 grid gap-2">
        <legend className="mb-1 text-sm font-medium">읽을 영역</legend>
        {collections.map((option) => (
          <div key={option.key} className="grid gap-1">
            <Label className="font-normal">
              <input
                type="checkbox"
                className="accent-primary"
                checked={option.key in grants}
                onChange={(event) => toggle(option.key, event.target.checked)}
              />
              {option.displayName}
            </Label>
            {option.key in grants ? (
              <Label className="ml-6 font-normal text-muted-foreground">
                <input
                  type="checkbox"
                  className="accent-primary"
                  checked={grants[option.key]}
                  onChange={(event) =>
                    setGrants((current) => ({
                      ...current,
                      [option.key]: event.target.checked,
                    }))
                  }
                />
                민감한 문서도 읽기
              </Label>
            ) : null}
          </div>
        ))}
      </fieldset>
      {error ? (
        <p role="alert" className="mt-3 text-sm text-destructive">
          {error}
        </p>
      ) : null}
      <Button
        type="submit"
        disabled={selected.length === 0}
        loading={busy}
        loadingText="만드는 중"
        className="mt-4"
      >
        토큰 만들기
      </Button>
    </form>
  );
}
