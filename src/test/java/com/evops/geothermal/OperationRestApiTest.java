package com.evops.geothermal;

import com.evops.geothermal.bootstrap.DemoDataSeeder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 运营检索 REST 层冒烟：Shiro HTTP Basic 多租户账号、租户/角色数据权限、
 * pageSize 1-100 约束、遥测分区裁剪必填，均在 HTTP 层生效。
 * 依赖内存库中由 {@link DemoDataSeeder} 幂等装载的压测数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationRestApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DemoDataSeeder seeder;

    private static String basic(String user, String pwd) {
        return "Basic " + java.util.Base64.getEncoder().encodeToString((user + ":" + pwd).getBytes());
    }

    @BeforeAll
    void seed() {
        seeder.seedIfAbsent(); // 幂等
    }

    @Test
    void operationsApi_requiresAuth() throws Exception {
        mockMvc.perform(get("/api/operations/batches"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void adminAccounts_areTenantScoped() throws Exception {
        // 租户1管理员：仅本租户；安全窗口 09-01~09-09 共 180 个 SEED 批次
        mockMvc.perform(get("/api/operations/batches")
                        .header("Authorization", basic("t1admin", "t1admin"))
                        .param("bizDateFrom", "2026-09-01").param("bizDateTo", "2026-09-09")
                        .param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total").value(180));

        // 租户2管理员：200 个批次
        mockMvc.perform(get("/api/operations/batches")
                        .header("Authorization", basic("t2admin", "t2admin"))
                        .param("pageSize", "100"))
                .andExpect(jsonPath("$.data.total").value(200));

        // 错误密码拒绝
        mockMvc.perform(get("/api/operations/batches")
                        .header("Authorization", basic("t1admin", "wrong")))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void viewer_seesOnlyGrantedGroups() throws Exception {
        mockMvc.perform(get("/api/operations/batches")
                        .header("Authorization", basic("t1view", "t1view"))
                        .param("bizDateFrom", "2026-09-01").param("bizDateTo", "2026-09-09")
                        .param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(90)) // 10 授权井组 × 9 日期
                .andExpect(jsonPath("$.data.records[0].groupCode").value(org.hamcrest.Matchers.startsWith("SEED-WG-")));
    }

    @Test
    void pageSize_outOfRange_rejected() throws Exception {
        mockMvc.perform(get("/api/operations/batches")
                        .header("Authorization", basic("t1admin", "t1admin"))
                        .param("pageSize", "101"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("pageSize")));
    }

    @Test
    void cursorPaging_returnsNextCursor() throws Exception {
        mockMvc.perform(get("/api/operations/batches")
                        .header("Authorization", basic("t1admin", "t1admin"))
                        .param("bizDateFrom", "2026-09-01").param("bizDateTo", "2026-09-09")
                        .param("pageSize", "50"))
                .andExpect(jsonPath("$.data.records.length()").value(50))
                .andExpect(jsonPath("$.data.nextCursor").isNotEmpty())
                .andExpect(jsonPath("$.data.hasMore").value(true));
    }

    @Test
    void telemetryAggregate_requiresPartitionWindow() throws Exception {
        // 缺日期：拒绝，强制分区裁剪
        mockMvc.perform(get("/api/operations/telemetry/aggregate")
                        .header("Authorization", basic("t1admin", "t1admin")))
                .andExpect(jsonPath("$.success").value(false));
        // 合法窗口：返回分区裁剪回参与桶
        mockMvc.perform(get("/api/operations/telemetry/aggregate")
                        .header("Authorization", basic("t1admin", "t1admin"))
                        .param("dateFrom", "2026-09-08").param("dateTo", "2026-09-09")
                        .param("granularity", "HOUR"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.partitionDateFrom").value("2026-09-08"))
                .andExpect(jsonPath("$.data.totalSamples").value(50_000)); // 20 井组 × 2 日 × 1250
    }

    @Test
    void wellGroupPressure_oneCallAllGroups() throws Exception {
        mockMvc.perform(get("/api/operations/telemetry/well-group-pressure")
                        .header("Authorization", basic("t1admin", "t1admin"))
                        .param("dateFrom", "2026-09-08").param("dateTo", "2026-09-10"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(20)); // 单集合查询返回租户全部井组
        // t1view 仅授权 10 井组
        mockMvc.perform(get("/api/operations/telemetry/well-group-pressure")
                        .header("Authorization", basic("t1view", "t1view"))
                        .param("dateFrom", "2026-09-08").param("dateTo", "2026-09-10"))
                .andExpect(jsonPath("$.data.length()").value(10));
    }
}
