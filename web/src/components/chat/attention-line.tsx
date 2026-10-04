"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { readNowCount } from "@/lib/attention-api";

/**
 * 새 대화 화면의 「확인할 것 N건」 한 줄이다. 홈의 첫 표시를 기다리게 하지 않으려고 그린 뒤에 읽는다.
 * 0 건이거나 읽지 못하면 아무것도 그리지 않는다. 자리는 늘 같은 높이로 잡아 한 줄이 생겨도 입력창이 밀리지 않는다.
 */
export function AttentionLine() {
  const [nowCount, setNowCount] = useState(0);
  useEffect(() => {
    let stale = false;
    void readNowCount().then((count) => {
      if (!stale) setNowCount(count);
    });
    return () => {
      stale = true;
    };
  }, []);

  return (
    <div className="mb-4 flex h-5 items-center justify-center">
      {nowCount > 0 ? (
        <Link
          href="/now"
          prefetch={false}
          data-testid="attention-line"
          className="text-sm text-muted-foreground hover:text-foreground"
        >
          확인할 것 {nowCount}건
        </Link>
      ) : null}
    </div>
  );
}
