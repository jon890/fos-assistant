"use client";

import type { RefObject } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect } from "@/components/ui/native-select";
import { webUrl, type ScreenInput } from "@/components/browser/screen-input";

export type ScreenTab = {
  id: string;
  title: string;
  url: string;
  active: boolean;
};

/** 로그인 화면의 주소 칸, 이동, 뒤로, 새로고침, 키보드, 닫기, 탭 고르기다. */
export function BrowserScreenToolbar({
  address,
  onAddress,
  addressRef,
  tabs,
  disabled,
  send,
  onNotice,
  onKeyboard,
  onClose,
}: {
  address: string;
  onAddress: (value: string) => void;
  addressRef: RefObject<HTMLInputElement | null>;
  tabs: ScreenTab[];
  disabled: boolean;
  send: (input: ScreenInput) => void;
  onNotice: (message: string | null) => void;
  onKeyboard: () => void;
  onClose: () => void;
}) {
  function navigate(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!webUrl(address)) {
      onNotice("http:// 나 https:// 로 시작하는 주소를 넣어 주세요.");
      return;
    }
    onNotice(null);
    send({ type: "navigate", url: address });
  }

  const active = tabs.find((tab) => tab.active);

  return (
    <>
      <form onSubmit={navigate} className="flex gap-2">
        <Input
          ref={addressRef}
          aria-label="주소"
          inputMode="url"
          autoCapitalize="off"
          value={address}
          onChange={(event) => onAddress(event.target.value)}
          disabled={disabled}
        />
        <Button type="submit" size="sm" disabled={disabled}>
          이동
        </Button>
      </form>
      <div className="flex flex-wrap items-center gap-2">
        <Button
          variant="outline"
          size="sm"
          disabled={disabled}
          onClick={() => send({ type: "back" })}
        >
          뒤로
        </Button>
        <Button
          variant="outline"
          size="sm"
          disabled={disabled}
          onClick={() => send({ type: "reload" })}
        >
          새로고침
        </Button>
        <Button
          variant="outline"
          size="sm"
          className="md:hidden"
          disabled={disabled}
          onClick={onKeyboard}
        >
          키보드
        </Button>
        <Button variant="ghost" size="sm" onClick={onClose}>
          닫기
        </Button>
        {tabs.length > 0 ? (
          <NativeSelect
            aria-label="탭"
            wrapperClassName="w-full sm:w-64"
            value={active?.id ?? ""}
            disabled={disabled}
            onChange={(event) => send({ type: "tab", id: event.target.value })}
          >
            {tabs.map((tab) => (
              <option key={tab.id} value={tab.id}>
                {tab.title || tab.url}
              </option>
            ))}
          </NativeSelect>
        ) : null}
      </div>
    </>
  );
}
