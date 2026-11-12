package com.mobiletools.arrears.dataingestion.api;

import com.mobiletools.arrears.common.api.ApiResponse;
import com.mobiletools.arrears.dataingestion.ledger.LineExpiryLedgerQueryService;
import com.mobiletools.arrears.dataingestion.ledger.SnapshotAccessScopeService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;


@RestController
@RequestMapping("/api/line-expiry")
public class LineExpiryLedgerController {

    private final LineExpiryLedgerQueryService lineExpiry;
    private final SnapshotAccessScopeService scopes;

    public LineExpiryLedgerController(LineExpiryLedgerQueryService lineExpiry, SnapshotAccessScopeService scopes) {
        this.lineExpiry = lineExpiry;
        this.scopes = scopes;
    }

    @GetMapping
    public ApiResponse<LineExpiryLedgerQueryService.Page> list(
            @RequestParam(required = false) String statisticsKey,
            @RequestParam(required = false) String statisticsDateFrom,
            @RequestParam(required = false) String statisticsDateTo,
            @RequestParam(required = false) String groupQuery,
            @RequestParam(required = false) String productQuery,
            @RequestParam(required = false) Long effectiveManagerUserId,
            @RequestParam(required = false) Integer remainingDaysMin,
            @RequestParam(required = false) Integer remainingDaysMax,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String renewalStatus,
            Authentication authentication) {
        var scope = scopes.resolve(authentication);
        return ApiResponse.success(lineExpiry.list(new LineExpiryLedgerQueryService.Filters(statisticsKey, groupQuery,
                productQuery, effectiveManagerUserId, remainingDaysMin, remainingDaysMax, page, pageSize,
                statisticsDateFrom, statisticsDateTo, renewalStatus), scope));
    }

    @GetMapping("/{snapshotRecordId}")
    public ApiResponse<LineExpiryLedgerQueryService.Detail> detail(@PathVariable long snapshotRecordId,
                                                                    Authentication authentication) {
        return ApiResponse.success(lineExpiry.detail(snapshotRecordId, scopes.resolve(authentication)));
    }

    @GetMapping("/export")
    public ResponseEntity<ByteArrayResource> export(@RequestParam(required = false) String statisticsKey,
                                                     @RequestParam(required = false) String statisticsDateFrom,
                                                     @RequestParam(required = false) String statisticsDateTo,
                                                     @RequestParam(required = false) String groupQuery,
                                                     @RequestParam(required = false) String productQuery,
                                                     @RequestParam(required = false) Long effectiveManagerUserId,
                                                     @RequestParam(required = false) Integer remainingDaysMin,
                                                     @RequestParam(required = false) Integer remainingDaysMax,
                                                     @RequestParam(required = false) String renewalStatus,
                                                     Authentication authentication) {
        byte[] bytes = lineExpiry.export(new LineExpiryLedgerQueryService.Filters(statisticsKey, groupQuery,
                productQuery, effectiveManagerUserId, remainingDaysMin, remainingDaysMax, 1, 20,
                statisticsDateFrom, statisticsDateTo, renewalStatus),
                scopes.resolve(authentication));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("专线到期明细.xlsx", StandardCharsets.UTF_8).build().toString())
                .contentLength(bytes.length)
                .body(new ByteArrayResource(bytes));
    }

    @GetMapping("/renewal-work-orders/export")
    public ResponseEntity<ByteArrayResource> exportRenewalWorkOrders(
            @RequestParam(required = false) String statisticsKey,
            @RequestParam(required = false) String statisticsDateFrom,
            @RequestParam(required = false) String statisticsDateTo,
            @RequestParam(required = false) String groupQuery,
            @RequestParam(required = false) String productQuery,
            @RequestParam(required = false) Long effectiveManagerUserId,
            @RequestParam(required = false) Integer remainingDaysMin,
            @RequestParam(required = false) Integer remainingDaysMax,
            @RequestParam(required = false) String renewalStatus,
            Authentication authentication) {
        var scope = scopes.resolve(authentication);
        byte[] bytes = lineExpiry.exportRenewalReport(new LineExpiryLedgerQueryService.Filters(statisticsKey,
                groupQuery, productQuery, effectiveManagerUserId, remainingDaysMin, remainingDaysMax,
                1, 20, statisticsDateFrom, statisticsDateTo, renewalStatus), scope);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("专线续签工单报表.xlsx", StandardCharsets.UTF_8).build().toString())
                .contentLength(bytes.length)
                .body(new ByteArrayResource(bytes));
    }

}
