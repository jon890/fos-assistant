package com.bifos.assistant.context;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/** 문맥 예산에 맞춰 글과 누락 항목을 조립한다. 상태는 조립 호출마다 새로 만든다. */
@Slf4j
final class ContextBudgetWriter {
    /** 제목과 항목, 항목과 항목 사이에 넣는 구분 줄이다. */
    static final String SEPARATOR = "\n\n";

    private final long maxChars;
    private final StringBuilder full = new StringBuilder();
    private final StringBuilder selected = new StringBuilder();
    private final Set<String> fullHeaders = new HashSet<>();
    private final Set<String> selectedHeaders = new HashSet<>();
    private final List<Long> omitted = new ArrayList<>();
    private final List<ContextItem> items = new ArrayList<>();

    /** 지금 담는 층이 쓸 수 있는 자리다. 층을 옮길 때마다 바꾼다. */
    private long limit;


    ContextBudgetWriter(long maxChars) {
        this.maxChars = maxChars;
        this.limit = maxChars;
    }


    void limit(long limit) {
        this.limit = Math.max(0, Math.min(limit, maxChars));
    }


    /** 지금 층에 남은 자리다. */
    long room() {
        return Math.max(0, limit - selected.length());
    }


    boolean isEmpty() {
        return selected.isEmpty();
    }


    /**
     * 남은 자리에 들어가면 담고 참을 돌려준다. 들어가지 않으면 아무것도 남기지 않고 거짓을 돌려준다.
     *
     * <p>{@link #append} 와 달리 빠진 항목으로 세지 않는다. 그 항목은 부르는 쪽이 다른 층으로 돌린다.
     */
    boolean appendIfFits(String header, String line, ContextItem item) {
        String added = next(selected, selectedHeaders, header, line);
        if (selected.length() + added.length() > limit) {
            return false;
        }
        appendTo(full, fullHeaders, header, line);
        appendTo(selected, selectedHeaders, header, line);
        items.add(item);
        return true;
    }


    /**
     * 항목 하나를 담는다.
     *
     * <p>그 항목이 남은 자리에 들어가지 않으면 <b>그 항목만</b> 빼고 다음 항목을 계속 본다. 한 번
     * 넘쳤다고 뒤를 모두 멈추면 본문이 긴 항목 하나가 나머지와 색인까지 밀어낸다.
     *
     * <p>넘친 항목을 잘라서 싣지도 않는다. 잘린 사실은 틀린 사실이 될 수 있다. 뺀 항목은 묶음의 원래 자리에
     * {@link ContextBodyMode#OMITTED} 로 남긴다.
     */
    void append(Long memoryId, String header, String line, ContextItem item) {
        appendTo(full, fullHeaders, header, line);
        String lineWithHeader = next(selected, selectedHeaders, header, line);
        if (selected.length() + lineWithHeader.length() > limit) {
            omitted.add(memoryId);
            items.add(item.withBodyMode(ContextBodyMode.OMITTED));
            return;
        }
        appendTo(selected, selectedHeaders, header, line);
        items.add(item);
    }


    private static String next(StringBuilder value, Set<String> headers, String header, String item) {
        return next(value.isEmpty(), headers.contains(header), header, item);
    }


    /** 앞 글이 비었는지와 머리가 이미 있는지로 다음에 붙을 글을 만든다. 담기와 고르기가 같은 규칙을 쓴다. */
    static String next(boolean empty, boolean headed, String header, String item) {
        String prefix = empty ? "" : SEPARATOR;
        return prefix + (headed ? item : header + SEPARATOR + item);
    }


    static void appendTo(StringBuilder value, Set<String> headers, String header, String item) {
        value.append(next(value, headers, header, item));
        headers.add(header);
    }


    AssembledContext build() {
        if (!omitted.isEmpty()) {
            log.warn("memory context omitted items={} chars={}", omitted.size(), full.length() - selected.length());
        }
        ContextBundle bundle = new ContextBundle(items);
        return selected.isEmpty()
            ? new AssembledContext(null, 0, omitted, bundle)
            : new AssembledContext(selected.toString(), selected.length(), omitted, bundle);
    }
}
