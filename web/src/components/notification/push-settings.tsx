"use client";

import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Notice } from "@/components/ui/notice";
import { Skeleton } from "@/components/ui/skeleton";
import { formatFullTime } from "@/lib/format";
import type { PushDevice } from "@/lib/push";
import type { PushScreenState } from "@/lib/push-device";
import { IosInstallGuide } from "./ios-install-guide";
import { usePushSettings } from "./use-push-settings";

/** 단추를 보일 수 없는 상태의 까닭과 다시 켜는 방법이다(스펙 REQ3). */
const BLOCKED_NOTICES: Partial<Record<PushScreenState, string>> = {
  "server-off": "이 서버는 아직 알림을 보낼 수 없어요.",
  "ios-old":
    "iOS 16.4 이상에서 받을 수 있어요. 기기를 업데이트한 뒤 이 화면을 다시 열어 주세요.",
  unsupported:
    "이 브라우저는 알림을 받을 수 없어요. 최신 Chrome, Edge, Firefox, Safari 에서 열어 주세요.",
  denied:
    "이 브라우저에서 알림을 막아 두었어요. 주소창 옆 사이트 설정에서 알림을 허용한 뒤 이 화면을 다시 열어 주세요.",
};

/**
 * 「알림」 화면의 이 기기 상태와 내 기기 목록이다(docs/features/attention/spec.md 의 REQ1-REQ3).
 */
export function PushSettings() {
  const { view, busy, actionError, enable, disable, reload } =
    usePushSettings();

  if (view.phase === "loading")
    return (
      <Skeleton aria-label="알림 설정을 읽는 중" className="h-24 w-full" />
    );
  if (view.phase === "failed")
    return (
      <Notice variant="error" role="alert">
        <p>{view.message}</p>
        <Button
          variant="outline"
          size="sm"
          className="mt-2"
          onClick={() => void reload()}
        >
          다시 읽기
        </Button>
      </Notice>
    );

  const blocked = BLOCKED_NOTICES[view.state];
  return (
    <div className="space-y-6">
      <section aria-labelledby="this-device-title" className="space-y-3">
        <h2 id="this-device-title" className="text-base font-semibold">
          이 기기
        </h2>
        {view.state === "ios-install" ? <IosInstallGuide /> : null}
        {blocked ? (
          <Notice variant="info" role="status">
            {blocked}
          </Notice>
        ) : null}
        {view.state === "ready" ? (
          <Button onClick={() => void enable()} disabled={busy}>
            이 기기에서 알림 받기
          </Button>
        ) : null}
        {view.state === "registered" ? (
          <div className="flex flex-wrap items-center gap-3">
            <p className="text-sm">이 기기는 알림을 받고 있어요</p>
            <Button
              variant="outline"
              onClick={() => void disable()}
              disabled={busy}
            >
              이 기기에서 그만 받기
            </Button>
          </div>
        ) : null}
        {actionError ? (
          <Notice variant="error" role="alert">
            {actionError}
          </Notice>
        ) : null}
      </section>
      <DeviceList devices={view.devices} thisHash={view.thisHash} />
    </div>
  );
}

function DeviceList({
  devices,
  thisHash,
}: {
  devices: PushDevice[];
  thisHash: string | null;
}) {
  return (
    <section aria-labelledby="devices-title" className="space-y-3">
      <h2 id="devices-title" className="text-base font-semibold">
        알림을 받는 기기
      </h2>
      {devices.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          알림을 받는 기기가 없어요.
        </p>
      ) : (
        <ul className="divide-y divide-border rounded-lg border border-border">
          {devices.map((device) => (
            <li
              key={device.id}
              data-testid="push-device"
              className="flex flex-wrap items-center gap-2 px-4 py-3"
            >
              <span className="font-medium">{device.name}</span>
              {device.endpointHash === thisHash ? (
                <Badge variant="info">이 기기</Badge>
              ) : null}
              <span className="ml-auto text-xs text-muted-foreground">
                {formatFullTime(device.registeredAt)} 등록
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
