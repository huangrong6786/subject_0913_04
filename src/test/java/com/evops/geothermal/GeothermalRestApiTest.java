package com.evops.geothermal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * REST 层冒烟：统一 ApiResponse、Shiro Basic 认证、请求头透传到审计。
 * 不引入 spring-security-test，Basic 头手工拼装。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GeothermalRestApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private static String basic(String user, String pwd) {
        return "Basic " + java.util.Base64.getEncoder()
                .encodeToString((user + ":" + pwd).getBytes());
    }

    @Test
    void health_isAnonymous() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void businessApi_requiresBasicAuth() throws Exception {
        mockMvc.perform(get("/api/well-groups"))
                .andExpect(status().is4xxClientError()); // Shiro authcBasic 未认证
        mockMvc.perform(get("/api/well-groups").header("Authorization", basic("bootstrap", "wrong")))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void createGroup_unifiedResponse_andAuditHeaders() throws Exception {
        String code = "WG-REST-" + UUID.randomUUID().toString().substring(0, 6);
        String requestNo = "REST-" + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"groupCode\":\"" + code + "\",\"groupName\":\"REST井组\",\"location\":\"西安\"}";

        MvcResult result = mockMvc.perform(post("/api/well-groups")
                        .header("Authorization", basic("bootstrap", "bootstrap"))
                        .header("X-Request-No", requestNo)
                        .header("X-Operator-Id", "7777")
                        .header("X-Operator-Name", "接口测试员")
                        .header("X-Biz-Timezone", "Asia/Urumqi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("OK"))
                .andExpect(jsonPath("$.data.groupCode").value(code))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        Long id = json.path("data").path("id").asLong();

        // 审计记录可按请求号查到，且带操作者与业务时区
        mockMvc.perform(get("/api/audits").param("requestNo", requestNo)
                        .header("Authorization", basic("bootstrap", "bootstrap")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].requestNo").value(requestNo))
                .andExpect(jsonPath("$.data[0].operatorId").value(7777))
                .andExpect(jsonPath("$.data[0].operatorName").value("接口测试员"))
                .andExpect(jsonPath("$.data[0].bizTimezone").value("Asia/Urumqi"))
                .andExpect(jsonPath("$.data[0].objectId").value(id));
    }

    @Test
    void duplicateBusinessKey_returnsUnifiedFail() throws Exception {
        String code = "WG-DUP-" + UUID.randomUUID().toString().substring(0, 6);
        String body = "{\"groupCode\":\"" + code + "\",\"groupName\":\"一\"}";
        mockMvc.perform(post("/api/well-groups")
                        .header("Authorization", basic("bootstrap", "bootstrap"))
                        .header("X-Request-No", "D1-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(post("/api/well-groups")
                        .header("Authorization", basic("bootstrap", "bootstrap"))
                        .header("X-Request-No", "D2-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("井组编码已存在")));
    }
}
