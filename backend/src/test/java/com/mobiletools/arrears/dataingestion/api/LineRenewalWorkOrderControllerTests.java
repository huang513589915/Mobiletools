package com.mobiletools.arrears.dataingestion.api;

import com.mobiletools.arrears.dataingestion.ledger.LineRenewalWorkOrderService;
import com.mobiletools.arrears.dataingestion.ledger.SnapshotAccessScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LineRenewalWorkOrderControllerTests {
    private MockMvc mvc;
    private LineRenewalWorkOrderService workOrders;
    private SnapshotAccessScopeService scopes;
    private SnapshotAccessScopeService.Scope managerScope;

    @BeforeEach
    void setUp() {
        workOrders = mock(LineRenewalWorkOrderService.class);
        scopes = mock(SnapshotAccessScopeService.class);
        managerScope = new SnapshotAccessScopeService.Scope(false, 8);
        mvc = MockMvcBuilders.standaloneSetup(new LineRenewalWorkOrderController(workOrders, scopes)).build();
        when(scopes.resolve(any())).thenReturn(managerScope);
    }

    @Test
    void patchEndpointMapsStatusAndReturnsHandlingInformation() throws Exception {
        when(workOrders.updateHandling(eq(42L), eq("已续签"), eq(null), eq(managerScope))).thenReturn(
                new LineRenewalWorkOrderService.UpdateResult(42, "已续签", 8L, "经理甲",
                        LocalDateTime.of(2026, 11, 4, 12, 30, 0), true));

        mvc.perform(patch("/api/line-renewal-work-orders/42/status")
                        .principal(authentication()).contentType("application/json").content("{\"status\":\"已续签\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("已续签"))
                .andExpect(jsonPath("$.data.lastHandledByName").value("经理甲"))
                .andExpect(jsonPath("$.data.changed").value(true));
        verify(workOrders).updateHandling(42L, "已续签", null, managerScope);
    }

    private static UsernamePasswordAuthenticationToken authentication() {
        return new UsernamePasswordAuthenticationToken("manager", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER_MANAGER")));
    }
}
