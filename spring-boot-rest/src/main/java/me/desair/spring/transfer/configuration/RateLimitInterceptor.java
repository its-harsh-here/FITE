package me.desair.spring.transfer.configuration;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final Map<String, Bucket> createBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> lookupBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> pollingBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> uploadBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> downloadBuckets = new ConcurrentHashMap<>();

    // Strict limit: 10 transfer creations per minute per IP
    private Bucket getCreateBucket(String ip) {
        return createBuckets.computeIfAbsent(ip, k -> Bucket.builder()
                .addLimit(Bandwidth.classic(10, Refill.greedy(10, Duration.ofMinutes(1))))
                .build());
    }

    // Strict limit: 60 metadata lookups / completion calls per minute per IP
    private Bucket getLookupBucket(String ip) {
        return lookupBuckets.computeIfAbsent(ip, k -> Bucket.builder()
                .addLimit(Bandwidth.classic(60, Refill.greedy(60, Duration.ofMinutes(1))))
                .build());
    }

    // Moderate limit: 120 polling requests per minute per IP (allows ~2 second polling cadence)
    private Bucket getPollingBucket(String ip) {
        return pollingBuckets.computeIfAbsent(ip, k -> Bucket.builder()
                .addLimit(Bandwidth.classic(120, Refill.greedy(120, Duration.ofMinutes(1))))
                .build());
    }

    // High limit: 300 chunk uploads per minute per IP with greedy refill (supports concurrent 8MB chunk streams while preventing abuse)
    private Bucket getUploadBucket(String ip) {
        return uploadBuckets.computeIfAbsent(ip, k -> Bucket.builder()
                .addLimit(Bandwidth.classic(300, Refill.greedy(300, Duration.ofMinutes(1))))
                .build());
    }

    // High limit: 300 chunk downloads per minute per IP with greedy refill (supports concurrent receiver chunk streams)
    private Bucket getDownloadBucket(String ip) {
        return downloadBuckets.computeIfAbsent(ip, k -> Bucket.builder()
                .addLimit(Bandwidth.classic(300, Refill.greedy(300, Duration.ofMinutes(1))))
                .build());
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        if (path.startsWith("/health")) {
            return true;
        }

        String ip = getClientIP(request);
        String method = request.getMethod();

        Bucket bucket;

        if (path.matches("^/api/transfers$") && "POST".equalsIgnoreCase(method)) {
            bucket = getCreateBucket(ip);
        } else if ((path.matches("^/api/transfers/[^/]+$") || path.matches("^/api/transfers/code/[^/]+$")) && "GET".equalsIgnoreCase(method)) {
            bucket = getLookupBucket(ip);
        } else if (path.matches("^/api/transfers/[^/]+/chunks$") && "GET".equalsIgnoreCase(method)) {
            // Polling
            bucket = getPollingBucket(ip);
        } else if (path.matches("^/api/transfers/[^/]+/chunks/\\d+$") && "PUT".equalsIgnoreCase(method)) {
            // Chunk upload
            bucket = getUploadBucket(ip);
        } else if (path.matches("^/api/transfers/[^/]+/chunks/\\d+$") && "GET".equalsIgnoreCase(method)) {
            // Chunk download
            bucket = getDownloadBucket(ip);
        } else {
            // Fallback for completion or other metadata endpoints
            bucket = getLookupBucket(ip);
        }

        if (bucket.tryConsume(1)) {
            return true;
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"RATE_LIMITED\",\"message\":\"Too many requests. Please slow down.\"}");
            return false;
        }
    }

    private String getClientIP(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null || xfHeader.isBlank()) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0].trim();
    }
}
