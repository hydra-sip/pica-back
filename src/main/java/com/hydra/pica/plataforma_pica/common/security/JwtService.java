package com.hydra.pica.plataforma_pica.common.security;

import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import com.hydra.pica.plataforma_pica.common.config.SesionConfig.SesionProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    public static final String CLAIM_VERSION_SESION = "versionSesion";

    private final KeyPair keyPair;
    private final JwkKeyDto jwk;
    private final Duration accessTtl;

    public JwtService(KeyPair keyPair, SesionProperties sesion) {
        this.keyPair = keyPair;
        this.jwk = armarJwk((RSAPublicKey) keyPair.getPublic());
        this.accessTtl = sesion.accessTtl();
    }

    public String generarAccessToken(
            Long usuarioId,
            String username,
            List<String> roles,
            List<String> permisos,
            int versionSesion) {
        Instant ahora = Instant.now();

        return Jwts.builder()
                .header().keyId(jwk.kid()).and()
                .subject(String.valueOf(usuarioId))
                .claim("username", username)
                .claim("roles", roles)
                .claim("permisos", permisos)
                .claim(CLAIM_VERSION_SESION, versionSesion)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(ahora))
                .expiration(Date.from(ahora.plus(accessTtl)))
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    public Jws<Claims> validar(String token) {
        return Jwts.parser()
                .verifyWith(keyPair.getPublic())
                .build()
                .parseSignedClaims(token);
    }

    public String obtenerClavePublicaPem() {
        PublicKey publicKey = keyPair.getPublic();
        String base64Key = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(publicKey.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + base64Key + "\n-----END PUBLIC KEY-----\n";
    }

    public JwksResponse obtenerJwks() {
        return new JwksResponse(List.of(jwk));
    }

    // El kid es el thumbprint RFC 7638 de la clave: cambia solo si cambia la clave. Va también en el header
    // de cada token, porque hay verificadores (PyJWKClient, por ejemplo) que buscan la clave del JWKS por kid.
    private static JwkKeyDto armarJwk(RSAPublicKey publicKey) {
        String n = encodeBigIntegerBase64Url(publicKey.getModulus());
        String e = encodeBigIntegerBase64Url(publicKey.getPublicExponent());
        String miembrosRequeridos = "{\"e\":\"" + e + "\",\"kty\":\"RSA\",\"n\":\"" + n + "\"}";
        String kid = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(miembrosRequeridos));
        return new JwkKeyDto("RSA", "sig", "RS256", kid, n, e);
    }

    private static byte[] sha256(String valor) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(valor.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }

    private static String encodeBigIntegerBase64Url(BigInteger bigInt) {
        byte[] array = bigInt.toByteArray();
        if (array.length > 0 && array[0] == 0) {
            byte[] tmp = new byte[array.length - 1];
            System.arraycopy(array, 1, tmp, 0, tmp.length);
            array = tmp;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(array);
    }
}
