package com.bifos.assistant.skill.presentation;

import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.skill.application.SkillPackageService;
import com.bifos.assistant.skill.application.SkillPackageZip;
import com.bifos.assistant.skill.presentation.SkillDtos.SkillDetailView;
import com.bifos.assistant.skill.presentation.SkillDtos.SkillPackagePreviewView;
import java.io.IOException;
import java.io.UncheckedIOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 스킬 하나를 zip 묶음으로 미리보고 올리는 경로다. 크기 상한을 넘은 파일은 바이트를 읽지 않는다.
 *
 * <p>권한과 판정은 {@link SkillPackageService} 가 정한다. 미리보기는 문제가 있어도 200 이다.
 */
@RestController
@RequestMapping("/api/v1/agents/{code}/skill-packages")
@RequiredArgsConstructor
public class SkillPackageController {

    private final SkillPackageService packages;
    private final CurrentUserProvider currentUser;

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SkillPackagePreviewView preview(@PathVariable String code, @RequestParam("file") MultipartFile file) {
        if (file.getSize() > SkillPackageZip.MAX_ZIP_BYTES) {
            return SkillPackagePreviewView.from(packages.previewTooLarge(currentUser.require(), code));
        }
        return SkillPackagePreviewView.from(packages.preview(currentUser.require(), code, bytesOf(file)));
    }

    /** {@code baseDigest} 는 이미 있는 스킬을 덮어쓸 때 미리보기가 준 값이다. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SkillDetailView upload(
            @PathVariable String code,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "baseDigest", required = false) String baseDigest) {
        if (file.getSize() > SkillPackageZip.MAX_ZIP_BYTES) {
            throw packages.uploadTooLarge(currentUser.require(), code);
        }
        return SkillDetailView.from(packages.upload(currentUser.require(), code, bytesOf(file), baseDigest));
    }

    private static byte[] bytesOf(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
