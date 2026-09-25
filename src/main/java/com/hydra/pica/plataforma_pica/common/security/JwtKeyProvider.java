package com.hydra.pica.plataforma_pica.common.security;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class JwtKeyProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(JwtKeyProvider.class);
    private static final String PRIVATE_KEY_VARIABLE = "JWT_PRIVATE_KEY";
    private static final String PUBLIC_KEY_VARIABLE = "JWT_PUBLIC_KEY";
    private static final String RSA_ALGORITHM = "RSA";

    private final Environment environment;

    public JwtKeyProvider(Environment environment) {
        this.environment = environment;
    }

    @Bean
    public KeyPair jwtKeyPair() {
        String privateKeyPem = System.getenv(PRIVATE_KEY_VARIABLE);
        String publicKeyPem = System.getenv(PUBLIC_KEY_VARIABLE);
        boolean privateKeyPresent = hasValue(privateKeyPem);
        boolean publicKeyPresent = hasValue(publicKeyPem);

        if (privateKeyPresent && publicKeyPresent) {
            return parseKeyPair(privateKeyPem, publicKeyPem);
        }

        if (isDevProfile()) {
            LOGGER.warn(
                    "No se configuraron ambas claves JWT; se generaron claves RSA efímeras para dev. "
                            + "No son válidas fuera de este proceso.");
            return generateKeyPair();
        }

        StringBuilder missingVariables = new StringBuilder();
        if (!privateKeyPresent) {
            missingVariables.append(PRIVATE_KEY_VARIABLE);
        }
        if (!publicKeyPresent) {
            if (missingVariables.length() > 0) {
                missingVariables.append(" y ");
            }
            missingVariables.append(PUBLIC_KEY_VARIABLE);
        }
        throw new IllegalStateException(
                "Falta(n) la(s) variable(s) de entorno " + missingVariables
                        + " para las claves JWT. La aplicación no puede iniciar fuera del perfil dev.");
    }

    private KeyPair parseKeyPair(String privateKeyPem, String publicKeyPem) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(RSA_ALGORITHM);
            PrivateKey privateKey = keyFactory.generatePrivate(
                    new PKCS8EncodedKeySpec(decodePem(privateKeyPem, "PRIVATE KEY")));
            PublicKey publicKey = keyFactory.generatePublic(
                    new X509EncodedKeySpec(decodePem(publicKeyPem, "PUBLIC KEY")));
            return new KeyPair(publicKey, privateKey);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Las variables JWT_PRIVATE_KEY y JWT_PUBLIC_KEY no contienen claves RSA PEM válidas "
                            + "(PKCS#8/X.509).",
                    exception);
        }
    }

    private byte[] decodePem(String pem, String type) {
        String normalizedPem = pem
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(normalizedPem);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("El contenido PEM de la clave " + type + " no es Base64 válido.",
                    exception);
        }
    }

    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(RSA_ALGORITHM);
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo generar la clave RSA efímera para dev.", exception);
        }
    }

    private boolean isDevProfile() {
        for (String activeProfile : environment.getActiveProfiles()) {
            if ("dev".equals(activeProfile)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }
}
