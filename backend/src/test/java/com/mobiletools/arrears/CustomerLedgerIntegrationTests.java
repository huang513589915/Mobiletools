package com.mobiletools.arrears;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc
class CustomerLedgerIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test void pageSupportsServerFiltersSortAndPagingWithinScope() throws Exception {
        var all=json.readTree(mvc.perform(get("/api/customers/page").param("page","1").param("pageSize","2").param("sort","name").param("direction","desc").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertEquals(2,all.path("items").size()); assertTrue(all.path("total").asLong()>=3); assertEquals(2,all.path("pageSize").asInt());
        var first=all.path("items").get(0).path("name").asText(); var second=all.path("items").get(1).path("name").asText(); assertTrue(first.compareTo(second)>=0);
        var empty=json.readTree(mvc.perform(get("/api/customers/page").param("q","NO-SUCH-CUSTOMER").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertEquals(0,empty.path("total").asLong()); assertTrue(empty.path("items").isEmpty());
        var manager=json.readTree(mvc.perform(get("/api/customers/page").param("pageSize","100").with(user("huangling").roles("CUSTOMER_MANAGER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data/items");
        for(var item:manager) assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM user_customer_scope WHERE user_id=(SELECT id FROM app_user WHERE username='huangling') AND customer_id=?",Integer.class,item.path("id").asLong()));
        mvc.perform(get("/api/customers/page").param("sort","customer_name;drop").with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/customers/page").param("pageSize","101").with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
    }

    @Test void customerAndServiceDetailsReturnAssociatedHistoryAndEnforceScope() throws Exception {
        Long managerCustomer=jdbc.queryForObject("SELECT c.id FROM customer c JOIN user_customer_scope s ON s.customer_id=c.id JOIN app_user u ON u.id=s.user_id WHERE u.username='huangling' AND c.customer_code IS NOT NULL LIMIT 1",Long.class);
        Long forbidden=jdbc.queryForObject("SELECT c.id FROM customer c WHERE c.customer_code IS NOT NULL AND NOT EXISTS (SELECT 1 FROM user_customer_scope s JOIN app_user u ON u.id=s.user_id WHERE s.customer_id=c.id AND u.username='huangling') LIMIT 1",Long.class);
        var detail=json.readTree(mvc.perform(get("/api/customers/"+managerCustomer).with(user("huangling").roles("CUSTOMER_MANAGER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertEquals(managerCustomer,detail.path("customer").path("id").asLong());
        for(String key:new String[]{"services","bills","expiries","risks","tasks","followUps","batches"}) assertTrue(detail.path(key).isArray());
        mvc.perform(get("/api/customers/"+forbidden).with(user("huangling").roles("CUSTOMER_MANAGER"))).andExpect(status().isForbidden());
        Long service=jdbc.queryForObject("SELECT s.id FROM business_service s JOIN user_customer_scope sc ON sc.customer_id=s.customer_id JOIN app_user u ON u.id=sc.user_id WHERE u.username='huangling' LIMIT 1",Long.class);
        var serviceDetail=json.readTree(mvc.perform(get("/api/ledger/services/"+service).with(user("huangling").roles("CUSTOMER_MANAGER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertTrue(serviceDetail.path("service").path("service_code").isTextual());
        mvc.perform(get("/api/ledger/services/"+jdbc.queryForObject("SELECT s.id FROM business_service s JOIN customer c ON c.id=s.customer_id WHERE NOT EXISTS (SELECT 1 FROM user_customer_scope sc JOIN app_user u ON u.id=sc.user_id WHERE sc.customer_id=c.id AND u.username='huangling') LIMIT 1",Long.class)).with(user("huangling").roles("CUSTOMER_MANAGER"))).andExpect(status().isForbidden());
    }
}
