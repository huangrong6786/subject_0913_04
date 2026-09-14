package com.evops.geothermal.dto;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;

/**
 * 游标编解码：承载确定性排序键 (bizDate, id)。
 * 形态为 Base64("yyyy-MM-dd|id")，对外不透明；用于 keyset 分页补齐。
 */
public final class PageCursor {

    private final LocalDate bizDate;
    private final long id;

    private PageCursor(LocalDate bizDate, long id) {
        this.bizDate = bizDate;
        this.id = id;
    }

    public LocalDate getBizDate() { return bizDate; }
    public long getId() { return id; }

    public static String encode(LocalDate bizDate, long id) {
        String raw = bizDate.toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static PageCursor decode(String cursor) {
        if (cursor == null || cursor.trim().isEmpty()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
            int sep = raw.lastIndexOf('|');
            if (sep <= 0) {
                return null;
            }
            return new PageCursor(LocalDate.parse(raw.substring(0, sep)),
                    Long.parseLong(raw.substring(sep + 1)));
        } catch (Exception ex) {
            return null;
        }
    }
}
