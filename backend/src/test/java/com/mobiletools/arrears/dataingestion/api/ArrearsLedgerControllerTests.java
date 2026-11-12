package com.mobiletools.arrears.dataingestion.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobiletools.arrears.dataingestion.ledger.ArrearsLedgerQueryService;
import com.mobiletools.arrears.dataingestion.ledger.SnapshotAccessScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ArrearsLedgerControllerTests {

    private MockMvc mvc;
    private ArrearsLedgerQueryService arrears;
    private SnapshotAccessScopeService scopes;
    private SnapshotAccessScopeService.Scope managerScope;

    @BeforeEach
    void setUp() {
        arrears = mock(ArrearsLedgerQueryService.class);
        scopes = mock(SnapshotAccessScopeService.class);
        managerScope = new SnapshotAccessScopeService.Scope(false, 8);
        mvc = MockMvcBuilders.standaloneSetup(new ArrearsLedgerController(arrears, scopes)).build();
        when(scopes.resolve(any())).thenReturn(managerScope);
    }

    @Test
    void listEndpointMapsFiltersAndReturnsPagedLedgerContract() throws Exception {
        var item = new ArrearsLedgerQueryService.Item(77, "202609", "G-1", "甲集团",
                "客户经理甲", 8L, "客户经理甲", "A-1", "承载产品甲", new BigDecimal("12.50"), "30天");
        when(arrears.list(any(), eq(managerScope))).thenReturn(new ArrearsLedgerQueryService.Page(
                List.of(item), 1, 2, 50, "202609", List.of("202609", "202608")));

        mvc.perform(get("/api/arrears")
                        .param("statisticsKey", "202609")
                        .param("groupQuery", "G-1")
                        .param("accountId", "A-1")
                        .param("carryingProductName", "承载")
                        .param("workOrderStatus", "跟进中")
                        .param("effectiveManagerUserId", "8")
                        .param("page", "2")
                        .param("pageSize", "50")
                        .principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.statisticsKey").value("202609"))
                .andExpect(jsonPath("$.data.availableStatisticsKeys[1]").value("202608"))
                .andExpect(jsonPath("$.data.items[0].snapshotRecordId").value(77))
                .andExpect(jsonPath("$.data.items[0].accountId").value("A-1"))
                .andExpect(jsonPath("$.data.items[0].effectiveManagerName").value("客户经理甲"));
        verify(arrears).list(argThat(filters -> filters.statisticsKey().equals("202609")
                && filters.groupQuery().equals("G-1") && filters.accountId().equals("A-1")
                && filters.carryingProductName().equals("承载") && filters.effectiveManagerUserId() == 8L
                && filters.workOrderStatus().equals("跟进中")
                && filters.page() == 2 && filters.pageSize() == 50), eq(managerScope));
    }

    @Test
    void listEndpointMapsStatisticsMonthRange() throws Exception {
        when(arrears.list(any(), eq(managerScope))).thenReturn(new ArrearsLedgerQueryService.Page(
                List.of(), 0, 1, 20, "202609", List.of("202609", "202608")));

        mvc.perform(get("/api/arrears").param("statisticsDateFrom", "202608")
                        .param("statisticsDateTo", "202609").principal(authentication()))
                .andExpect(status().isOk());

        verify(arrears).list(argThat(filters -> filters.statisticsDateFrom().equals("202608")
                && filters.statisticsDateTo().equals("202609")), eq(managerScope));
    }

    @Test
    void detailEndpointReturnsRawFieldsAndTraceability() throws Exception {
        var item = new ArrearsLedgerQueryService.Item(77, "202609", "G-1", "甲集团",
                "客户经理甲", 8L, "客户经理甲", "A-1", "承载产品甲", BigDecimal.ZERO, "0天");
        var rawFields = new ObjectMapper().readTree("[{\"columnIndex\":2,\"header\":\"是否达到停机标准\",\"value\":\"是\"}]");
        when(arrears.detail(77, managerScope)).thenReturn(new ArrearsLedgerQueryService.Detail(
                item, 500, "arrears.xlsx", "欠费表", 9, "202609:A-1", rawFields,
                new ObjectMapper().createObjectNode(), "ASSIGNED"));

        mvc.perform(get("/api/arrears/77").principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.endingArrearsAmount").value(0))
                .andExpect(jsonPath("$.data.rawFields[0].header").value("是否达到停机标准"))
                .andExpect(jsonPath("$.data.batchId").value(500))
                .andExpect(jsonPath("$.data.sheetName").value("欠费表"))
                .andExpect(jsonPath("$.data.sourceRowNumber").value(9));
    }

    @Test
    void exportEndpointReturnsAnExcelAttachment() throws Exception {
        when(arrears.export(any(), eq(managerScope))).thenReturn(new byte[]{1, 2, 3});

        mvc.perform(get("/api/arrears/export").param("statisticsKey", "202609")
                        .param("groupQuery", "G-1").param("workOrderStatus", "跟进中")
                        .principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
        verify(arrears).export(argThat(filters -> filters.statisticsKey().equals("202609")
                && filters.groupQuery().equals("G-1")
                && filters.workOrderStatus().equals("跟进中")), eq(managerScope));
    }

    @Test
    void administratorWorkOrderExportUsesItsOwnEndpointAndFilters() throws Exception {
        var admin = new SnapshotAccessScopeService.Scope(true, 1);
        when(scopes.resolve(any())).thenReturn(admin);
        when(arrears.exportWorkOrders(any(), eq(admin))).thenReturn(new byte[]{4, 5, 6});
        mvc.perform(get("/api/arrears/work-orders/export").param("statisticsDateFrom", "202609")
                        .param("statisticsDateTo", "202610").param("workOrderStatus", "跟进中")
                        .principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(new byte[]{4, 5, 6}))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")));
        verify(arrears).exportWorkOrders(argThat(filters -> filters.statisticsDateFrom().equals("202609")
                && filters.statisticsDateTo().equals("202610") && filters.workOrderStatus().equals("跟进中")), eq(admin));
    }

    private static UsernamePasswordAuthenticationToken authentication() {
        return new UsernamePasswordAuthenticationToken("manager", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER_MANAGER")));
    }
}
