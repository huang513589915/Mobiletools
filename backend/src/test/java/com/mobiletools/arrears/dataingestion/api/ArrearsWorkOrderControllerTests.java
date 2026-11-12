package com.mobiletools.arrears.dataingestion.api;

import com.mobiletools.arrears.dataingestion.ledger.ArrearsWorkOrderService;
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

class ArrearsWorkOrderControllerTests {
    private MockMvc mvc;
    private ArrearsWorkOrderService workOrders;
    private SnapshotAccessScopeService scopes;
    private SnapshotAccessScopeService.Scope manager;

    @BeforeEach
    void setUp() {
        workOrders = mock(ArrearsWorkOrderService.class);
        scopes = mock(SnapshotAccessScopeService.class);
        manager = new SnapshotAccessScopeService.Scope(false, 8);
        mvc = MockMvcBuilders.standaloneSetup(new ArrearsWorkOrderController(workOrders, scopes)).build();
        when(scopes.resolve(any())).thenReturn(manager);
    }

    @Test
    void patchEndpointAcceptsStatusAndOptionalFollowUpNote() throws Exception {
        when(workOrders.updateHandling(eq(21L), eq("跟进中"), eq("客户已回复"), eq(manager))).thenReturn(
                new ArrearsWorkOrderService.UpdateResult(21, "跟进中", 8L, "经理甲",
                        LocalDateTime.of(2026, 11, 4, 12, 30), true, true, true));

        mvc.perform(patch("/api/arrears-work-orders/21/status").principal(authentication())
                        .contentType("application/json")
                        .content("{\"status\":\"跟进中\",\"followUpNote\":\"客户已回复\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workOrderId").value(21))
                .andExpect(jsonPath("$.data.status").value("跟进中"))
                .andExpect(jsonPath("$.data.followUpAdded").value(true));
        verify(workOrders).updateHandling(21, "跟进中", "客户已回复", manager);
    }

    private static UsernamePasswordAuthenticationToken authentication() {
        return new UsernamePasswordAuthenticationToken("manager", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER_MANAGER")));
    }
}
