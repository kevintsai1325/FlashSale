package com.flashsale.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Set;

/**
 * Lets the local demo-data cleanup script (running as {@code compose exec backend wget
 * http://localhost:8080/...}) coordinate a barrier around {@link ApiAuditWriter}'s async
 * persistence so cleanup can prove no suppressed row lands after it deletes rows. This is not
 * exposed through nginx or a published Docker port, but Spring Security still has to
 * {@code permitAll} it — the script never carries a JWT — so the real access-control boundary is
 * enforced here: only a loopback caller (the container talking to itself) may reach it. Any other
 * source address gets 404, not 403, so the endpoint's existence isn't disclosed to it either.
 */
@RestController
@RequestMapping("/internal/demo-data/audit-barrier")
class DemoDataAuditBarrierController {
    private final ApiAuditWriter writer;

    DemoDataAuditBarrierController(ApiAuditWriter writer) { this.writer = writer; }

    @PostMapping("/begin")
    ResponseEntity<Void> begin(HttpServletRequest httpRequest, @RequestBody BarrierRequest request) {
        if (!isLoopback(httpRequest)) return ResponseEntity.notFound().build();
        boolean drained = writer.beginDemoCleanup(request.userIds(), request.traceIds(), Duration.ofSeconds(10));
        return drained ? ResponseEntity.noContent().build() : ResponseEntity.status(503).build();
    }

    @PostMapping("/end")
    ResponseEntity<Void> end(HttpServletRequest httpRequest) {
        if (!isLoopback(httpRequest)) return ResponseEntity.notFound().build();
        writer.endDemoCleanup();
        return ResponseEntity.noContent().build();
    }

    private static boolean isLoopback(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        return "127.0.0.1".equals(remoteAddr) || "::1".equals(remoteAddr) || "0:0:0:0:0:0:0:1".equals(remoteAddr);
    }

    record BarrierRequest(Set<Long> userIds, Set<String> traceIds) {}
}
