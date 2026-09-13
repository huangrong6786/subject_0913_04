package com.evops.geothermal.dto;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 井口监测数据上报：压力、温度、流量与回灌量必须同批次原子落库。
 * readings 中每一项四要素齐全，整体在同一事务内写入。
 */
public class ReadingSubmitRequest {
    @NotEmpty
    @Valid
    private List<ReadingItem> readings;

    public List<ReadingItem> getReadings() { return readings; }
    public void setReadings(List<ReadingItem> readings) { this.readings = readings; }

    public static class ReadingItem {
        /** 业务时区下的本地时刻，缺省取服务端当前时刻 */
        private String readingTimeLocal;
        @javax.validation.constraints.NotNull
        private java.math.BigDecimal pressureMpa;
        @javax.validation.constraints.NotNull
        private java.math.BigDecimal temperatureC;
        @javax.validation.constraints.NotNull
        private java.math.BigDecimal flowM3h;
        @javax.validation.constraints.NotNull
        private java.math.BigDecimal injectionVolumeM3;

        public String getReadingTimeLocal() { return readingTimeLocal; }
        public void setReadingTimeLocal(String readingTimeLocal) { this.readingTimeLocal = readingTimeLocal; }
        public java.math.BigDecimal getPressureMpa() { return pressureMpa; }
        public void setPressureMpa(java.math.BigDecimal pressureMpa) { this.pressureMpa = pressureMpa; }
        public java.math.BigDecimal getTemperatureC() { return temperatureC; }
        public void setTemperatureC(java.math.BigDecimal temperatureC) { this.temperatureC = temperatureC; }
        public java.math.BigDecimal getFlowM3h() { return flowM3h; }
        public void setFlowM3h(java.math.BigDecimal flowM3h) { this.flowM3h = flowM3h; }
        public java.math.BigDecimal getInjectionVolumeM3() { return injectionVolumeM3; }
        public void setInjectionVolumeM3(java.math.BigDecimal injectionVolumeM3) { this.injectionVolumeM3 = injectionVolumeM3; }
    }
}
