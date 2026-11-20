"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { describeFailure } from "@/components/error-message";
import {
  currentSubscription,
  deleteCurrentPushDevice,
  endpointHash,
  fetchPushConfig,
  fetchPushDevices,
  isStandalone,
  notificationPermission,
  pushSupported,
  putPushDevice,
  registrationBody,
  subscribe,
  type PushConfig,
  type PushDevice,
} from "@/lib/push";
import {
  deniedHelp,
  deviceName,
  iosInfo,
  pushScreenState,
  type DeniedHelp,
  type PushScreenState,
} from "@/lib/push-device";

export type PushSettingsView =
  | { phase: "loading" }
  | { phase: "failed"; message: string }
  | {
      phase: "ready";
      state: PushScreenState;
      devices: PushDevice[];
      /** 이 브라우저 구독의 주소 해시다. 구독이 없으면 null 이다 */
      thisHash: string | null;
      /** 알림을 막았을 때 다시 켜는 곳이다 */
      denied: DeniedHelp;
    };

/** 등록이 이 코드로 거절되면 서버의 공개 키가 바뀐 것이라 다시 구독한다 */
const KEY_CHANGED = "PUSH_KEY_CHANGED";
/** 등록이 이 코드로 거절되면 서버에 키가 없다 */
const NOT_CONFIGURED = "PUSH_NOT_CONFIGURED";

type Loaded = { config: PushConfig; devices: PushDevice[] };

/** 서버 설정과 기기 목록을 읽는다. 푸시 경로가 아직 없는 서버(404)는 꺼진 것으로 본다. */
async function loadServer(): Promise<Loaded | { failure: string }> {
  const configResponse = await fetchPushConfig();
  if (configResponse.status === 404)
    return { config: { enabled: false, publicKey: null }, devices: [] };
  if (!configResponse.ok)
    return { failure: await describeFailure(configResponse) };
  const config = (await configResponse.json()) as PushConfig;
  const devicesResponse = await fetchPushDevices();
  if (!devicesResponse.ok)
    return { failure: await describeFailure(devicesResponse) };
  return { config, devices: (await devicesResponse.json()) as PushDevice[] };
}

type ReadResult = { config: PushConfig | null; view: PushSettingsView };

/** 서버 설정, 기기 목록, 이 브라우저의 구독과 허락 상태를 읽어 화면 상태를 정한다. */
async function readView(): Promise<ReadResult> {
  try {
    const loaded = await loadServer();
    if ("failure" in loaded)
      return {
        config: null,
        view: { phase: "failed", message: loaded.failure },
      };
    const subscription = await currentSubscription().catch(() => null);
    const thisHash = subscription
      ? await endpointHash(subscription.endpoint)
      : null;
    const ios = iosInfo(navigator.userAgent, navigator.maxTouchPoints);
    const standalone = isStandalone();
    const state = pushScreenState({
      serverEnabled: loaded.config.enabled && Boolean(loaded.config.publicKey),
      supported: pushSupported(),
      permission: notificationPermission(),
      ios,
      standalone,
      registered:
        thisHash !== null &&
        loaded.devices.some((device) => device.endpointHash === thisHash),
    });
    return {
      config: loaded.config,
      view: {
        phase: "ready",
        state,
        devices: loaded.devices,
        thisHash,
        denied: deniedHelp(ios.ios, standalone),
      },
    };
  } catch {
    return {
      config: null,
      view: { phase: "failed", message: "알림 설정을 읽지 못했어요." },
    };
  }
}

async function readErrorCode(response: Response): Promise<string | null> {
  try {
    const body = (await response.clone().json()) as { code?: unknown };
    return typeof body.code === "string" ? body.code : null;
  } catch {
    return null;
  }
}

/**
 * 「알림」 화면의 이 기기 상태와 기기 목록이다(docs/features/attention/spec.md 의 REQ1-REQ3).
 *
 * <p>「이 기기」 는 브라우저 구독 주소의 해시가 서버 목록에 있는지로 정한다. 서버에서 지운 기기는 브라우저에 구독이
 * 남아 있어도 다시 등록하지 않고 단추를 보인다(스펙 DECISION21).
 */
export function usePushSettings() {
  const [view, setView] = useState<PushSettingsView>({ phase: "loading" });
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const config = useRef<PushConfig | null>(null);

  const apply = useCallback((read: ReadResult) => {
    config.current = read.config;
    setView(read.view);
  }, []);

  const reload = useCallback(async () => apply(await readView()), [apply]);

  useEffect(() => {
    // 화면을 연 뒤 한 번 읽는다. 읽기가 끝나면 상태를 바꾼다.
    void readView().then(apply);
  }, [apply]);

  /** 허락을 묻고 구독을 만들어 등록한다. 서버의 공개 키가 바뀌었으면 한 번만 다시 구독한다. */
  const enable = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    try {
      const permission = await Notification.requestPermission();
      if (permission !== "granted") return;
      const name = deviceName(
        navigator.userAgent,
        isStandalone(),
        navigator.maxTouchPoints,
      );
      for (let attempt = 0; attempt < 2; attempt += 1) {
        const publicKey = config.current?.publicKey;
        if (!publicKey) return;
        const subscription = await subscribe(publicKey);
        const response = await putPushDevice(
          registrationBody(subscription, name, publicKey),
        );
        if (response.ok) return;
        const code = await readErrorCode(response);
        if (code === KEY_CHANGED && attempt === 0) {
          await subscription.unsubscribe();
          const refreshed = await fetchPushConfig();
          if (!refreshed.ok) {
            setActionError(await describeFailure(refreshed));
            return;
          }
          config.current = (await refreshed.json()) as PushConfig;
          continue;
        }
        if (code === NOT_CONFIGURED) return;
        setActionError(await describeFailure(response));
        return;
      }
    } catch {
      setActionError("이 기기를 등록하지 못했어요. 잠시 뒤 다시 눌러 주세요.");
    } finally {
      await reload();
      setBusy(false);
    }
  }, [reload]);

  /** 이 기기를 서버 목록에서 빼고 브라우저 구독을 끊는다. */
  const disable = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    try {
      const subscription = await currentSubscription();
      if (subscription) {
        const response = await deleteCurrentPushDevice(subscription.endpoint);
        if (!response.ok) {
          setActionError(await describeFailure(response));
          return;
        }
        await subscription.unsubscribe();
      }
    } catch {
      setActionError("이 기기를 빼지 못했어요. 잠시 뒤 다시 눌러 주세요.");
    } finally {
      await reload();
      setBusy(false);
    }
  }, [reload]);

  return { view, busy, actionError, enable, disable, reload };
}
