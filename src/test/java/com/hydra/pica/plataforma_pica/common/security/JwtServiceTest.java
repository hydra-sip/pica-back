package com.hydra.pica.plataforma_pica.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private KeyPair keyPair;
    private JwtService jwtService;

    @BeforeEach
    void setUp() throws Exception {
        keyPair = generarKeyPair();
        jwtService = new JwtService(keyPair);
    }

    @Test
    void generaTokenConLasClaimsEsperadasYLoValida() {
        String token = jwtService.generarAccessToken(
                42L,
                "jperez",
                List.of("ADMIN", "PARTICIPANTE"),
                List.of("USUARIO_LEER", "USUARIO_EDITAR"));

        var claims = jwtService.validar(token).getPayload();

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("username", String.class)).isEqualTo("jperez");
        assertThat(claims.get("roles", List.class)).containsExactly("ADMIN", "PARTICIPANTE");
        assertThat(claims.get("permisos", List.class)).containsExactly("USUARIO_LEER", "USUARIO_EDITAR");
        assertThat(claims.getId()).isNotBlank();
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime())
                .isEqualTo(15 * 60 * 1000L);
    }

    @Test
    void tokenVencidoLanzaExpiredJwtException() {
        String token = Jwts.builder()
                .subject("42")
                .issuedAt(Date.from(Instant.now().minusSeconds(120)))
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(keyPair.getPrivate())
                .compact();

        assertThatThrownBy(() -> jwtService.validar(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void tokenFirmadoConOtraClaveFallaLaValidacion() throws Exception {
        KeyPair otraClave = generarKeyPair();
        String token = Jwts.builder()
                .subject("42")
                .signWith(otraClave.getPrivate())
                .compact();

        assertThatThrownBy(() -> jwtService.validar(token))
                .isInstanceOf(SignatureException.class);
    }

    private KeyPair generarKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
