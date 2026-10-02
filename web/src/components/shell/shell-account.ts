"use client";

import { createContext, useContext } from "react";
import type { ShellRoleState } from "./role-state";

/** 사이드바 맨 아래 줄이 읽는 값이다. 붙박이 사이드바와 서랍의 사이드바가 같은 값을 읽는다. */
export type ShellAccount = {
  state: ShellRoleState;
  displayName: string | null;
  /** 역할과 이름을 브라우저에서 다시 읽는다. */
  retry(): void;
};

export const ShellAccountContext = createContext<ShellAccount>({
  state: "reading",
  displayName: null,
  retry: () => {},
});

export function useShellAccount(): ShellAccount {
  return useContext(ShellAccountContext);
}
