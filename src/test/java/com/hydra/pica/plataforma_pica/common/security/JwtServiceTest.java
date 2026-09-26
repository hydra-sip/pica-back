package com.hydra.pica.plataforma_pica.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import com.hydra.pica.plataforma_pica.user.dto.JwksResponse;
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

    @Test
    void obtenerClavePublicaPemDevuelvePemValidoYValidaToken() throws Exception {
        String token = jwtService.generarAccessToken(
                1L, "usuario", List.of("ROLE_USER"), List.of());

        String pem = jwtService.obtenerClavePublicaPem();

        assertThat(pem).startsWith("-----BEGIN PUBLIC KEY-----")
                .endsWith("-----END PUBLIC KEY-----\n");

        String normalizedPem = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");

        byte[] decodedKey = Base64.getDecoder().decode(normalizedPem);
        PublicKey publicKeyParsed = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(decodedKey));

        var claims = Jwts.parser()
                .verifyWith(publicKeyParsed)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertThat(claims.getSubject()).isEqualTo("1");
    }

    @Test
    void obtenerJwksDevuelveConjuntoValidoYReconstruyeClavePublica() throws Exception {
        String token = jwtService.generarAccessToken(
                1L, "usuario", List.of("ROLE_USER"), List.of());

        JwksResponse jwks = jwtService.obtenerJwks();

        assertThat(jwks.keys()).hasSize(1);
        var key = jwks.keys().get(0);
        assertThat(key.kty()).isEqualTo("RSA");
        assertThat(key.use()).isEqualTo("sig");
        assertThat(key.alg()).isEqualTo("RS256");
        assertThat(key.n()).isNotBlank();
        assertThat(key.e()).isNotBlank();

        BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(key.n()));
        BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(key.e()));

        PublicKey publicKeyParsed = KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(modulus, exponent));

        var claims = Jwts.parser()
                .verifyWith(publicKeyParsed)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertThat(claims.getSubject()).isEqualTo("1");
    }

    private KeyPair generarKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
