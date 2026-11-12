package com.mobiletools.arrears.dataingestion.api;

import com.mobiletools.arrears.common.api.ApiResponse;
import com.mobiletools.arrears.dataingestion.ledger.ArrearsWorkOrderService;
import com.mobiletools.arrears.dataingestion.ledger.SnapshotAccessScopeService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/arrears-work-orders")
public class ArrearsWorkOrderController {
    private final ArrearsWorkOrderService workOrders;
    private final SnapshotAccessScopeService scopes;

    public ArrearsWorkOrderController(ArrearsWorkOrderService workOrders,
                                      SnapshotAccessScopeService scopes) {
        this.workOrders = workOrders;
        this.scopes = scopes;
    }

    @PatchMapping("/{workOrderId}/status")
    public ApiResponse<ArrearsWorkOrderService.UpdateResult> updateStatus(
            @PathVariable long workOrderId, @RequestBody StatusUpdate request,
            Authentication authentication) {
        return ApiResponse.success(workOrders.updateHandling(workOrderId,
                request == null ? null : request.status(),
                request == null ? null : request.followUpNote(), scopes.resolve(authentication)));
    }

    public record StatusUpdate(String status, String followUpNote) {}
}
