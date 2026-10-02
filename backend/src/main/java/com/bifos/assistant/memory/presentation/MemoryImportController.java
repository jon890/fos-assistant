package com.bifos.assistant.memory.presentation;

import com.bifos.assistant.memory.application.MemoryImportService;
import com.bifos.assistant.memory.application.model.MemoryImportItem;
import com.bifos.assistant.memory.application.model.MemoryImportOutcome;
import com.bifos.assistant.memory.domain.type.MemoryImportStatus;
import com.bifos.assistant.memory.presentation.MemoryDtos.ImportItemBody;
import com.bifos.assistant.memory.presentation.MemoryDtos.ImportOutcomeView;
import com.bifos.assistant.memory.presentation.MemoryDtos.ImportRequest;
import com.bifos.assistant.memory.presentation.MemoryDtos.ImportResponse;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주인이 검토한 묶음을 들이는 API 다(ADR-058). 웹의 JWT 로만 부르고 요청자가 주인이 된다. 서비스 토큰과 에이전트의
 * 토큰으로 부르는 길은 없다.
 */
@RestController
@RequestMapping("/api/v1/memory-imports")
@RequiredArgsConstructor
public class MemoryImportController {

    private static final int SCHEMA_VERSION = 1;

    private final MemoryImportService imports;
    private final CurrentUserProvider currentUser;

    /** 묶음을 대조만 한다. 저장하지 않는다. */
    @PostMapping("/preview")
    public ResponseEntity<ImportResponse> preview(@Valid @RequestBody ImportRequest request) {
        CurrentUser user = currentUser.require();
        requireSchema(request);
        return noStore(imports.preview(user, items(request)));
    }

    /** 같은 묶음의 NEW 인 항목만 한 트랜잭션으로 저장한다. */
    @PostMapping
    public ResponseEntity<ImportResponse> commit(@Valid @RequestBody ImportRequest request) {
        CurrentUser user = currentUser.require();
        requireSchema(request);
        return noStore(imports.commit(user, items(request)));
    }

    private static void requireSchema(ImportRequest request) {
        if (request.schemaVersion() != SCHEMA_VERSION) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "schemaVersion must be 1");
        }
    }

    private static List<MemoryImportItem> items(ImportRequest request) {
        return request.items().stream().map(MemoryImportController::toItem).toList();
    }

    private static MemoryImportItem toItem(ImportItemBody body) {
        return new MemoryImportItem(
                body.sourceRef(),
                body.sourceDate(),
                body.collection(),
                body.entryType(),
                body.documentKey(),
                body.title(),
                body.content(),
                body.sensitive(),
                body.retrieval());
    }

    private static ResponseEntity<ImportResponse> noStore(List<MemoryImportOutcome> outcomes) {
        List<ImportOutcomeView> views = outcomes.stream()
                .map(outcome -> new ImportOutcomeView(
                        outcome.index(), outcome.status().name(), outcome.reason(), outcome.memoryId()))
                .toList();
        ImportResponse body = new ImportResponse(
                count(outcomes, MemoryImportStatus.NEW),
                count(outcomes, MemoryImportStatus.DUPLICATE),
                count(outcomes, MemoryImportStatus.CONFLICT),
                count(outcomes, MemoryImportStatus.REJECTED),
                views);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static int count(List<MemoryImportOutcome> outcomes, MemoryImportStatus status) {
        return (int)
                outcomes.stream().filter(outcome -> outcome.status() == status).count();
    }
}
