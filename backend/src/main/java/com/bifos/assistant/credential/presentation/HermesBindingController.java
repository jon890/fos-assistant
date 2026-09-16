package com.bifos.assistant.credential.presentation;

import com.bifos.assistant.credential.domain.CostMode;
import com.bifos.assistant.credential.domain.HermesProfileBinding;
import com.bifos.assistant.credential.infra.HermesProfileBindingRepository;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.infra.AppUserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Connects a family member to their own Hermes profile.
 *
 * <p>The profile's API key stays on the host: this endpoint only records which profile belongs to
 * whom, so no secret passes through the web tier.
 */
@RestController
@RequestMapping("/api/v1/admin/hermes-bindings")
public class HermesBindingController {

    private final HermesProfileBindingRepository bindings;
    private final AppUserRepository users;
    private final CurrentUserProvider currentUser;

    public HermesBindingController(
            HermesProfileBindingRepository bindings,
            AppUserRepository users,
            CurrentUserProvider currentUser) {
        this.bindings = bindings;
        this.users = users;
        this.currentUser = currentUser;
    }

    @PostMapping
    public BindingView create(@Valid @RequestBody CreateBindingRequest request) {
        currentUser.requireAdmin();
        var target =
                users
                        .findByEmail(request.email())
                        .orElseThrow(
                                () -> new ApiException(ErrorCode.VALIDATION_FAILED, "no such family member"));
        if (bindings.findByUserId(target.id()).isPresent()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "this member already has a profile bound");
        }
        if (bindings.findByProfileName(request.profileName()).isPresent()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "this profile is already bound to another member");
        }
        HermesProfileBinding saved =
                bindings.save(
                        HermesProfileBinding.of(
                                target.id(),
                                request.profileName(),
                                request.provider(),
                                request.model(),
                                request.costMode()));
        return BindingView.from(saved, target.email());
    }

    @GetMapping
    public List<BindingView> list() {
        currentUser.requireAdmin();
        return bindings.findAll().stream()
                .map(
                        binding ->
                                BindingView.from(
                                        binding,
                                        users.findById(binding.userId()).map(it -> it.email()).orElse("unknown")))
                .toList();
    }

    public record CreateBindingRequest(
            @NotBlank String email,
            @NotBlank String profileName,
            @NotBlank String provider,
            @NotBlank String model,
            @NotNull CostMode costMode) {
    }

    public record BindingView(
            Long id, String email, String profileName, String provider, String model, String costMode,
            String status) {

        static BindingView from(HermesProfileBinding binding, String email) {
            return new BindingView(
                    binding.id(),
                    email,
                    binding.profileName(),
                    binding.provider(),
                    binding.model(),
                    binding.costMode().name(),
                    binding.status().name());
        }
    }
}
