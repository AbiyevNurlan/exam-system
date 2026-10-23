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
 * Size limit for the group CSV import and the Word file of the exam creator, for those routes only: every other endpoint keeps the
 * default request limits. The body is counted while it is read, so an upload stops at the limit
 * whatever its Content-Length says, before the multipart parser has buffered or stored the rest.
 * The error is the one the codecs raise themselves (413 PAYLOAD_TOO_LARGE in GlobalExceptionHandler).
 */
@Component
public class CsvUploadLimitFilter implements WebFilter {

    // The file limit plus room for the multipart boundaries and part headers
    static final long MAX_BODY_BYTES = GroupImportService.MAX_FILE_BYTES + 16 * 1024;

    private static final Pattern IMPORT_PATH = Pattern.compile("^/api/admin/groups/[^/]+/members/import/?$");
    // The Word file of questions for the exam creator, with its own limit
    private static final Pattern DOCX_PATH = Pattern.compile("^/api/exams/import/docx/?$");
    static final long MAX_DOCX_BODY_BYTES = com.examportal.exam.importer.ExamImportService.MAX_FILE_BYTES + 16 * 1024;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        boolean csv = IMPORT_PATH.matcher(path).matches();
        if (request.getMethod() != HttpMethod.POST || !(csv || DOCX_PATH.matcher(path).matches())) {
            return chain.filter(exchange);
        }
        long limit = csv ? MAX_BODY_BYTES : MAX_DOCX_BODY_BYTES;

        ServerHttpRequest limited = new ServerHttpRequestDecorator(request) {
            @Override
            public Flux<DataBuffer> getBody() {
                AtomicLong received = new AtomicLong();
                return super.getBody().handle((buffer, sink) -> {
                    if (received.addAndGet(buffer.readableByteCount()) > limit) {
                        DataBufferUtils.release(buffer);
                        sink.error(new DataBufferLimitException("Upload exceeds the size limit"));
                    } else {
                        sink.next(buffer);
                    }
                });
            }
        };
        return chain.filter(exchange.mutate().request(limited).build());
    }
}