package com.evops.geothermal.csv;

import com.evops.common.BizException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 观测数据 CSV 解析支持。
 * 行级解析兼容 RFC4180 风格引号字段（字段内逗号、"" 转义），不支持字段内换行。
 * 表头支持中英文别名，映射到固定列位；必需列缺失时在文件级直接拒绝。
 */
public final class CsvParser {

    private CsvParser() {}

    /** 解析单行 CSV 为字段列表（已去引号、处理 "" 转义）。 */
    public static List<String> parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields;
    }

    /** 规范列名。 */
    public enum Col {
        OBJECT_CODE("objectCode", "对象编号", "object_code", "objectcode"),
        OBSERVED_AT("observedAt", "观测时间", "observed_at", "observationtime", "observation_time", "observedat"),
        PRESSURE_MPA("pressureMpa", "井口压力", "pressure_mpa", "pressure", "pressurempa"),
        TEMPERATURE_C("temperatureC", "温度", "temperature_c", "temperature", "temperaturec"),
        FLOW_M3H("flowM3h", "流量", "flow_m3h", "flow", "flowm3h"),
        INJECTION_VOLUME_M3("injectionVolumeM3", "回灌量", "injection_volume_m3", "injectionvolume",
                "injection_volume", "injectionvolumem3"),
        SOURCE_DEVICE("sourceDevice", "来源设备", "source_device", "device", "devicecode", "device_code", "sourcedevice"),
        SERIAL_NO("serialNo", "序列号", "serial_no", "serial", "serialno");

        private final String canonical;
        private final String[] aliases;

        Col(String canonical, String... aliases) {
            this.canonical = canonical;
            this.aliases = aliases;
        }

        public String canonical() { return canonical; }

        boolean matches(String headerName) {
            String norm = headerName == null ? "" : headerName.trim().toLowerCase();
            if (canonical.toLowerCase().equals(norm)) {
                return true;
            }
            for (String a : aliases) {
                if (a.toLowerCase().equals(norm)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 表头映射：规范列 -> 文件中的列下标。 */
    public static class Header {
        private final Map<Col, Integer> indexByCol;
        private final int columnCount;

        private Header(Map<Col, Integer> indexByCol, int columnCount) {
            this.indexByCol = indexByCol;
            this.columnCount = columnCount;
        }

        public static Header from(List<String> headerFields) {
            Map<Col, Integer> map = new LinkedHashMap<>();
            for (Col col : Col.values()) {
                for (int i = 0; i < headerFields.size(); i++) {
                    if (col.matches(headerFields.get(i))) {
                        map.put(col, i);
                        break;
                    }
                }
            }
            List<String> missing = new ArrayList<>();
            for (Col col : Col.values()) {
                if (!map.containsKey(col)) {
                    missing.add(col.canonical());
                }
            }
            if (!missing.isEmpty()) {
                throw new BizException("CSV_HEADER_INVALID",
                        "CSV 表头缺少必需列: " + String.join(", ", missing)
                                + "（支持中英文别名，如 objectCode/对象编号）");
            }
            return new Header(map, headerFields.size());
        }

        public int indexOf(Col col) { return indexByCol.get(col); }
        public int columnCount() { return columnCount; }

        /** 取字段原值（去空格）；列缺失或下标越界返回 null。 */
        public String get(List<String> fields, Col col) {
            int idx = indexOf(col);
            if (idx >= fields.size()) {
                return null;
            }
            String v = fields.get(idx);
            return v == null ? null : v.trim();
        }
    }
}
