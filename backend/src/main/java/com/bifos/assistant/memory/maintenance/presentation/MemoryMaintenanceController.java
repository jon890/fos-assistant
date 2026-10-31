package com.bifos.assistant.memory.maintenance.presentation;

import com.bifos.assistant.memory.maintenance.application.MemoryMaintenanceService;
import com.bifos.assistant.memory.maintenance.presentation.MaintenanceDtos.MaintenanceCandidateView;
import com.bifos.assistant.memory.maintenance.presentation.MaintenanceDtos.MaintenanceReviewRequest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 기억 정비 후보를 보이고 확인한다(ADR-20261015 / memory-maintenance). 확인해도 기억은 바뀌지 않는다. */
@RestController
@RequestMapping("/api/v1/memory/maintenance-candidates")
@RequiredArgsConstructor
public class MemoryMaintenanceController {
    private final MemoryMaintenanceService maintenance;
    private final CurrentUserProvider currentUser;


    @GetMapping
    public List<MaintenanceCandidateView> candidates() {
        return maintenance.candidatesFor(currentUser.require()).stream().map(MaintenanceCandidateView::from).toList();
    }


    /** 주소의 번호는 후보 사건의 번호다. 고칠 수 없는 사람과 없는 후보는 같은 404 다. */
    @PutMapping("/{id}/review")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void review(@PathVariable Long id, @Valid @RequestBody MaintenanceReviewRequest request) {
        CurrentUser user = currentUser.require();
        maintenance.review(user, id, request.review());
    }
}
