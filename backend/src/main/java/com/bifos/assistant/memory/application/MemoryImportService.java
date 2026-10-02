package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.application.model.MemoryImportItem;
import com.bifos.assistant.memory.application.model.MemoryImportOutcome;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.MemoryCollection;
import com.bifos.assistant.memory.domain.MemoryPlacement;
import com.bifos.assistant.memory.domain.StoredContent;
import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryImportStatus;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주인이 검토한 묶음을 대조하고 들인다(ADR-058).
 *
 * <p>미리보기와 저장은 같은 판정을 쓴다. 저장은 묶음 전체가 한 트랜잭션이고 NEW 가 아닌 항목은 건너뛴다. 그래서 같은
 * 묶음을 다시 올려도 된다. 들인 줄은 요청자가 주인인 USER 범위이고 곧 ACCEPTED 다. GROUP 범위로 들이는 길은 없다.
 *
 * <p>대조에 본문을 쓰지 않는다. 민감 본문은 암호문이라 견줄 수 없고 해시를 두면 지문이 남는다. 로그에는 사용자 번호와
 * 결과별 개수만 적는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryImportService {

    public static final String SOURCE_TYPE = "brain";
    public static final int MAX_ITEMS = 100;
    public static final int MAX_CONTENT_CHARS = 12000;

    /** 신원 항목은 암호화와 읽기 경계와 권한을 운영에서 확인한 뒤에 연다. 그때까지 들이지 않는다. */
    public static final String IDENTITY_COLLECTION = "identity";

    private static final int MAX_SOURCE_REF_CHARS = 512;
    private static final int MAX_TITLE_CHARS = 200;
    private static final Pattern DOCUMENT_KEY = Pattern.compile("[a-z0-9][a-z0-9-]{0,127}");

    private final MemoryRepository memories;
    private final MemoryService memoryService;
    private final MemoryContentCipher cipher;
    private final Clock clock;

    /** 항목마다 판정만 한다. 저장하지 않는다. */
    @Transactional
    public List<MemoryImportOutcome> preview(CurrentUser user, List<MemoryImportItem> items) {
        return judge(user, items).stream().map(Judgement::outcome).toList();
    }

    /** 같은 판정 뒤 NEW 인 항목만 저장한다. */
    @Transactional
    public List<MemoryImportOutcome> commit(CurrentUser user, List<MemoryImportItem> items) {
        List<Judgement> judgements = judge(user, items);
        List<Memory> created = new ArrayList<>();
        for (Judgement judgement : judgements) {
            if (judgement.status() == MemoryImportStatus.NEW) {
                created.add(toMemory(user, judgement.item()));
            }
        }
        List<Memory> saved;
        try {
            saved = memories.saveAllAndFlush(created);
        } catch (DataIntegrityViolationException e) {
            // 두 요청이 같은 묶음을 함께 올린 경우다. 트랜잭션이 통째로 되돌아간다
            throw new ApiException(ErrorCode.MEMORY_IMPORT_RETRY, "the bundle changed while importing");
        }
        List<MemoryImportOutcome> outcomes = new ArrayList<>();
        int next = 0;
        for (Judgement judgement : judgements) {
            MemoryImportOutcome outcome = judgement.outcome();
            if (judgement.status() == MemoryImportStatus.NEW) {
                outcome = new MemoryImportOutcome(
                        outcome.index(),
                        outcome.sourceRef(),
                        outcome.status(),
                        null,
                        saved.get(next++).id());
            }
            outcomes.add(outcome);
        }
        log.info(
                "memory import userId={} new={} duplicate={} conflict={} rejected={}",
                user.id(),
                count(outcomes, MemoryImportStatus.NEW),
                count(outcomes, MemoryImportStatus.DUPLICATE),
                count(outcomes, MemoryImportStatus.CONFLICT),
                count(outcomes, MemoryImportStatus.REJECTED));
        return outcomes;
    }

    private Memory toMemory(CurrentUser user, MemoryImportItem item) {
        MemoryEntryType entryType = MemoryEntryType.valueOf(item.entryType());
        MemorySensitivity sensitivity = item.sensitive() ? MemorySensitivity.SENSITIVE : MemorySensitivity.NORMAL;
        // 암호화는 저장 전에 한다. key 가 없으면 judge 가 이미 요청 전체를 거절했다
        StoredContent body = item.sensitive()
                ? cipher.seal(item.content(), "USER:" + user.id())
                : StoredContent.plain(item.content());
        MemoryPlacement placement =
                new MemoryPlacement(item.collection(), MemoryRetrieval.valueOf(item.retrieval()), sensitivity);
        return Memory.imported(
                user.id(),
                entryType,
                item.documentKey(),
                item.title(),
                body,
                placement,
                SOURCE_TYPE,
                item.sourceRef(),
                item.sourceDate(),
                clock.instant());
    }

    private List<Judgement> judge(CurrentUser user, List<MemoryImportItem> items) {
        if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "a bundle must have 1 to 100 items");
        }
        Set<String> collectionKeys = memoryService.collectionsFor(user).stream()
                .map(MemoryCollection::key)
                .collect(Collectors.toSet());
        Set<String> knownRefs = memories
                .findByScopeAndOwnerUserIdAndSourceTypeAndSourceRefIn(
                        MemoryScope.USER,
                        user.id(),
                        SOURCE_TYPE,
                        items.stream()
                                .map(MemoryImportItem::sourceRef)
                                .filter(ref -> ref != null && ref.length() <= MAX_SOURCE_REF_CHARS)
                                .toList())
                .stream()
                .map(Memory::sourceRef)
                .collect(Collectors.toSet());

        Set<String> seenRefs = new HashSet<>();
        Set<String> newDocumentKeys = new HashSet<>();
        List<Judgement> judgements = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            MemoryImportItem item = items.get(index);
            Verdict verdict = verdictOf(user, item, collectionKeys, knownRefs, seenRefs, newDocumentKeys);
            judgements.add(new Judgement(index, item, verdict.status(), verdict.reason()));
        }
        boolean sealRequired = judgements.stream()
                .anyMatch(judgement -> judgement.status() == MemoryImportStatus.NEW
                        && judgement.item().sensitive());
        if (sealRequired && !cipher.enabled()) {
            throw new ApiException(ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE, "Memory encryption key is not configured");
        }
        return judgements;
    }

    /** 항목 하나의 판정이다. 위에서부터 보고 먼저 걸린 것으로 답한다. */
    private Verdict verdictOf(
            CurrentUser user,
            MemoryImportItem item,
            Set<String> collectionKeys,
            Set<String> knownRefs,
            Set<String> seenRefs,
            Set<String> newDocumentKeys) {
        if (!fieldsValid(item)) {
            return Verdict.rejected("INVALID_FIELD");
        }
        if (item.content().length() > MAX_CONTENT_CHARS) {
            return Verdict.rejected("CONTENT_TOO_LONG");
        }
        if (!collectionKeys.contains(item.collection())) {
            return Verdict.rejected("UNKNOWN_COLLECTION");
        }
        if (IDENTITY_COLLECTION.equals(item.collection())) {
            return Verdict.rejected("IDENTITY_HELD");
        }
        MemoryEntryType entryType = MemoryEntryType.valueOf(item.entryType());
        if (!retrievalAllowed(entryType, MemoryRetrieval.valueOf(item.retrieval()), item.sensitive())) {
            return Verdict.rejected("RETRIEVAL_NOT_ALLOWED");
        }
        String documentIdentity =
                entryType == MemoryEntryType.DOCUMENT ? item.collection() + "/" + item.documentKey() : null;
        boolean repeatedRef = !seenRefs.add(item.sourceRef());
        if (repeatedRef || (documentIdentity != null && newDocumentKeys.contains(documentIdentity))) {
            return Verdict.rejected("DUPLICATE_IN_BUNDLE");
        }
        if (knownRefs.contains(item.sourceRef())) {
            return new Verdict(MemoryImportStatus.DUPLICATE, null);
        }
        if (entryType == MemoryEntryType.DOCUMENT
                && memories.findByScopeAndOwnerUserIdAndCollectionAndDocumentKey(
                                MemoryScope.USER, user.id(), item.collection(), item.documentKey())
                        .isPresent()) {
            return new Verdict(MemoryImportStatus.CONFLICT, "DOCUMENT_KEY_TAKEN");
        }
        if (entryType == MemoryEntryType.MEMORY
                && memories.existsByScopeAndOwnerUserIdAndEntryTypeAndCollectionAndTitle(
                        MemoryScope.USER, user.id(), MemoryEntryType.MEMORY, item.collection(), item.title())) {
            return new Verdict(MemoryImportStatus.CONFLICT, "TITLE_TAKEN");
        }
        if (documentIdentity != null) {
            newDocumentKeys.add(documentIdentity);
        }
        return new Verdict(MemoryImportStatus.NEW, null);
    }

    private static boolean fieldsValid(MemoryImportItem item) {
        if (item.sourceRef() == null
                || item.sourceRef().isBlank()
                || item.sourceRef().length() > MAX_SOURCE_REF_CHARS
                || item.title() == null
                || item.title().isBlank()
                || item.title().length() > MAX_TITLE_CHARS
                || item.content() == null
                || item.content().isBlank()
                || !isOneOf(item.entryType(), MemoryEntryType.values())
                || !isOneOf(item.retrieval(), MemoryRetrieval.values())
                || !MemoryPlacement.isCollectionKey(item.collection())) {
            return false;
        }
        if (MemoryEntryType.DOCUMENT.name().equals(item.entryType())) {
            return item.documentKey() != null
                    && DOCUMENT_KEY.matcher(item.documentKey()).matches();
        }
        return item.documentKey() == null;
    }

    private static boolean isOneOf(String value, Enum<?>[] candidates) {
        for (Enum<?> candidate : candidates) {
            if (candidate.name().equals(value)) {
                return true;
            }
        }
        return false;
    }

    /** 문서는 SEARCH, 원문은 ARCHIVE, 기억은 ALWAYS 나 SEARCH 만 받는다. 민감 항목은 ALWAYS 가 아니어야 한다. */
    private static boolean retrievalAllowed(MemoryEntryType entryType, MemoryRetrieval retrieval, boolean sensitive) {
        boolean kindAllows = switch (entryType) {
            case DOCUMENT -> retrieval == MemoryRetrieval.SEARCH;
            case SOURCE -> retrieval == MemoryRetrieval.ARCHIVE;
            case MEMORY -> retrieval != MemoryRetrieval.ARCHIVE;
        };
        return kindAllows && MemoryPlacement.allows(retrieval, sensitivity(sensitive));
    }

    private static MemorySensitivity sensitivity(boolean sensitive) {
        return sensitive ? MemorySensitivity.SENSITIVE : MemorySensitivity.NORMAL;
    }

    private static long count(List<MemoryImportOutcome> outcomes, MemoryImportStatus status) {
        return outcomes.stream().filter(outcome -> outcome.status() == status).count();
    }

    private record Verdict(MemoryImportStatus status, String reason) {
        static Verdict rejected(String reason) {
            return new Verdict(MemoryImportStatus.REJECTED, reason);
        }
    }

    private record Judgement(int index, MemoryImportItem item, MemoryImportStatus status, String reason) {
        MemoryImportOutcome outcome() {
            return new MemoryImportOutcome(index, item.sourceRef(), status, reason, null);
        }
    }
}
