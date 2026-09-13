package com.evops.common;

/**
 * 请求级上下文：请求号、操作者、业务时区。
 * 由 RequestContextFilter 从请求头解析，业务层与 MetaObjectHandler 共用。
 */
public final class RequestContext {
    private static final ThreadLocal<Ctx> HOLDER = new ThreadLocal<>();

    private RequestContext() {}

    public static void set(Ctx ctx) { HOLDER.set(ctx); }
    public static Ctx get() { return HOLDER.get(); }
    public static void clear() { HOLDER.remove(); }

    public static String requireRequestNo() {
        Ctx ctx = HOLDER.get();
        if (ctx == null || ctx.getRequestNo() == null) {
            throw new BizException("缺少请求号（X-Request-No）");
        }
        return ctx.getRequestNo();
    }

    public static Long currentOperatorId() {
        Ctx ctx = HOLDER.get();
        return ctx == null ? null : ctx.getOperatorId();
    }

    public static class Ctx {
        private final String requestNo;
        private final Long operatorId;
        private final String operatorName;
        private final String bizTimezone;

        public Ctx(String requestNo, Long operatorId, String operatorName, String bizTimezone) {
            this.requestNo = requestNo;
            this.operatorId = operatorId;
            this.operatorName = operatorName;
            this.bizTimezone = bizTimezone;
        }

        public String getRequestNo() { return requestNo; }
        public Long getOperatorId() { return operatorId; }
        public String getOperatorName() { return operatorName; }
        public String getBizTimezone() { return bizTimezone; }
    }
}
