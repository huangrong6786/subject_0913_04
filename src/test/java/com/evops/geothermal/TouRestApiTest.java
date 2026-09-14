package com.evops.geothermal;

import com.evops.common.RequestContext;
import com.evops.geothermal.dto.MonitorPointRequest;
import com.evops.geothermal.dto.TestSectionRequest;
import com.evops.geothermal.dto.WellGroupRequest;
import com.evops.geothermal.entity.ImportFile;
import com.evops.geothermal.entity.MonitorPoint;
import com.evops.geothermal.entity.WellGroup;
import com.evops.geothermal.entity.WellheadObservation;
import com.evops.geothermal.mapper.ImportFileMapper;
import com.evops.geothermal.mapper.WellheadObservationMapper;
import com.evops.geothermal.service.GeothermalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 时序规则与试验窗口计算 REST 冒烟：Basic 认证强制、规则建档-启用、冻结不可改、计算与历史读取。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TouRestApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private GeothermalService geothermalService;
    @Autowired private WellheadObservationMapper observationMapper;
    @Autowired private ImportFileMapper importFileMapper;
    @Autowired private com.evops.geothermal.mapper.ObjectGrantMapper objectGrantMapper;
    @Autowired private com.evops.geothermal.mapper.MonitorPointMapper monitorPointMapper;
    @Autowired private com.evops.geothermal.mapper.TestSectionMapper testSectionMapper;

    private static String basic(String user, String pwd) {
        return "Basic " + java.util.Base64.getEncoder().encodeToString((user + ":" + pwd).getBytes());
    }

    private static final String AUTH = basic("t1admin", "t1admin");

    private String suffix;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
    }

    /** 建井组（Asia/Shanghai）/试验段/监测点。 */
    private MonitorPoint setupPoint() {
        try {
            RequestContext.set(new RequestContext.Ctx("REST-TOU-G-" + suffix, 1L, "接口测试员", "Asia/Shanghai"));
            WellGroupRequest g = new WellGroupRequest();
            g.setGroupCode("WG-RT-" + suffix);
            g.setGroupName("REST时序井组");
            g.setTimezone("Asia/Shanghai");
            WellGroup group = geothermalService.createGroup(g);
            RequestContext.set(new RequestContext.Ctx("REST-TOU-S-" + suffix, 1L, "接口测试员", "Asia/Shanghai"));
            TestSectionRequest s = new TestSectionRequest();
            s.setWellGroupId(group.getId());
            s.setSectionCode("SEC-RT-" + suffix);
            s.setSectionName("REST试验段");
            geothermalService.createSection(s);
            RequestContext.set(new RequestContext.Ctx("REST-TOU-P-" + suffix, 1L, "接口测试员", "Asia/Shanghai"));
            MonitorPointRequest p = new MonitorPointRequest();
            p.setTestSectionId(geothermalService.listSections(group.getId()).get(0).getId());
            p.setPointCode("PT-RT-" + suffix);
            p.setPointName("REST监测点");
            p.setWellName("DR-RT");
            return geothermalService.createPoint(p);
        } finally {
            RequestContext.clear();
        }
    }

    private String json(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    @Test
    void touApi_requiresBasicAuth() throws Exception {
        mockMvc.perform(post("/api/tou/chains").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/api/tou/calculations"))
                .andExpect(status().is4xxClientError());
    }


    @Test
    void fullFlow_createEnable_calculate_frozenRejected_historyReadable() throws Exception {
        MonitorPoint point = setupPoint();

        // 1) 建规则链
        Map<String, Object> chainReq = new LinkedHashMap<>();
        chainReq.put("chainCode", "CHAIN-RT-" + suffix);
        chainReq.put("chainName", "REST峰平谷链");
        chainReq.put("targetType", "MONITOR_POINT");
        chainReq.put("targetId", point.getId());
        MvcResult chainRes = mockMvc.perform(post("/api/tou/chains")
                        .header("Authorization", AUTH)
                        .header("X-Request-No", "RT-CHAIN-" + suffix)
                        .header("X-Operator-Id", "3001").header("X-Operator-Name", "接口核算员")
                        .contentType(MediaType.APPLICATION_JSON).content(json(chainReq)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andReturn();
        JsonNode chainNode = objectMapper.readTree(chainRes.getResponse().getContentAsString());
        long chainId = chainNode.path("data").path("id").asLong();

        // 2) 建草稿（跨午夜谷段 + 峰 + 平，全覆盖）
        Map<String, Object> draftReq = new LinkedHashMap<>();
        draftReq.put("effectiveFrom", "2026-09-10T00:00:00");
        draftReq.put("pressureThresholdMpa", "1.200");
        draftReq.put("intervals", java.util.Arrays.asList(
                interval("VALLEY_NIGHT", "VALLEY", 1320, 360, "0.5"),
                interval("FLAT_DAY", "FLAT", 360, 1080, "1.0"),
                interval("PEAK_EVE", "PEAK", 1080, 1320, "1.5")));
        MvcResult draftRes = mockMvc.perform(post("/api/tou/chains/" + chainId + "/versions")
                        .header("Authorization", AUTH).header("X-Request-No", "RT-DRAFT-" + suffix)
                        .header("X-Operator-Id", "3001").header("X-Operator-Name", "接口核算员")
                        .contentType(MediaType.APPLICATION_JSON).content(json(draftReq)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn();
        long setId = objectMapper.readTree(draftRes.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // 2b) 重叠区间拒绝（草稿维护接口返回 success=false）
        mockMvc.perform(post("/api/tou/versions/" + setId + "/intervals/PEAK_EXTRA")
                        .header("Authorization", AUTH).header("X-Request-No", "RT-OVERLAP-" + suffix)
                        .header("X-Operator-Id", "3001").header("X-Operator-Name", "接口核算员")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(interval(null, "PEAK", 300, 400, "2.0"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false));

        // 3) 启用
        mockMvc.perform(post("/api/tou/versions/" + setId + "/enable")
                        .header("Authorization", AUTH).header("X-Request-No", "RT-ENABLE-" + suffix)
                        .header("X-Operator-Id", "3001").header("X-Operator-Name", "接口核算员"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ENABLED"))
                .andExpect(jsonPath("$.data.frozenSnapshot").isNotEmpty());

        // 3b) 启用后原地修改被拒绝
        mockMvc.perform(put("/api/tou/versions/" + setId + "/intervals/FLAT_DAY")
                        .header("Authorization", AUTH).header("X-Request-No", "RT-FROZEN-" + suffix)
                        .header("X-Operator-Id", "3001").header("X-Operator-Name", "接口核算员")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(interval(null, "FLAT", 360, 1080, "9.99"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false));

        // 4) 落两条观测（峰 18:00 与跨午夜谷 00:30）
        seedObservation(point, LocalDateTime.of(2026, 9, 11, 18, 0), "1.500", "200");
        seedObservation(point, LocalDateTime.of(2026, 9, 12, 0, 30), "1.100", "100");

        // 5) 执行试验窗口计算 [09-11 18:00, 09-12 06:00)
        Map<String, Object> calcReq = new LinkedHashMap<>();
        calcReq.put("targetType", "MONITOR_POINT");
        calcReq.put("targetId", point.getId());
        calcReq.put("windowStart", "2026-09-11T18:00:00");
        calcReq.put("windowEnd", "2026-09-12T06:00:00");
        MvcResult calcRes = mockMvc.perform(post("/api/tou/calculations")
                        .header("Authorization", AUTH).header("X-Request-No", "RT-CALC-" + suffix)
                        .header("X-Operator-Id", "3001").header("X-Operator-Name", "接口核算员")
                        .contentType(MediaType.APPLICATION_JSON).content(json(calcReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.observationCount").value(2))
                .andExpect(jsonPath("$.data.totalScaledInjectionM3").value(350.000))
                .andExpect(jsonPath("$.data.windowTimezone").value("Asia/Shanghai"))
                .andReturn();
        long resultId = objectMapper.readTree(calcRes.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // 5b) 同窗口重放幂等（同 id）
        mockMvc.perform(post("/api/tou/calculations")
                        .header("Authorization", AUTH).header("X-Request-No", "RT-CALC2-" + suffix)
                        .header("X-Operator-Id", "3001").header("X-Operator-Name", "接口核算员")
                        .contentType(MediaType.APPLICATION_JSON).content(json(calcReq)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value((int) resultId));

        // 5c) 重叠窗口拒绝
        Map<String, Object> overlapReq = new LinkedHashMap<>(calcReq);
        overlapReq.put("windowStart", "2026-09-12T00:00:00");
        overlapReq.put("windowEnd", "2026-09-12T12:00:00");
        mockMvc.perform(post("/api/tou/calculations")
                        .header("Authorization", AUTH).header("X-Request-No", "RT-CALC3-" + suffix)
                        .header("X-Operator-Id", "3001").header("X-Operator-Name", "接口核算员")
                        .contentType(MediaType.APPLICATION_JSON).content(json(overlapReq)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false));

        // 6) 历史结果详情：明细 2 条，跨午夜谷段 dayOffset=-1
        mockMvc.perform(get("/api/tou/calculations/" + resultId)
                        .header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.details.length()").value(2))
                .andExpect(jsonPath("$.data.details[0].segmentType").value("PEAK"))
                .andExpect(jsonPath("$.data.details[0].ruleVersionNo").value(1))
                .andExpect(jsonPath("$.data.details[1].segmentType").value("VALLEY"))
                .andExpect(jsonPath("$.data.details[1].dayOffset").value(-1))
                .andExpect(jsonPath("$.data.segmentTotalsM3.PEAK").value("300.000"))
                .andExpect(jsonPath("$.data.segmentTotalsM3.VALLEY").value("50.000"));
    }

    @Test
    void readonlyAccount_isScopedByObjectGrant() throws Exception {
        MonitorPoint point = setupPoint();

        // 建链并启用一版规则
        Map<String, Object> chainReq = new LinkedHashMap<>();
        chainReq.put("chainCode", "CHAIN-GRANT-" + suffix);
        chainReq.put("chainName", "授权收敛链");
        chainReq.put("targetType", "MONITOR_POINT");
        chainReq.put("targetId", point.getId());
        MvcResult chainRes = mockMvc.perform(post("/api/tou/chains")
                        .header("Authorization", AUTH)
                        .header("X-Request-No", "RT-G-CHAIN-" + suffix)
                        .header("X-Operator-Id", "3001").header("X-Operator-Name", "接口核算员")
                        .contentType(MediaType.APPLICATION_JSON).content(json(chainReq)))
                .andExpect(status().isOk()).andReturn();
        long chainId = objectMapper.readTree(chainRes.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        Map<String, Object> draftReq = new LinkedHashMap<>();
        draftReq.put("effectiveFrom", "2026-09-10T00:00:00");
        draftReq.put("pressureThresholdMpa", "1.200");
        draftReq.put("intervals", java.util.Arrays.asList(
                interval("VALLEY_NIGHT", "VALLEY", 1320, 360, "0.5"),
                interval("FLAT_DAY", "FLAT", 360, 1080, "1.0"),
                interval("PEAK_EVE", "PEAK", 1080, 1320, "1.5")));
        MvcResult draftRes = mockMvc.perform(post("/api/tou/chains/" + chainId + "/versions")
                        .header("Authorization", AUTH).header("X-Request-No", "RT-G-DRAFT-" + suffix)
                        .header("X-Operator-Id", "3001").header("X-Operator-Name", "接口核算员")
                        .contentType(MediaType.APPLICATION_JSON).content(json(draftReq)))
                .andExpect(status().isOk()).andReturn();
        long setId = objectMapper.readTree(draftRes.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        mockMvc.perform(post("/api/tou/versions/" + setId + "/enable")
                        .header("Authorization", AUTH).header("X-Request-No", "RT-G-EN-" + suffix)
                        .header("X-Operator-Id", "3001").header("X-Operator-Name", "接口核算员"))
                .andExpect(status().isOk());

        seedObservation(point, LocalDateTime.of(2026, 9, 11, 18, 0), "1.500", "200");
        Map<String, Object> calcReq = new LinkedHashMap<>();
        calcReq.put("targetType", "MONITOR_POINT");
        calcReq.put("targetId", point.getId());
        calcReq.put("windowStart", "2026-09-11T18:00:00");
        calcReq.put("windowEnd", "2026-09-12T06:00:00");
        MvcResult calcRes = mockMvc.perform(post("/api/tou/calculations")
                        .header("Authorization", AUTH).header("X-Request-No", "RT-G-CALC-" + suffix)
                        .header("X-Operator-Id", "3001").header("X-Operator-Name", "接口核算员")
                        .contentType(MediaType.APPLICATION_JSON).content(json(calcReq)))
                .andExpect(status().isOk()).andReturn();
        long resultId = objectMapper.readTree(calcRes.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        String viewAuth = basic("t1view", "t1view");
        // 未授权：列表为空、详情拒绝（写操作在 Shiro 层不区分角色，但读按授权收敛）
        mockMvc.perform(get("/api/tou/chains").header("Authorization", viewAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/api/tou/calculations").header("Authorization", viewAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/api/tou/calculations/" + resultId).header("Authorization", viewAuth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false));

        // 授权该井组后：链与结果均可读
        Long grantId = grantFirstGroup(point, "t1view");
        try {
            mockMvc.perform(get("/api/tou/chains").header("Authorization", viewAuth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value((int) chainId));
            mockMvc.perform(get("/api/tou/calculations/" + resultId).header("Authorization", viewAuth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.details.length()").value(1));
        } finally {
            revokeGrant(grantId);
        }
    }

    private Long grantFirstGroup(MonitorPoint point, String account) {
        try {
            RequestContext.set(new RequestContext.Ctx("RT-GRANT-" + suffix, 1L, "接口测试员", "Asia/Shanghai"));
            com.evops.geothermal.entity.MonitorPoint full = monitorPointMapper.selectById(point.getId());
            com.evops.geothermal.entity.TestSection section = testSectionMapper.selectById(full.getTestSectionId());
            com.evops.geothermal.entity.ObjectGrant grant = new com.evops.geothermal.entity.ObjectGrant();
            grant.setTenantId(1L);
            grant.setGranteeAccount(account);
            grant.setObjectType("WELL_GROUP");
            grant.setObjectId(section.getWellGroupId());
            objectGrantMapper.insert(grant);
            return grant.getId();
        } finally {
            RequestContext.clear();
        }
    }

    /** 授权断言后立即回收，避免污染共享内存库中 t1view 的既有授权集合。 */
    private void revokeGrant(Long grantId) {
        if (grantId != null) {
            objectGrantMapper.deleteById(grantId);
        }
    }

    private Map<String, Object> interval(String code, String seg, int start, int end, String coeff) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (code != null) {
            m.put("intervalCode", code);
        }
        m.put("segmentType", seg);
        m.put("startMinute", start);
        m.put("endMinute", end);
        m.put("priceCoefficient", coeff);
        return m;
    }

    private void seedObservation(MonitorPoint point, LocalDateTime at, String pressure, String volume) {
        RequestContext.set(new RequestContext.Ctx("RT-OBS-" + suffix + "-" + at, 1L, "接口测试员", "Asia/Shanghai"));
        ImportFile importFile = new ImportFile();
        importFile.setTenantId(1L);
        importFile.setFileName("rt-" + suffix + "-" + at + ".csv");
        importFile.setChecksum("sha256-rt-" + suffix + "-" + at);
        importFile.setShardSize(1000);
        importFile.setStatus("COMPLETED");
        importFileMapper.insert(importFile);

        WellheadObservation o = new WellheadObservation();
        o.setTenantId(1L);
        o.setMonitorPointId(point.getId());
        o.setObjectCode(point.getPointCode());
        o.setSerialNo("SN-RT-" + suffix + "-" + at.toString().replace("-", "").replace(":", ""));
        o.setSourceDevice("DEV-RT");
        o.setObservedAt(at);
        o.setBizDate(at.toLocalDate());
        o.setPressureMpa(new BigDecimal(pressure));
        o.setTemperatureC(new BigDecimal("65.000"));
        o.setFlowM3h(new BigDecimal("80.000"));
        o.setInjectionVolumeM3(new BigDecimal(volume));
        o.setImportFileId(importFile.getId());
        o.setRowNo(1);
        observationMapper.insert(o);
        RequestContext.clear();
    }
}
