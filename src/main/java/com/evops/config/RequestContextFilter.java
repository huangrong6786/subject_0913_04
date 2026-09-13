package com.evops.config;

import com.evops.common.RequestContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.FilterConfig;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;

/**
 * 解析请求头并填充 RequestContext：
 * X-Request-No（请求号，缺省自动生成）、X-Operator-Id、X-Operator-Name、X-Biz-Timezone（默认 Asia/Shanghai）。
 */
@Component("bizRequestContextFilter")
@Order(1)
public class RequestContextFilter implements Filter {

    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    @Override
    public void init(FilterConfig filterConfig) { }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        String requestNo = req.getHeader("X-Request-No");
        if (requestNo == null || requestNo.trim().isEmpty()) {
            requestNo = "AUTO-" + UUID.randomUUID().toString().replace("-", "");
        }
        Long operatorId = parseLong(req.getHeader("X-Operator-Id"));
        String operatorName = req.getHeader("X-Operator-Name");
        String timezone = req.getHeader("X-Biz-Timezone");
        if (timezone == null || timezone.trim().isEmpty()) {
            timezone = DEFAULT_TIMEZONE;
        }
        RequestContext.set(new RequestContext.Ctx(requestNo, operatorId, operatorName, timezone));
        try {
            chain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }

    private Long parseLong(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
