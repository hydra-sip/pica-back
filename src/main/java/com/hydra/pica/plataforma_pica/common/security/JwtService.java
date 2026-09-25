package com.hydra.pica.plataforma_pica.common.security;

import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private static final Duration ACCESS_TOKEN_DURATION = Duration.ofMinutes(15);

    private final KeyPair keyPair;

    public JwtService(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public String generarAccessToken(
            Long usuarioId,
            String username,
            List<String> roles,
            List<String> permisos) {
        Instant ahora = Instant.now();

        return Jwts.builder()
                .subject(String.valueOf(usuarioId))
                .claim("username", username)
                .claim("roles", roles)
                .claim("permisos", permisos)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(ahora))
                .expiration(Date.from(ahora.plus(ACCESS_TOKEN_DURATION)))
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    public Jws<Claims> validar(String token) {
        return Jwts.parser()
                .verifyWith(keyPair.getPublic())
                .build()
                .parseSignedClaims(token);
    }
}
