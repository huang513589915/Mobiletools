package com.mobiletools.arrears.dataingestion.api;

import com.mobiletools.arrears.common.api.ApiResponse;
import com.mobiletools.arrears.dataingestion.ledger.LedgerImportService;
import com.mobiletools.arrears.dataingestion.ledger.LedgerBatchConfirmationService;
import com.mobiletools.arrears.dataingestion.ledger.LedgerImportBatchQueryService;
import com.mobiletools.arrears.dataingestion.ledger.LedgerBatchCancellationService;
import com.mobiletools.arrears.dataingestion.ledger.LedgerWorkbookParser;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/ledger-imports")
public class LedgerImportController {

    private final LedgerImportService imports;
    private final LedgerBatchConfirmationService confirmations;
    private final LedgerImportBatchQueryService batchQueries;
    private final LedgerBatchCancellationService cancellations;

    public LedgerImportController(LedgerImportService imports) {
        this(imports, null, null, null);
    }

    public LedgerImportController(LedgerImportService imports, LedgerBatchConfirmationService confirmations) {
        this(imports, confirmations, null, null);
    }

    public LedgerImportController(LedgerImportService imports, LedgerBatchConfirmationService confirmations,
                                  LedgerImportBatchQueryService batchQueries) {
        this(imports, confirmations, batchQueries, null);
    }

    @Autowired
    public LedgerImportController(LedgerImportService imports, LedgerBatchConfirmationService confirmations,
                                  LedgerImportBatchQueryService batchQueries,
                                  LedgerBatchCancellationService cancellations) {
        this.imports = imports;
        this.confirmations = confirmations;
        this.batchQueries = batchQueries;
        this.cancellations = cancellations;
    }

    @PostMapping(value = "/precheck", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<LedgerImportService.UploadPreview> precheck(
            @RequestParam LedgerWorkbookParser.SourceType sourceType,
            @RequestPart("file") MultipartFile file,
            Authentication authentication) throws IOException {
        requireAdmin(authentication);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Excel 文件不能为空");
        return ApiResponse.success(imports.precheck(sourceType, file.getOriginalFilename(),
                file.getBytes(), authentication.getName()));
    }

    @PostMapping("/{batchId}/confirm")
    public ApiResponse<LedgerBatchConfirmationService.ConfirmationResult> confirm(
            @PathVariable long batchId, Authentication authentication) {
        requireAdmin(authentication);
        if (confirmations == null) throw new IllegalStateException("导入确认服务未配置");
        return ApiResponse.success(confirmations.confirm(batchId, authentication.getName()));
    }

    @PostMapping("/{batchId}/cancel")
    public ApiResponse<LedgerBatchCancellationService.CancellationResult> cancel(
            @PathVariable long batchId, Authentication authentication) {
        requireAdmin(authentication);
        if (cancellations == null) throw new IllegalStateException("导入取消服务未配置");
        return ApiResponse.success(cancellations.cancel(batchId, authentication.getName()));
    }

    @GetMapping("/batches")
    public ApiResponse<LedgerImportBatchQueryService.Page<LedgerImportBatchQueryService.BatchListItem>> batches(
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String statisticsKey,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            Authentication authentication) {
        requireAdmin(authentication);
        if (batchQueries == null) throw new IllegalStateException("导入批次查询服务未配置");
        return ApiResponse.success(batchQueries.list(sourceType, statisticsKey, status, page, pageSize));
    }

    @GetMapping("/batches/{batchId}")
    public ApiResponse<LedgerImportBatchQueryService.BatchDetail> batch(
            @PathVariable long batchId,
            @RequestParam(defaultValue = "1") int rowPage,
            @RequestParam(defaultValue = "20") int pageSize,
            Authentication authentication) {
        requireAdmin(authentication);
        if (batchQueries == null) throw new IllegalStateException("导入批次查询服务未配置");
        return ApiResponse.success(batchQueries.detail(batchId, rowPage, pageSize));
    }

    @GetMapping("/{batchId}/original")
    public ResponseEntity<ByteArrayResource> original(@PathVariable long batchId,
                                                     Authentication authentication) throws IOException {
        requireAdmin(authentication);
        LedgerImportService.OriginalWorkbook original = imports.originalWorkbook(batchId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(original.filename(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-SHA256", original.sha256())
                .contentLength(original.bytes().length)
                .body(new ByteArrayResource(original.bytes()));
    }

    private void requireAdmin(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities().stream()
                .noneMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))) {
            throw new AccessDeniedException("仅管理员可管理导入批次");
        }
    }
}
