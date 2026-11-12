package com.mobiletools.arrears.dataingestion.api;

import com.mobiletools.arrears.common.api.ApiResponse;
import com.mobiletools.arrears.dataingestion.ledger.ArrearsLedgerQueryService;
import com.mobiletools.arrears.dataingestion.ledger.SnapshotAccessScopeService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/arrears")
public class ArrearsLedgerController {

    private final ArrearsLedgerQueryService arrears;
    private final SnapshotAccessScopeService scopes;

    public ArrearsLedgerController(ArrearsLedgerQueryService arrears, SnapshotAccessScopeService scopes) {
        this.arrears = arrears;
        this.scopes = scopes;
    }

    @GetMapping
    public ApiResponse<ArrearsLedgerQueryService.Page> list(
            @RequestParam(required = false) String statisticsKey,
            @RequestParam(required = false) String statisticsDateFrom,
            @RequestParam(required = false) String statisticsDateTo,
            @RequestParam(required = false) String groupQuery,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String carryingProductName,
            @RequestParam(required = false) String workOrderStatus,
            @RequestParam(required = false) Long effectiveManagerUserId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            Authentication authentication) {
        var scope = scopes.resolve(authentication);
        return ApiResponse.success(arrears.list(new ArrearsLedgerQueryService.Filters(statisticsKey, groupQuery,
                accountId, carryingProductName, effectiveManagerUserId, page, pageSize,
                statisticsDateFrom, statisticsDateTo, workOrderStatus), scope));
    }

    @GetMapping("/{snapshotRecordId}")
    public ApiResponse<ArrearsLedgerQueryService.Detail> detail(@org.springframework.web.bind.annotation.PathVariable long snapshotRecordId,
                                                                  Authentication authentication) {
        return ApiResponse.success(arrears.detail(snapshotRecordId, scopes.resolve(authentication)));
    }

    @GetMapping("/export")
    public ResponseEntity<ByteArrayResource> export(
            @RequestParam(required = false) String statisticsKey,
            @RequestParam(required = false) String statisticsDateFrom,
            @RequestParam(required = false) String statisticsDateTo,
            @RequestParam(required = false) String groupQuery,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String carryingProductName,
            @RequestParam(required = false) String workOrderStatus,
            @RequestParam(required = false) Long effectiveManagerUserId,
            Authentication authentication) {
        var filters = new ArrearsLedgerQueryService.Filters(statisticsKey, groupQuery, accountId,
                carryingProductName, effectiveManagerUserId, 1, 20, statisticsDateFrom, statisticsDateTo,
                workOrderStatus);
        byte[] bytes = arrears.export(filters, scopes.resolve(authentication));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("欠费明细.xlsx", StandardCharsets.UTF_8).build().toString())
                .contentLength(bytes.length)
                .body(new ByteArrayResource(bytes));
    }

    @GetMapping("/work-orders/export")
    public ResponseEntity<ByteArrayResource> exportWorkOrders(
            @RequestParam(required = false) String statisticsDateFrom,
            @RequestParam(required = false) String statisticsDateTo,
            @RequestParam(required = false) String groupQuery,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String carryingProductName,
            @RequestParam(required = false) String workOrderStatus,
            @RequestParam(required = false) Long effectiveManagerUserId,
            Authentication authentication) {
        var filters = new ArrearsLedgerQueryService.Filters(null, groupQuery, accountId, carryingProductName,
                effectiveManagerUserId, 1, 20, statisticsDateFrom, statisticsDateTo, workOrderStatus);
        byte[] bytes = arrears.exportWorkOrders(filters, scopes.resolve(authentication));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("欠费工单报表.xlsx", StandardCharsets.UTF_8).build().toString())
                .contentLength(bytes.length)
                .body(new ByteArrayResource(bytes));
    }
}
