package com.mobiletools.arrears.dataingestion.api;

import com.mobiletools.arrears.dataingestion.ledger.LedgerImportService;
import com.mobiletools.arrears.dataingestion.ledger.LedgerBatchConfirmationService;
import com.mobiletools.arrears.dataingestion.ledger.LedgerImportBatchQueryService;
import com.mobiletools.arrears.dataingestion.ledger.LedgerBatchCancellationService;
import com.mobiletools.arrears.dataingestion.ledger.LedgerWorkbookParser;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class LedgerImportControllerTests {

    @Test
    void customerManagerCannotPrecheckOrDownloadOriginalWorkbooks() {
        LedgerImportService imports = mock(LedgerImportService.class);
        LedgerBatchConfirmationService confirmations = mock(LedgerBatchConfirmationService.class);
        LedgerImportBatchQueryService batchQueries = mock(LedgerImportBatchQueryService.class);
        LedgerImportController controller = new LedgerImportController(imports, confirmations, batchQueries);
        var manager = new TestingAuthenticationToken("manager", "password", "ROLE_CUSTOMER_MANAGER");
        var file = new MockMultipartFile("file", "input.xlsx", "application/octet-stream", new byte[]{1});

        assertThrows(AccessDeniedException.class, () -> controller.precheck(
                LedgerWorkbookParser.SourceType.ARREARS, file, manager));
        assertThrows(AccessDeniedException.class, () -> controller.original(1L, manager));
        assertThrows(AccessDeniedException.class, () -> controller.confirm(1L, manager));
        assertThrows(AccessDeniedException.class, () -> controller.batches(null, null, null, 1, 20, manager));
        assertThrows(AccessDeniedException.class, () -> controller.batch(1L, 1, 20, manager));
        verifyNoInteractions(imports, confirmations, batchQueries);
    }

    @Test
    void administratorCanConfirmPrecheckedBatch() {
        LedgerImportService imports = mock(LedgerImportService.class);
        LedgerBatchConfirmationService confirmations = mock(LedgerBatchConfirmationService.class);
        LedgerImportController controller = new LedgerImportController(imports, confirmations);
        var admin = new TestingAuthenticationToken("admin", "password", "ROLE_ADMIN");
        var expected = new LedgerBatchConfirmationService.ConfirmationResult(
                42, "ARREARS", "202609", 3, 1, 0, true);
        when(confirmations.confirm(42, "admin")).thenReturn(expected);

        var response = controller.confirm(42, admin);

        org.junit.jupiter.api.Assertions.assertEquals(expected, response.data());
        verify(confirmations).confirm(42, "admin");
    }

    @Test
    void administratorPreviewResponseIncludesMergeCountsAndExplicitClearDiffs() throws Exception {
        LedgerImportService imports = mock(LedgerImportService.class);
        var controller = new LedgerImportController(imports);
        var admin = new TestingAuthenticationToken("admin", "password", "ROLE_ADMIN");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var diff = new LedgerImportService.RevisionDiff("202609:A-1", "UPDATED", 10L, 20L,
                mapper.readTree("{\"账龄\":\"60天\"}"), mapper.readTree("{\"账龄\":null}"),
                mapper.readTree("[{\"header\":\"账龄\",\"before\":\"60天\",\"after\":null,\"cleared\":true}]"));
        var revision = new LedgerImportService.RevisionSummary(9L, 2, 0, 1, 3, 4, java.util.List.of(diff));
        var preview = new LedgerImportService.UploadPreview(42L, "ARREARS", "202609", "rev.xlsx",
                "a".repeat(64), 128, "PRECHECKED", 6, 6, 0, 0, false, revision,
                java.util.List.of(), java.util.List.of());
        when(imports.precheck(eq(LedgerWorkbookParser.SourceType.ARREARS), eq("rev.xlsx"),
                any(byte[].class), eq("admin"))).thenReturn(preview);
        var file = new MockMultipartFile("file", "rev.xlsx", "application/octet-stream", new byte[]{1, 2});

        var response = controller.precheck(LedgerWorkbookParser.SourceType.ARREARS, file, admin);

        assertEquals(2, response.data().revision().addedRows());
        assertEquals(1, response.data().revision().changedRows());
        assertEquals(3, response.data().revision().unchangedRows());
        assertEquals(4, response.data().revision().retainedRows());
        assertEquals(true, response.data().revision().differences().get(0).changedFields()
                .get(0).get("cleared").asBoolean());
    }

    @Test
    void customerManagerCannotCancelImportBatch() {
        LedgerImportService imports = mock(LedgerImportService.class);
        LedgerBatchConfirmationService confirmations = mock(LedgerBatchConfirmationService.class);
        LedgerImportBatchQueryService batchQueries = mock(LedgerImportBatchQueryService.class);
        LedgerBatchCancellationService cancellations = mock(LedgerBatchCancellationService.class);
        LedgerImportController controller = new LedgerImportController(imports, confirmations, batchQueries, cancellations);
        var manager = new TestingAuthenticationToken("manager", "password", "ROLE_CUSTOMER_MANAGER");

        assertThrows(AccessDeniedException.class, () -> controller.cancel(42, manager));
        verifyNoInteractions(cancellations);
    }

    @Test
    void administratorCanCancelImportBatch() {
        LedgerImportService imports = mock(LedgerImportService.class);
        LedgerBatchConfirmationService confirmations = mock(LedgerBatchConfirmationService.class);
        LedgerImportBatchQueryService batchQueries = mock(LedgerImportBatchQueryService.class);
        LedgerBatchCancellationService cancellations = mock(LedgerBatchCancellationService.class);
        LedgerImportController controller = new LedgerImportController(imports, confirmations, batchQueries, cancellations);
        var admin = new TestingAuthenticationToken("admin", "password", "ROLE_ADMIN");
        var expected = new LedgerBatchCancellationService.CancellationResult(42, "CANCELLED", "ARREARS", "202609");
        when(cancellations.cancel(42, "admin")).thenReturn(expected);

        var response = controller.cancel(42, admin);

        org.junit.jupiter.api.Assertions.assertEquals(expected, response.data());
        verify(cancellations).cancel(42, "admin");
    }

    @Test
    void administratorCanListAndOpenBatchDetails() {
        LedgerImportService imports = mock(LedgerImportService.class);
        LedgerBatchConfirmationService confirmations = mock(LedgerBatchConfirmationService.class);
        LedgerImportBatchQueryService batchQueries = mock(LedgerImportBatchQueryService.class);
        LedgerImportController controller = new LedgerImportController(imports, confirmations, batchQueries);
        var admin = new TestingAuthenticationToken("admin", "password", "ROLE_ADMIN");
        var contributedBatch = new LedgerImportBatchQueryService.BatchListItem(
                7, "ARREARS", "202609", "partial.xlsx", "a".repeat(64), 100L,
                "admin", null, null, "CONFIRMED", 1, "PARTIAL",
                3, 3, 0, 0, 1, 0, 1, 1, 1, 0, true, "AVAILABLE");
        var page = new LedgerImportBatchQueryService.Page<LedgerImportBatchQueryService.BatchListItem>(
                java.util.List.of(contributedBatch), 1, 20, 1);
        when(batchQueries.list("ARREARS", "202609", null, 1, 20)).thenReturn(page);
        var detail = new LedgerImportBatchQueryService.BatchDetail(null,
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
                java.util.List.of(), java.util.List.of(),
                new LedgerImportBatchQueryService.PageInfo(1, 20, 0), java.util.List.of(), 0);
        when(batchQueries.detail(42, 1, 20)).thenReturn(detail);

        var response = controller.batches("ARREARS", "202609", null, 1, 20, admin);
        org.junit.jupiter.api.Assertions.assertEquals(page, response.data());
        var responseJson = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(response.data());
        assertEquals("PARTIAL", responseJson.path("items").get(0).path("contributionStatus").asText());
        assertEquals(1, responseJson.path("items").get(0).path("contributingRows").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(detail, controller.batch(42, 1, 20, admin).data());
        verify(batchQueries).list("ARREARS", "202609", null, 1, 20);
        verify(batchQueries).detail(42, 1, 20);
    }
}
