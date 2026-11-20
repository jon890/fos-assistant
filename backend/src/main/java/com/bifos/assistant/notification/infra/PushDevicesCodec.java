package com.bifos.assistant.notification.infra;

import com.bifos.assistant.notification.domain.PushDevice;
import com.bifos.assistant.notification.domain.PushDevices;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * {@code app_user.push_devices} 의 JSON 글과 기기 목록을 바꾼다(ADR-20261120 / web-push).
 *
 * <p>모양은 {@code {"version":1,"devices":[...]}} 다. 칸이 비면 기기가 없다. 모르는 버전이거나 깨진 글은 기기 없음으로 보지 않고
 * 오류로 올린다. 없음으로 보면 다음 쓰기가 기기를 모두 지운다. 오류 메시지에 글을 넣지 않는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PushDevicesCodec {

    private static final int VERSION = 1;
    private static final JsonMapper JSON = JsonMapper.builder().build();


    public static PushDevices decode(String value) {
        if (value == null || value.isBlank()) {
            return PushDevices.empty();
        }
        Stored stored;
        try {
            stored = JSON.readValue(value, Stored.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("push_devices is not valid JSON", ex);
        }
        if (stored == null || stored.version() == null || stored.version() != VERSION || stored.devices() == null) {
            throw new IllegalStateException("push_devices has an unknown version");
        }
        try {
            return new PushDevices(stored.devices().stream().map(StoredDevice::toDevice).toList());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("push_devices has a broken device", ex);
        }
    }


    /** 기기가 없으면 null 을 돌려줘 칸을 비운다. */
    public static String encode(PushDevices devices) {
        if (devices.isEmpty()) {
            return null;
        }
        List<StoredDevice> items = devices.newestFirst().stream().map(StoredDevice::from).toList();
        return JSON.writeValueAsString(new Stored(VERSION, items));
    }


    private record Stored(Integer version, List<StoredDevice> devices) {
    }

    private record StoredDevice(
        String id,
        String endpoint,
        String endpointHash,
        String p256dh,
        String auth,
        String name,
        String timeZone,
        String registeredAt) {

        static StoredDevice from(PushDevice device) {
            return new StoredDevice(
                device.id().toString(),
                device.endpoint(),
                device.endpointHash(),
                device.p256dh(),
                device.auth(),
                device.name(),
                device.timeZone(),
                device.registeredAt().toString());
        }


        PushDevice toDevice() {
            return new PushDevice(
                UUID.fromString(id), endpoint, endpointHash, p256dh, auth, name, timeZone, Instant.parse(registeredAt));
        }


        @Override
        public String toString() {
            return "StoredDevice[id=" + id + "]";
        }
    }
}
