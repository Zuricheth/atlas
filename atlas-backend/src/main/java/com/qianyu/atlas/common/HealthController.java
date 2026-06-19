package com.qianyu.atlas.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {
    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.ok(Map.of("status", "ok"));
    }

    @GetMapping("/ready")
    public ApiResponse<Map<String, String>> ready() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(1);
            return ApiResponse.ok(Map.of(
                    "status", valid ? "ok" : "degraded",
                    "database", valid ? "ok" : "unavailable"
            ));
        }
    }
}
