package com.evops.geothermal.dto;

import java.util.List;

/**
 * 稳定分页结果：当前页记录、满足全部 AND/范围条件的总数、回传的下一页游标。
 * 排序为确定性 (biz_date DESC, id ASC)，翻页过程中不重不漏。
 */
public class PageResult<T> {
    private List<T> records;
    private long total;
    private int pageSize;
    private Integer page;
    private String nextCursor;
    private boolean hasMore;

    public PageResult(List<T> records, long total, int pageSize, Integer page,
                      String nextCursor, boolean hasMore) {
        this.records = records;
        this.total = total;
        this.pageSize = pageSize;
        this.page = page;
        this.nextCursor = nextCursor;
        this.hasMore = hasMore;
    }

    public List<T> getRecords() { return records; }
    public long getTotal() { return total; }
    public int getPageSize() { return pageSize; }
    public Integer getPage() { return page; }
    public String getNextCursor() { return nextCursor; }
    public boolean isHasMore() { return hasMore; }
}
