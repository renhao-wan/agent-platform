package io.github.renhaowan.platform.web;

import java.time.OffsetDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PingController {

    public record PingResponse(String service, String status, OffsetDateTime time) {
    }

    @GetMapping("/ping")
    public PingResponse ping() {
        return new PingResponse("agent-platform", "ok", OffsetDateTime.now());
    }
}
