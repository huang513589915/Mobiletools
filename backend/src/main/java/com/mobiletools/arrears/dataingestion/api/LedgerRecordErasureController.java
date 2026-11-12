package com.mobiletools.arrears.dataingestion.api;

import com.mobiletools.arrears.common.api.ApiResponse;
import com.mobiletools.arrears.dataingestion.ledger.LedgerRecordErasurePreviewService;
import com.mobiletools.arrears.dataingestion.ledger.LedgerRecordErasureService;
import com.mobiletools.arrears.dataingestion.ledger.LedgerWorkbookParser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ledger-erasure")
public class LedgerRecordErasureController {
    private final LedgerRecordErasurePreviewService previews;
    private final LedgerRecordErasureService erasures;

    public LedgerRecordErasureController(LedgerRecordErasurePreviewService previews, LedgerRecordErasureService erasures) {
        this.previews = previews;
        this.erasures = erasures;
    }

    @PostMapping("/confirm")
    public ApiResponse<LedgerRecordErasureService.ErasureResult> confirm(
            @RequestBody ConfirmRequest request, Authentication authentication) {
        requireAdmin(authentication);
        if (request == null) throw new IllegalArgumentException("删除确认请求不能为空");
        LedgerRecordErasureService.ErasureResult result = erasures.erase(request.previewToken(),
                authentication.getName(), request.deleteOriginalWorkbooks(), request.confirmationText());
        return ApiResponse.success(erasures.processPhysicalCleanup(result));
    }

    @PostMapping("/preview")
    public ApiResponse<LedgerRecordErasurePreviewService.PreviewResult> preview(
            @RequestBody PreviewRequest request, Authentication authentication) {
        requireAdmin(authentication);
        if (request == null) throw new IllegalArgumentException("删除预览请求不能为空");
        return ApiResponse.success(previews.preview(request.sourceType(), request.snapshotRecordIds(),
                authentication.getName()));
    }

    private static void requireAdmin(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities().stream()
                .noneMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))) {
            throw new AccessDeniedException("仅管理员可预览永久删除");
        }
    }

    public record PreviewRequest(LedgerWorkbookParser.SourceType sourceType, List<Long> snapshotRecordIds) {}
    public record ConfirmRequest(String previewToken, boolean deleteOriginalWorkbooks, String confirmationText) {}
}
