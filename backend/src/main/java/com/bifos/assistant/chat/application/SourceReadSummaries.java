package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.type.EventObservation;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 비서 답의 실행 트리에서 원문 열람 완료 사건을 한 번에 모은다. */
@Component
@RequiredArgsConstructor
public class SourceReadSummaries {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern IPV4 = Pattern.compile("(?:\\d{1,3}\\.){3}\\d{1,3}");
    private final AgentExecutionRepository executions;
    private final ExecutionEventRepository events;

    public Map<Long, SourceReadSummary> of(List<ChatMessage> history) {
        Map<Long, Long> ids = new LinkedHashMap<>();
        history.forEach(message -> {
            if (message.role() == MessageRole.ASSISTANT) {
                ids.put(message.id(), message.executionId());
            }
        });
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, AgentExecution> asked = new LinkedHashMap<>();
        Set<Long> executionIds = ids.values().stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (!executionIds.isEmpty()) {
            executions.findAllById(executionIds).forEach(execution -> asked.put(execution.id(), execution));
        }
        Set<Long> roots = new LinkedHashSet<>();
        asked.values().forEach(execution -> roots.add(execution.treeRootId()));
        Map<Long, AgentExecution> rootRows = new LinkedHashMap<>();
        if (!roots.isEmpty()) {
            executions.findAllById(roots).forEach(execution -> rootRows.put(execution.id(), execution));
        }
        List<AgentExecution> children = List.of();
        if (!rootRows.isEmpty()) {
            children = executions.findByRootExecutionIdIn(rootRows.keySet());
        }
        Map<Long, List<AgentExecution>> childrenByParent = new LinkedHashMap<>();
        for (AgentExecution child : children) {
            if (child.parentExecutionId() != null) {
                childrenByParent
                        .computeIfAbsent(child.parentExecutionId(), unused -> new ArrayList<>())
                        .add(child);
            }
        }
        Set<Long> treeIds = new LinkedHashSet<>();
        asked.values().stream()
                .filter(execution -> rootRows.containsKey(execution.treeRootId()))
                .forEach(execution -> treeIds.add(execution.id()));
        children.forEach(execution -> treeIds.add(execution.id()));
        Map<Long, List<ExecutionEvent>> eventRows = new LinkedHashMap<>();
        if (!treeIds.isEmpty()) {
            events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(treeIds)
                    .forEach(event -> eventRows
                            .computeIfAbsent(event.executionId(), unused -> new ArrayList<>())
                            .add(event));
        }
        Map<Long, SourceReadSummary> result = new LinkedHashMap<>();
        Map<Long, SourceReadSummary> byExecution = new LinkedHashMap<>();
        ids.forEach((messageId, executionId) -> {
            AgentExecution execution = executionId == null ? null : asked.get(executionId);
            result.put(
                    messageId,
                    execution == null || !rootRows.containsKey(execution.treeRootId())
                            ? missing()
                            : byExecution.computeIfAbsent(
                                    executionId, unused -> summarize(execution, childrenByParent, eventRows)));
        });
        return result;
    }

    private static SourceReadSummary missing() {
        return new SourceReadSummary(0, List.of(), 0, false, List.of());
    }

    private static SourceReadSummary summarize(
            AgentExecution start,
            Map<Long, List<AgentExecution>> childrenByParent,
            Map<Long, List<ExecutionEvent>> eventRows) {
        List<AgentExecution> pending = new ArrayList<>();
        List<AgentExecution> tree = new ArrayList<>();
        Set<Long> visited = new LinkedHashSet<>();
        pending.add(start);
        for (int index = 0; index < pending.size(); index++) {
            AgentExecution execution = pending.get(index);
            if (!Objects.equals(start.userId(), execution.userId()) || !visited.add(execution.id())) {
                continue;
            }
            tree.add(execution);
            childrenByParent.getOrDefault(execution.id(), List.of()).forEach(pending::add);
        }
        int completed = 0;
        int unresolved = 0;
        boolean observed = true;
        Set<String> urls = new LinkedHashSet<>();
        Set<String> requestedUrls = new LinkedHashSet<>();
        for (AgentExecution execution : tree) {
            observed &= execution.eventObservation() == EventObservation.OBSERVED;
            ExecutionReads reads =
                    reads(eventRows.getOrDefault(execution.id(), List.of()), execution.eventObservation());
            completed += reads.completed;
            unresolved += reads.unresolved;
            urls.addAll(reads.urls);
            requestedUrls.addAll(reads.requestedUrls);
        }
        requestedUrls.removeAll(urls);
        return new SourceReadSummary(completed, List.copyOf(urls), unresolved, observed, List.copyOf(requestedUrls));
    }

