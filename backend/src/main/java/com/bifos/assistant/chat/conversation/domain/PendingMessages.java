package com.bifos.assistant.chat.conversation.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 한 대화의 대기 줄이다(ADR-048). {@code conversation.pending_messages} 칸 하나가 이 값을 갖는다.
 *
 * <p>값이 바뀌지 않는다. 바꾸는 메서드는 새 값을 돌려준다. 메시지가 없으면 멈춤도 없다. 빈 줄을 저장하지 않으므로 비운
 * 뒤 새로 더한 글은 멈추지 않은 채 들어간다.
 *
 * @param held 참이면 사용자가 「보내기」 를 누를 때까지 자동으로 보내지 않는다
 * @param items 받은 순서다
 */
public record PendingMessages(boolean held, List<PendingMessage> items) {

    /** 쌓인 글을 한 turn 의 본문으로 이을 때 사이에 넣는 빈 줄이다. */
    public static final String SEPARATOR = "\n\n";

    public static final PendingMessages EMPTY = new PendingMessages(false, List.of());


    public PendingMessages {
        items = List.copyOf(items);
        held = held && !items.isEmpty();
    }


    public boolean isEmpty() {
        return items.isEmpty();
    }


    /** 보낼 글이 있고 멈추지 않았다. */
    public boolean sendable() {
        return !items.isEmpty() && !held;
    }


    public List<Long> ids() {
        return items.stream().map(PendingMessage::id).toList();
    }


    /** 받은 순서대로 글을 {@link #SEPARATOR} 로 잇는다. */
    public String merged() {
        return items.stream().map(PendingMessage::content).collect(Collectors.joining(SEPARATOR));
    }


    /**
     * 지금 글을 이은 것에 {@code added} 를 더 이었을 때의 길이다.
     *
     * <p>{@code added} 가 null 이면 지금 글만 센다. 비어 있으면 사이에 넣을 것이 없어 {@code added} 의 길이만 센다.
     */
    public int mergedLength(String added) {
        int length = merged().length();
        if (added == null) {
            return length;
        }
        if (!items.isEmpty()) {
            length += SEPARATOR.length();
        }
        return length + added.length();
    }


    /**
     * 글 하나를 끝에 더한다. 멈춘 줄에 더하면 그 글도 멈춘 채로 들어간다.
     *
     * <p>번호는 마지막 번호보다 크고 받은 시각의 마이크로초 이상이다. 줄이 비면 번호를 기억하지 않으므로, 시각을 넣어 이미
     * 보낸 글의 번호를 새 글이 다시 받지 않게 한다. 보낸 글의 번호로 늦게 온 취소가 새 글을 지우면 안 된다.
     */
    public PendingMessages append(String content, Instant now) {
        long last = items.isEmpty() ? 0L : items.getLast().id();
        long fromClock = ChronoUnit.MICROS.between(Instant.EPOCH, now);
        List<PendingMessage> next = new ArrayList<>(items);
        next.add(new PendingMessage(Math.max(last + 1, fromClock), content, now));
        return new PendingMessages(held, next);
    }


    public PendingMessages withHeld(boolean value) {
        return new PendingMessages(value, items);
    }


    /** {@code ids} 의 글을 뺀다. 멈춤은 그대로 둔다. */
    public PendingMessages without(Collection<Long> ids) {
        return new PendingMessages(held, items.stream().filter(item -> !ids.contains(item.id())).toList());
    }


    public boolean containsAll(Collection<Long> ids) {
        return ids().containsAll(ids);
    }


    public boolean containsAny(Collection<Long> ids) {
        return items.stream().anyMatch(item -> ids.contains(item.id()));
    }
}
