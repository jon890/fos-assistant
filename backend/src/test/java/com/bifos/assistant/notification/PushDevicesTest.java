package com.bifos.assistant.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.notification.domain.PushDevice;
import com.bifos.assistant.notification.domain.PushDevices;
import com.bifos.assistant.notification.infra.PushDevicesCodec;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 기기 목록의 갱신, 상한, JSON 왕복을 본다(ADR-20261120 / web-push). */
class PushDevicesTest {

    private static final Instant BASE = Instant.parse("2026-11-20T00:00:00Z");


    @Test
    @DisplayName("같은 주소로 다시 등록하면 번호는 그대로이고 키와 등록 시각이 바뀐다")
    void upsertKeepsId() {
        PushDevice first = device("h1", "old", 0);
        PushDevice again = new PushDevice(UUID.randomUUID(), "https://e/h1", "h1", "new", "auth2", "이름", "UTC", BASE.plusSeconds(60));

        PushDevices devices = PushDevices.empty().upsert(first, 10).upsert(again, 10);

        assertThat(devices.devices()).singleElement().satisfies(device -> {
            assertThat(device.id()).isEqualTo(first.id());
            assertThat(device.p256dh()).isEqualTo("new");
            assertThat(device.registeredAt()).isEqualTo(BASE.plusSeconds(60));
        });
    }


    @Test
    @DisplayName("상한을 넘으면 등록 시각이 가장 이른 기기가 빠진다")
    void upsertDropsOldest() {
        PushDevices devices = PushDevices.empty();
        for (int i = 0; i < 3; i++) {
            devices = devices.upsert(device("h" + i, "k", i), 3);
        }

        PushDevices next = devices.upsert(device("h9", "k", 9), 3);

        assertThat(next.devices()).extracting(PushDevice::endpointHash).containsExactly("h9", "h2", "h1");
    }


    @Test
    @DisplayName("저장된 기기의 등록 시각이 새 기기보다 뒤여도 방금 등록한 기기는 남는다")
    void upsertKeepsNewDeviceUnderClockSkew() {
        PushDevices devices = PushDevices.empty();
        for (int i = 0; i < 3; i++) {
            devices = devices.upsert(device("future" + i, "k", 3600 + i), 3);
        }

        PushDevices next = devices.upsert(device("now", "k", 0), 3);

        assertThat(next.devices()).extracting(PushDevice::endpointHash).contains("now").hasSize(3);
    }


    @Test
    @DisplayName("JSON 으로 쓰고 읽으면 같은 목록이고, 비면 칸을 비운다")
    void codecRoundTrip() {
        PushDevices devices = PushDevices.empty().upsert(device("h1", "k1", 1), 10).upsert(device("h2", "k2", 2), 10);

        PushDevices read = PushDevicesCodec.decode(PushDevicesCodec.encode(devices));

        assertThat(read).isEqualTo(devices);
        assertThat(PushDevicesCodec.encode(PushDevices.empty())).isNull();
        assertThat(PushDevicesCodec.decode(null).isEmpty()).isTrue();
    }


    @Test
    @DisplayName("모르는 버전이나 깨진 글은 기기 없음으로 보지 않고 오류다")
    void codecRejectsUnknown() {
        assertThatThrownBy(() -> PushDevicesCodec.decode("{\"version\":2,\"devices\":[]}")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PushDevicesCodec.decode("{broken")).isInstanceOf(IllegalStateException.class);
    }


    @Test
    @DisplayName("문자열로 적을 때 주소와 키를 내지 않는다")
    void toStringHidesSecrets() {
        PushDevice device = device("hash", "secret-key", 0);

        assertThat(device.toString()).doesNotContain("secret-key").doesNotContain("https://e/");
    }


    private static PushDevice device(String hash, String key, int seconds) {
        return new PushDevice(UUID.randomUUID(), "https://e/" + hash, hash, key, "auth", "이름", "UTC", BASE.plusSeconds(seconds));
    }
}
