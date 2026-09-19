package com.hydra.pica.plataforma_pica.health;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador de verificación de salud para la API.
 * Expone un endpoint liviano en /api/v1/health para pruebas de conectividad y monitoreo.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    private final String serviceName;

    public HealthController(@Value("${spring.application.name:plataforma-pica}") String serviceName) {
        this.serviceName = serviceName;
    }

    @GetMapping
    public ResponseEntity<HealthResponse> checkHealth() {
        HealthResponse response = new HealthResponse("UP", serviceName, Instant.now());
        return ResponseEntity.ok(response);
    }

    public record HealthResponse(
            String status,
            String service,
            Instant timestamp
    ) {}
}
