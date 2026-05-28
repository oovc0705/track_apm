package com.example.trackserver.interceptor;

import com.example.trackserver.context.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * RestTemplate 拦截器：在发起 HTTP 调用时，自动从 TraceContext 中取出 traceId
 * 并注入到 X-Trace-Id 请求头中。
 */
public class TraceRestTemplateInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TraceRestTemplateInterceptor.class);
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        String traceId = TraceContext.get();
        if (traceId != null) {
            request.getHeaders().set(TRACE_ID_HEADER, traceId);
        } else {
            log.warn("TraceContext is empty while sending RestTemplate request to {}", request.getURI());
        }
        return execution.execute(request, body);
    }
}
