package com.mobiletools.arrears.dataingestion.api;

import com.mobiletools.arrears.dataingestion.ledger.LedgerRecordErasurePreviewService;
import com.mobiletools.arrears.dataingestion.ledger.LedgerWorkbookParser;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LedgerRecordErasureControllerTests {
    @Test
    void customerManagerCannotRequestErasurePreview() {
        LedgerRecordErasurePreviewService service = mock(LedgerRecordErasurePreviewService.class);
        LedgerRecordErasureController controller = new LedgerRecordErasureController(service,
                mock(com.mobiletools.arrears.dataingestion.ledger.LedgerRecordErasureService.class));
        var manager = new TestingAuthenticationToken("manager", "password", "ROLE_CUSTOMER_MANAGER");

        assertThrows(AccessDeniedException.class, () -> controller.preview(
                new LedgerRecordErasureController.PreviewRequest(LedgerWorkbookParser.SourceType.ARREARS,
                        List.of(7L)), manager));
        verifyNoInteractions(service);
    }

    @Test
    void administratorConfirmationRequiresTheTypedPhrase() {
        LedgerRecordErasurePreviewService previews = mock(LedgerRecordErasurePreviewService.class);
        var erasures = mock(com.mobiletools.arrears.dataingestion.ledger.LedgerRecordErasureService.class);
        LedgerRecordErasureController controller = new LedgerRecordErasureController(previews, erasures);
        var admin = new TestingAuthenticationToken("admin", "password", "ROLE_ADMIN");

        when(erasures.erase("token", "admin", false, "永久删除"))
                .thenReturn(new com.mobiletools.arrears.dataingestion.ledger.LedgerRecordErasureService.ErasureResult(
                        "operation", 8L, "ARREARS", 1L, 1L, 1L, 0, "RETAINED", java.util.Map.of("202611", 9L)));
        when(erasures.processPhysicalCleanup(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        controller.confirm(new LedgerRecordErasureController.ConfirmRequest("token", false, "永久删除"), admin);

        org.mockito.Mockito.verify(erasures).erase("token", "admin", false, "永久删除");
        org.mockito.Mockito.verify(erasures).processPhysicalCleanup(org.mockito.ArgumentMatchers.any());
    }
}
