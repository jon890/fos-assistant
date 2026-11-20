package com.bifos.assistant.notification.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 사용자 한 명의 웹 푸시 기기 목록이다(ADR-20261120 / web-push). 바꾸는 메서드는 새 목록을 돌려준다.
 *
 * <p>같은 주소(해시)의 기기는 하나뿐이다.
 */
public record PushDevices(List<PushDevice> devices) {

    private static final Comparator<PushDevice> NEWEST_FIRST = Comparator.comparing(PushDevice::registeredAt).reversed().thenComparing(device -> device.id().toString());


    public PushDevices {
        devices = devices == null ? List.of() : List.copyOf(devices);
    }


    public static PushDevices empty() {
        return new PushDevices(List.of());
    }


    /**
     * 같은 주소의 기기가 있으면 번호를 두고 키와 이름, 시간대, 등록 시각을 바꾸고, 없으면 더한다. 더해서 {@code max} 를 넘으면 등록 시각이
     * 가장 이른 기기를 뺀다.
     */
    public PushDevices upsert(PushDevice device, int max) {
        List<PushDevice> next = new ArrayList<>(devices.size() + 1);
        boolean replaced = false;
        for (PushDevice current : devices) {
            if (current.endpointHash().equals(device.endpointHash())) {
                next.add(current.refreshedBy(device));
                replaced = true;
            } else {
                next.add(current);
            }
        }
        if (!replaced) {
            next.add(device);
        }
        next.sort(NEWEST_FIRST);
        while (next.size() > max) {
            next.removeLast();
        }
        return new PushDevices(next);
    }


    public PushDevices removeByHash(String endpointHash) {
        return new PushDevices(devices.stream().filter(device -> !device.endpointHash().equals(endpointHash)).toList());
    }


    public PushDevices removeById(UUID id) {
        return new PushDevices(devices.stream().filter(device -> !device.id().equals(id)).toList());
    }


    public boolean containsHash(String endpointHash) {
        return devices.stream().anyMatch(device -> device.endpointHash().equals(endpointHash));
    }


    public boolean containsId(UUID id) {
        return devices.stream().anyMatch(device -> device.id().equals(id));
    }


    /** 같은 주소의 기기다. 없으면 null 이다. */
    public PushDevice findByHash(String endpointHash) {
        return devices.stream().filter(device -> device.endpointHash().equals(endpointHash)).findFirst().orElse(null);
    }


    /** 최근 등록 순이다. */
    public List<PushDevice> newestFirst() {
        return devices.stream().sorted(NEWEST_FIRST).toList();
    }


    public boolean isEmpty() {
        return devices.isEmpty();
    }
}
