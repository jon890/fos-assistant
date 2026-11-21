"use client";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import type { ConnectorField, ConnectorOption } from "@/lib/connection";

/** 연결 화면의 입력 칸 하나다. 선택지 칸은 「불러오기」 로 받은 목록에서 고른다. */
export function ConnectorFieldInput({
  field,
  value,
  options,
  busy,
  loading,
  canLoad,
  onChange,
  onLoadOptions,
}: {
  field: ConnectorField;
  value: string;
  options: ConnectorOption[] | undefined;
  busy: boolean;
  /** 이 칸의 선택지를 불러오는 중이다. */
  loading: boolean;
  /** 비밀 칸이 모두 채워져 선택지를 부를 수 있다. */
  canLoad: boolean;
  onChange(value: string): void;
  onLoadOptions(): void;
}) {
  const id = `connector-field-${field.key}`;
  return (
    <div className="space-y-2">
      <Label htmlFor={id}>{field.label}</Label>
      {field.hasOptions ? (
        <div className="flex items-center gap-2">
          {options?.length ? (
            <NativeSelect
              id={id}
              value={value}
              disabled={busy}
              onChange={(event) => onChange(event.target.value)}
            >
              <option value="">선택</option>
              {options.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </NativeSelect>
          ) : null}
          <Button
            type="button"
            variant="outline"
            disabled={busy || !canLoad}
            loading={loading}
            loadingText="불러오는 중…"
            onClick={onLoadOptions}
          >
            불러오기
          </Button>
        </div>
      ) : (
        <Input
          id={id}
          name={field.key}
          type={field.secret ? "password" : "text"}
          autoComplete="off"
          value={value}
          disabled={busy}
          onChange={(event) => onChange(event.target.value)}
        />
      )}
      {field.description ? (
        <p className="text-sm text-muted-foreground">{field.description}</p>
      ) : null}
      {field.hasOptions && field.autoSelectSingle && options?.length === 1 ? (
        <p className="text-sm text-muted-foreground">
          하나뿐이라 자동으로 골랐어요.
        </p>
      ) : null}
    </div>
  );
}
