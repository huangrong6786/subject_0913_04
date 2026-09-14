package com.evops.geothermal.bootstrap;

/** 演示数据装载结果统计。 */
public class SeedStats {
    public int groups;
    public int sections;
    public int points;
    public int shifts;
    public int batches;
    public int readings;
    public int telemetry;
    public int grants;
    public boolean skipped;

    @Override
    public String toString() {
        if (skipped) {
            return "SeedStats{skipped=true 演示数据已存在}";
        }
        return "SeedStats{groups=" + groups + ", sections=" + sections + ", points=" + points
                + ", shifts=" + shifts + ", batches=" + batches + ", readings=" + readings
                + ", telemetry=" + telemetry + ", grants=" + grants + "}";
    }
}
