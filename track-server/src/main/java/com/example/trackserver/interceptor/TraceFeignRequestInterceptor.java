package com.example.trackserver.interceptor;

import com.example.trackserver.context.TraceContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Feign 请求拦截器：在发起跨服务调用时，自动从 TraceContext 中取出 traceId
 * 并注入到 X-Trace-Id 请求头中，实现链路 ID 跨网络传递。
 */
@Slf4j
@Component
public class TraceFeignRequestInterceptor implements RequestInterceptor {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public void apply(RequestTemplate template) {
        String traceId = TraceContext.get();
        if (traceId != null) {
            template.header(TRACE_ID_HEADER, traceId);
        } else {
            log.warn("TraceContext is empty while sending Feign request to {}", template.url());
        }
    }
}
