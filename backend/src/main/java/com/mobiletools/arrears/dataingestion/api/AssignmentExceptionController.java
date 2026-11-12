package com.mobiletools.arrears.dataingestion.api;

import com.mobiletools.arrears.common.api.ApiResponse;
import com.mobiletools.arrears.dataingestion.ledger.AssignmentExceptionQueryService;
import com.mobiletools.arrears.dataingestion.ledger.AssignmentManagementService;
import com.mobiletools.arrears.system.api.UserController.AdminAccess;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assignment-exceptions")
public class AssignmentExceptionController {

    private final AssignmentExceptionQueryService exceptions;
    private final AssignmentManagementService assignments;
    private final AdminAccess admin;

    public AssignmentExceptionController(AssignmentExceptionQueryService exceptions,
                                         AssignmentManagementService assignments, AdminAccess admin) {
        this.exceptions = exceptions;
        this.assignments = assignments;
        this.admin = admin;
    }

    @GetMapping
    public ApiResponse<AssignmentExceptionQueryService.Page> list(
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String statisticsKey,
            @RequestParam(required = false) String groupCode,
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String excelManagerName,
            @RequestParam(required = false) Long batchId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            Authentication authentication) {
        admin.requireAdmin(authentication, "ASSIGNMENT_EXCEPTION_QUERY", "ASSIGNMENT_EXCEPTION", null);
        return ApiResponse.success(exceptions.list(sourceType, statisticsKey, groupCode, groupName,
                excelManagerName, batchId, status, page, pageSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<AssignmentExceptionQueryService.ExceptionDetail> detail(@PathVariable long id,
                                                                                Authentication authentication) {
        admin.requireAdmin(authentication, "ASSIGNMENT_EXCEPTION_DETAIL", "ASSIGNMENT_EXCEPTION", id);
        return ApiResponse.success(exceptions.detail(id));
    }

    @PostMapping("/assign")
    public ApiResponse<AssignmentManagementService.AssignmentResult> assign(
            @RequestBody AssignmentRequest request, Authentication authentication) {
        long actor = admin.requireAdmin(authentication, "ASSIGNMENT_BULK_UPDATE", "ASSIGNMENT_EXCEPTION", null);
        if (Boolean.TRUE.equals(request.allFiltered())) {
            return ApiResponse.success(assignments.assignFiltered(request.sourceType(), request.statisticsKey(),
                    request.groupCode(), request.groupName(), request.excelManagerName(), request.batchId(),
                    request.status(), request.targetManagerUserId(), actor, request.reason()));
        }
        return ApiResponse.success(assignments.assign(request.sourceType(), request.snapshotRecordIds(),
                request.targetManagerUserId(), actor, request.reason()));
    }

    public record AssignmentRequest(String sourceType, java.util.List<Long> snapshotRecordIds,
                                    Boolean allFiltered, String statisticsKey, String groupCode,
                                    String groupName, String excelManagerName, Long batchId, String status,
                                    long targetManagerUserId, String reason) {}
}
