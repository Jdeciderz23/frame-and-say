package com.groupphoto.consensus.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 前后端联通探针。数据库是否就绪由 Actuator readiness 单独报告。 */
@RestController
@RequestMapping("/api/health")
public class HealthController {
    @GetMapping
    public HealthResponse health() {
        return new HealthResponse("UP", "group-photo-consensus");
    }

    public record HealthResponse(String status, String application) {}
}
