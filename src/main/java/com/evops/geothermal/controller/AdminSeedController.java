package com.evops.geothermal.controller;

import com.evops.common.ApiResponse;
import com.evops.common.BizException;
import com.evops.geothermal.bootstrap.DemoDataSeeder;
import com.evops.geothermal.bootstrap.SeedStats;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 压测演示数据装载入口（运维侧）。
 * 默认关闭：需配置 evops.demo-seed.token，并在请求头 X-Seed-Token 中携带相同值。
 * 数据生成幂等（已存在 SEED-WG-* 时跳过）。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminSeedController {

    private final DemoDataSeeder seeder;

    @Value("${evops.demo-seed.token:}")
    private String configuredToken;

    public AdminSeedController(DemoDataSeeder seeder) {
        this.seeder = seeder;
    }

    @PostMapping("/demo-seed")
    public ApiResponse<SeedStats> seed(@RequestHeader(value = "X-Seed-Token", required = false) String token) {
        if (configuredToken == null || configuredToken.trim().isEmpty()) {
            throw new BizException("FORBIDDEN", "演示数据装载未启用（evops.demo-seed.token 未配置）");
        }
        if (token == null || !token.equals(configuredToken)) {
            throw new BizException("FORBIDDEN", "装载令牌无效");
        }
        return ApiResponse.ok(seeder.seedIfAbsent());
    }
}
