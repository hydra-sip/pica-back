package com.hydra.pica.plataforma_pica.common.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class OAuthCodeStore {

    private static final Duration CODE_TTL = Duration.ofSeconds(60);

    private record CodeEntry(Long usuarioId, Instant expiresAt) {}

    private final Map<String, CodeEntry> codes = new ConcurrentHashMap<>();

    public String generarCodigo(Long usuarioId) {
        String code = UUID.randomUUID().toString();
        codes.put(code, new CodeEntry(usuarioId, Instant.now().plus(CODE_TTL)));
        limpiarCodigosExpirados();
        return code;
    }

    public Long consumirCodigo(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        CodeEntry entry = codes.remove(code);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            return null;
        }
        return entry.usuarioId();
    }

    private void limpiarCodigosExpirados() {
        Instant ahora = Instant.now();
        codes.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(ahora));
    }
}
