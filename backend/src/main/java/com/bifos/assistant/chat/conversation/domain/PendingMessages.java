package com.bifos.assistant.chat.conversation.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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


    /**
     * 끼워 넣기를 기다리는 글이 있다. 그 답이 올 때까지 다음 turn 을 열지 않는다. 그 사이 위임 결과 turn 이 사용자 글을 앞지르면
     * 안 된다(ADR-048, ADR-20261116 / run-steer).
     */
    public boolean blockedBySteering() {
        return items.stream().anyMatch(PendingMessage::steering);
    }


    /** 상한을 세는 글의 수다. 말풍선이 이미 있는 글은 세지 않는다(ADR-20261116 / run-steer). */
    public int countable() {
        return (int)items.stream().filter(item -> !item.carried()).count();
    }


    public Optional<PendingMessage> find(Long id) {
        return items.stream().filter(item -> item.id().equals(id)).findFirst();
    }


    /** 말풍선이 이미 있는 글의 메시지 번호다. 줄 순서다. */
    public List<Long> carriedMessageIds() {
        return items.stream().filter(PendingMessage::carried).map(PendingMessage::messageId).toList();
    }


    /** 새 사용자 메시지가 될 글이다. 말풍선이 없는 글만 {@link #SEPARATOR} 로 잇는다. 없으면 빈 글이다. */
    public String questionText() {
        return items.stream().filter(item -> !item.carried()).map(PendingMessage::content)
            .collect(Collectors.joining(SEPARATOR));
    }


    public List<Long> ids() {
        return items.stream().map(PendingMessage::id).toList();
    }


    /** 받은 순서대로 글을 {@link #SEPARATOR} 로 잇는다. */
    public String merged() {
        return items.stream().map(PendingMessage::content).collect(Collectors.joining(SEPARATOR));
    }


    /**
     * 새 사용자 메시지가 될 글을 이은 것에 {@code added} 를 더 이었을 때의 길이다. 말풍선이 이미 있는 글은 세지 않는다.
     *
     * <p>{@code added} 가 null 이면 지금 글만 센다. 셀 글이 없으면 사이에 넣을 것이 없어 {@code added} 의 길이만 센다.
     */
    public int mergedLength(String added) {
        int length = questionText().length();
        if (added == null) {
            return length;
        }
        if (countable() > 0) {
            length += SEPARATOR.length();
        }
        return length + added.length();
    }


    /**
     * 글 하나를 끝에 더한다. 멈춘 줄에 더하면 그 글도 멈춘 채로 들어간다.
     *
     * <p>번호는 줄의 가장 큰 번호보다 크고 받은 시각의 마이크로초 이상이다. 줄이 비면 번호를 기억하지 않으므로, 시각을 넣어 이미
     * 보낸 글의 번호를 새 글이 다시 받지 않게 한다. 보낸 글의 번호로 늦게 온 취소가 새 글을 지우면 안 된다.
     */
    public PendingMessages append(String content, Instant now) {
        List<PendingMessage> next = new ArrayList<>(items);
        next.add(new PendingMessage(nextId(now), content, now));
        return new PendingMessages(held, next);
    }


    /**
     * 말풍선이 이미 있는 글들을 줄 앞에 받은 순서대로 넣는다(ADR-20261116 / run-steer). 멈춤은 그대로 둔다.
     *
     * <p>번호는 줄의 가장 큰 번호와 받은 시각의 마이크로초보다 크게 차례로 준다. 앞에 넣어도 번호가 겹치지 않는다.
     */
    public PendingMessages prependCarried(List<Carried> carried, Instant now) {
        long id = nextId(now);
        List<PendingMessage> front = new ArrayList<>();
        for (Carried item : carried) {
            front.add(new PendingMessage(id++, item.content(), now, item.messageId(), false));
        }
        front.addAll(items);
        return new PendingMessages(held, front);
    }


    /** 그 글의 「끼우는 중」 표시를 바꾼다. 없는 번호면 그대로다. */
    public PendingMessages withSteering(Long id, boolean value) {
        return new PendingMessages(
            held, items.stream().map(item -> item.id().equals(id) ? item.withSteering(value) : item).toList());
    }


    private long nextId(Instant now) {
        long max = items.stream().mapToLong(PendingMessage::id).max().orElse(0L);
        long fromClock = ChronoUnit.MICROS.between(Instant.EPOCH, now);
        return Math.max(max + 1, fromClock);
    }


    /** 줄 앞에 되돌릴 말풍선 글이다. */
    public record Carried(Long messageId, String content) {
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