    private static ExecutionReads reads(List<ExecutionEvent> events, EventObservation observation) {
        int completed = 0;
        int unresolved = 0;
        int open = 0;
        boolean overlapping = false;
        String startUrl = null;
        Set<String> urls = new LinkedHashSet<>();
        Set<String> requestedUrls = new LinkedHashSet<>();
        for (ExecutionEvent event : events) {
            if (!"web_extract".equals(event.toolName())) {
                continue;
            }
            if (event.eventType() == ExecutionEventType.TOOL_STARTED) {
                if (open == 0) {
                    startUrl = clean(event.detail());
                } else {
                    overlapping = true;
                }
                open++;
                continue;
            }
            if (event.eventType() != ExecutionEventType.TOOL_COMPLETED) {
                continue;
            }
            boolean paired = open == 1 && !overlapping;
            if (open > 0) {
                open--;
                if (open == 0) {
                    overlapping = false;
                    startUrl = paired ? startUrl : null;
                }
            }
            if (!Boolean.FALSE.equals(event.failed())) {
                continue;
            }
            Parsed parsed = parse(event.detail());
            completed++;
            unresolved += parsed.unresolved ? 1 : 0;
            urls.addAll(parsed.urls);
            if (paired && observation == EventObservation.OBSERVED && !parsed.resultKnown && startUrl != null) {
                requestedUrls.add(startUrl);
            }
        }
        return new ExecutionReads(completed, unresolved, urls, requestedUrls);
    }

    private static Parsed parse(String detail) {
        if (detail == null) {
            return Parsed.unknown();
        }
        try {
            JsonNode root = JSON.readTree(detail);
            if (root == null || !root.isObject()) {
                return Parsed.unknown();
            }
            if (root.path("blocked_by_policy").asBoolean(false)
                    || (root.has("success") && !root.path("success").asBoolean())) {
                return Parsed.empty();
            }
            JsonNode results = root.get("results");
            if (results == null || !results.isArray()) {
                return Parsed.unknown();
            }
            return parseResults(results);
        } catch (JacksonException ex) {
            return Parsed.unknown();
        }
    }

    private static Parsed parseResults(JsonNode results) {
        Set<String> urls = new LinkedHashSet<>();
        boolean knownOutcome = results.isEmpty();
        boolean unresolved = false;
        for (JsonNode result : results) {
            if (!result.isObject()) {
                unresolved = true;
                continue;
            }
            JsonNode content = result.get("content");
            JsonNode error = result.get("error");
            if (result.path("blocked_by_policy").asBoolean(false)
                    || (error != null && !error.isNull())
                    || (content != null && content.isString() && content.asString().isBlank())) {
                knownOutcome = true;
                continue;
            }
            if (content == null || !content.isString()) {
                unresolved = true;
                continue;
            }
            String url = clean(result.path("url").asString(null));
            if (url == null) {
                unresolved = true;
                continue;
            }
            knownOutcome = true;
            urls.add(url);
        }
        return new Parsed(knownOutcome, unresolved, urls);
    }

    private static boolean hidden(String value) {
        return value.contains("[가림]") || value.contains("[항목 ") || value.contains("...") || value.contains("…");
    }

    private static String clean(String value) {
        if (value == null || hidden(value)) {
            return null;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null
                    || host == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) {
                host = host.substring(0, host.length() - 1);
            }
            if (!host.contains(".")
                    || host.equals("localhost")
                    || host.endsWith(".localhost")
                    || host.endsWith(".local")
                    || host.endsWith(".internal")
                    || IPV4.matcher(host).matches()
                    || host.contains(":")) {
                return null;
            }
            String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            return scheme.toLowerCase(Locale.ROOT) + "://" + host + port + path;
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    private record Parsed(boolean resultKnown, boolean unresolved, Set<String> urls) {
        static Parsed empty() {
            return new Parsed(true, false, Set.of());
        }

        static Parsed unknown() {
            return new Parsed(false, true, Set.of());
        }
    }

    private record ExecutionReads(int completed, int unresolved, Set<String> urls, Set<String> requestedUrls) {}
}
