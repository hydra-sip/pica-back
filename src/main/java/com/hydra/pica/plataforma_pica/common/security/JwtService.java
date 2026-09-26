package com.hydra.pica.plataforma_pica.common.security;

import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import java.math.BigInteger;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import com.hydra.pica.plataforma_pica.user.dto.JwkKeyDto;
import com.hydra.pica.plataforma_pica.user.dto.JwksResponse;
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

    public String obtenerClavePublicaPem() {
        PublicKey publicKey = keyPair.getPublic();
        String base64Key = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(publicKey.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + base64Key + "\n-----END PUBLIC KEY-----\n";
    }

    public JwksResponse obtenerJwks() {
        RSAPublicKey rsaPublicKey = (RSAPublicKey) keyPair.getPublic();
        String n = encodeBigIntegerBase64Url(rsaPublicKey.getModulus());
        String e = encodeBigIntegerBase64Url(rsaPublicKey.getPublicExponent());

        JwkKeyDto keyDto = new JwkKeyDto("RSA", "sig", "RS256", n, e);
        return new JwksResponse(List.of(keyDto));
    }

    private String encodeBigIntegerBase64Url(BigInteger bigInt) {
        byte[] array = bigInt.toByteArray();
        if (array.length > 0 && array[0] == 0) {
            byte[] tmp = new byte[array.length - 1];
            System.arraycopy(array, 1, tmp, 0, tmp.length);
            array = tmp;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(array);
    }
}
