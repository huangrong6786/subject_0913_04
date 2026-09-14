package com.evops.geothermal;

import com.evops.common.RequestContext;
import com.evops.geothermal.dto.MonitorPointRequest;
import com.evops.geothermal.dto.TestSectionRequest;
import com.evops.geothermal.dto.WellGroupRequest;
import com.evops.geothermal.entity.MonitorPoint;
import com.evops.geothermal.entity.TestSection;
import com.evops.geothermal.entity.WellGroup;
import com.evops.geothermal.service.GeothermalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CSV 批量导入 REST 冒烟：multipart 上传、汇总/行明细/重试/设备隔离接口、Basic 认证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImportRestApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private GeothermalService geothermalService;

    private static final String HEADER =
            "objectCode,observedAt,pressureMpa,temperatureC,flowM3h,injectionVolumeM3,sourceDevice,serialNo";

    private static String basic(String user, String pwd) {
        return "Basic " + java.util.Base64.getEncoder()
                .encodeToString((user + ":" + pwd).getBytes());
    }

    private String createPoint() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        try {
            RequestContext.set(new RequestContext.Ctx("REST-SETUP-G-" + suffix, 1L, "接口测试员", "Asia/Shanghai"));
            WellGroupRequest g = new WellGroupRequest();
            g.setGroupCode("WG-REST-IMP-" + suffix);
            g.setGroupName("REST导入井组");
            WellGroup group = geothermalService.createGroup(g);
            RequestContext.set(new RequestContext.Ctx("REST-SETUP-S-" + suffix, 1L, "接口测试员", "Asia/Shanghai"));
            TestSectionRequest s = new TestSectionRequest();
            s.setWellGroupId(group.getId());
            s.setSectionCode("SEC-REST-IMP-" + suffix);
            s.setSectionName("REST导入试验段");
            TestSection section = geothermalService.createSection(s);
            RequestContext.set(new RequestContext.Ctx("REST-SETUP-P-" + suffix, 1L, "接口测试员", "Asia/Shanghai"));
            MonitorPointRequest p = new MonitorPointRequest();
            p.setTestSectionId(section.getId());
            p.setPointCode("PT-REST-IMP-" + suffix);
            p.setPointName("REST导入井口");
            p.setWellName("井-REST");
            MonitorPoint point = geothermalService.createPoint(p);
            return point.getPointCode();
        } finally {
            RequestContext.clear();
        }
    }

    @Test
    void importApi_requiresBasicAuth() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "obs.csv", "text/csv",
                (HEADER + "\n").getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/imports/observations").file(file))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void upload_summary_rows_retry_andQuarantine() throws Exception {
        String point = createPoint();
        String csv = HEADER + "\n"
                + point + ",2026-09-10 08:00:00,1.2,60,70,100,DEV-REST,SN-R-0001\n"
                + point + ",2026-09-10 08:05:00,bad,60,70,100,DEV-REST,SN-R-0002\n";
        MockMultipartFile file = new MockMultipartFile("file", "obs.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        // 上传导入：1 成功 1 失败
        MvcResult result = mockMvc.perform(multipart("/api/imports/observations").file(file)
                        .header("Authorization", basic("bootstrap", "bootstrap"))
                        .header("X-Request-No", "REST-IMP-" + UUID.randomUUID().toString().substring(0, 8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalRows").value(2))
                .andExpect(jsonPath("$.data.successRows").value(1))
                .andExpect(jsonPath("$.data.failedRows").value(1))
                .andExpect(jsonPath("$.data.status").value("COMPLETED_WITH_ERRORS"))
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        long fileId = json.path("data").path("fileId").asLong();

        // 汇总查询
        mockMvc.perform(get("/api/imports/observations/{id}", fileId)
                        .header("Authorization", basic("bootstrap", "bootstrap")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileId").value(fileId))
                .andExpect(jsonPath("$.data.totalShards").value(1));

        // 失败行明细：原始行号、字段、原值、原因
        mockMvc.perform(get("/api/imports/observations/{id}/rows", fileId)
                        .param("result", "FAILED")
                        .header("Authorization", basic("bootstrap", "bootstrap")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].rowNo").value(3))
                .andExpect(jsonPath("$.data.records[0].failField").value("pressureMpa"))
                .andExpect(jsonPath("$.data.records[0].failValue").value("bad"))
                .andExpect(jsonPath("$.data.records[0].failReason").exists());

        // 重试接口幂等（无失败分片）
        mockMvc.perform(post("/api/imports/observations/{id}/retry", fileId)
                        .header("Authorization", basic("bootstrap", "bootstrap"))
                        .header("X-Request-No", "REST-RETRY-" + UUID.randomUUID().toString().substring(0, 8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumed").value(false));

        // 同一文件重复上传：校验和幂等
        mockMvc.perform(multipart("/api/imports/observations").file(file)
                        .header("Authorization", basic("bootstrap", "bootstrap"))
                        .header("X-Request-No", "REST-IMP2-" + UUID.randomUUID().toString().substring(0, 8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.idempotent").value(true))
                .andExpect(jsonPath("$.data.fileId").value(fileId));

        // 坏传感器隔离 + 解除
        mockMvc.perform(post("/api/imports/devices/quarantine")
                        .header("Authorization", basic("bootstrap", "bootstrap"))
                        .header("X-Request-No", "REST-Q-" + UUID.randomUUID().toString().substring(0, 8))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceCode\":\"DEV-REST\",\"reason\":\"漂移\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUARANTINED"));
        mockMvc.perform(get("/api/imports/devices/quarantine")
                        .header("Authorization", basic("bootstrap", "bootstrap")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(post("/api/imports/devices/release")
                        .header("Authorization", basic("bootstrap", "bootstrap"))
                        .header("X-Request-No", "REST-R-" + UUID.randomUUID().toString().substring(0, 8))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceCode\":\"DEV-REST\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RELEASED"));
    }

    @Test
    void invalidHeader_isRejectedAtFileLevel() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "bad.csv", "text/csv",
                "col1,col2\n1,2\n".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/imports/observations").file(file)
                        .header("Authorization", basic("bootstrap", "bootstrap"))
                        .header("X-Request-No", "REST-BAD-" + UUID.randomUUID().toString().substring(0, 8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("缺少必需列")));
    }
}
