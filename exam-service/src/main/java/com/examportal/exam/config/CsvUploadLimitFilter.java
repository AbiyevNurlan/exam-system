package com.examportal.exam.config;

import com.examportal.exam.service.GroupImportService;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * CSV Upload Limit Filter
 * Size limit for the group CSV import, and for that route only: every other endpoint keeps the
 * default request limits. The body is counted while it is read, so an upload stops at the limit
 * whatever its Content-Length says, before the multipart parser has buffered or stored the rest.
 * The error is the one the codecs raise themselves (413 PAYLOAD_TOO_LARGE in GlobalExceptionHandler).
 */
@Component
public class CsvUploadLimitFilter implements WebFilter {

    // The file limit plus room for the multipart boundaries and part headers
    static final long MAX_BODY_BYTES = GroupImportService.MAX_FILE_BYTES + 16 * 1024;

    private static final Pattern IMPORT_PATH = Pattern.compile("^/api/admin/groups/[^/]+/members/import/?$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (request.getMethod() != HttpMethod.POST || !IMPORT_PATH.matcher(request.getPath().value()).matches()) {
            return chain.filter(exchange);
        }

        ServerHttpRequest limited = new ServerHttpRequestDecorator(request) {
            @Override
            public Flux<DataBuffer> getBody() {
                AtomicLong received = new AtomicLong();
                return super.getBody().handle((buffer, sink) -> {
                    if (received.addAndGet(buffer.readableByteCount()) > MAX_BODY_BYTES) {
                        DataBufferUtils.release(buffer);
                        sink.error(new DataBufferLimitException("CSV upload exceeds the size limit"));
                    } else {
                        sink.next(buffer);
                    }
                });
            }
        };
        return chain.filter(exchange.mutate().request(limited).build());
    }
}