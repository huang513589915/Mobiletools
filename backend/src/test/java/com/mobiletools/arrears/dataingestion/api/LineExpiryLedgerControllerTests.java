package com.mobiletools.arrears.dataingestion.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobiletools.arrears.dataingestion.ledger.LineExpiryLedgerQueryService;
import com.mobiletools.arrears.dataingestion.ledger.SnapshotAccessScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LineExpiryLedgerControllerTests {

    private MockMvc mvc;
    private LineExpiryLedgerQueryService lineExpiry;
    private SnapshotAccessScopeService scopes;
    private SnapshotAccessScopeService.Scope managerScope;

    @BeforeEach
    void setUp() {
        lineExpiry = mock(LineExpiryLedgerQueryService.class);
        scopes = mock(SnapshotAccessScopeService.class);
        managerScope = new SnapshotAccessScopeService.Scope(false, 8);
        mvc = MockMvcBuilders.standaloneSetup(new LineExpiryLedgerController(lineExpiry, scopes)).build();
        when(scopes.resolve(any())).thenReturn(managerScope);
    }

    @Test
    void listEndpointMapsRangeFiltersAndDynamicDateFields() throws Exception {
        var item = new LineExpiryLedgerQueryService.Item(77, "20261001", "G-1", "甲集团",
                "P-1", "产品一", "S-1", "客户经理甲", 8L, "客户经理甲", LocalDate.of(2026, 10, 31), 9);
        when(lineExpiry.list(any(), eq(managerScope))).thenReturn(new LineExpiryLedgerQueryService.Page(
                List.of(item), 1, 1, 20, "20261001", List.of("20261001"), LocalDate.of(2026, 10, 22)));

        mvc.perform(get("/api/line-expiry")
                        .param("statisticsKey", "20261001")
                        .param("groupQuery", "G-1")
                        .param("productQuery", "产品")
                        .param("remainingDaysMin", "0")
                        .param("remainingDaysMax", "9")
                        .param("pageSize", "20")
                        .principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].memberServiceNumber").value("S-1"))
                .andExpect(jsonPath("$.data.items[0].remainingDays").value(9))
                .andExpect(jsonPath("$.data.calculatedAt").value("2026-10-22"));
        verify(lineExpiry).list(argThat(filters -> filters.remainingDaysMin() == 0
                && filters.remainingDaysMax() == 9 && filters.groupQuery().equals("G-1")
                && filters.productQuery().equals("产品")), eq(managerScope));
    }

    @Test
    void listEndpointMapsStatisticsDateRange() throws Exception {
        when(lineExpiry.list(any(), eq(managerScope))).thenReturn(new LineExpiryLedgerQueryService.Page(
                List.of(), 0, 1, 20, "20261001", List.of("20261001", "20260901"), LocalDate.of(2026, 10, 22)));

        mvc.perform(get("/api/line-expiry").param("statisticsDateFrom", "20260901")
                        .param("statisticsDateTo", "20261001").principal(authentication()))
                .andExpect(status().isOk());

        verify(lineExpiry).list(argThat(filters -> filters.statisticsDateFrom().equals("20260901")
                && filters.statisticsDateTo().equals("20261001")), eq(managerScope));
    }

    @Test
    void detailAndExportRoutesAreSeparateAndPreserveRawFields() throws Exception {
        var item = new LineExpiryLedgerQueryService.Item(77, "20261001", "G-1", "甲集团",
                "P-1", "产品一", "S-1", "客户经理甲", 8L, "客户经理甲", LocalDate.of(2026, 10, 31), 9);
        var rawFields = new ObjectMapper().readTree("[{\"columnIndex\":4,\"header\":\"成员状态\",\"value\":\"在用\"}]");
        when(lineExpiry.detail(77, managerScope)).thenReturn(new LineExpiryLedgerQueryService.Detail(
                item, 500, "line.xlsx", "专线表", 9, "20261001:S-1", rawFields,
                new ObjectMapper().createObjectNode(), "ASSIGNED", LocalDate.of(2026, 10, 22)));
        when(lineExpiry.export(any(), eq(managerScope))).thenReturn(new byte[]{4, 5, 6});

        mvc.perform(get("/api/line-expiry/77").principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rawFields[0].header").value("成员状态"))
                .andExpect(jsonPath("$.data.item.billingCutoffDate").value("2026-10-31"));
        mvc.perform(get("/api/line-expiry/export").param("remainingDaysMin", "-5")
                        .param("remainingDaysMax", "10").principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(new byte[]{4, 5, 6}))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")));
    }

    private static UsernamePasswordAuthenticationToken authentication() {
        return new UsernamePasswordAuthenticationToken("manager", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER_MANAGER")));
    }
}
