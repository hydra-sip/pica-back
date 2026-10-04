package com.hydra.pica.plataforma_pica.common.security;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

/**
 * Guarda el {@link OAuth2AuthorizationRequest} (incluyendo el state generado para Google) en una cookie
 * HttpOnly enviada al navegador del usuario (CE2-9), en lugar de la {@code HttpSession} en memoria.
 *
 * Permite que cualquier réplica del backend inicie la redirección a Google y cualquier otra réplica
 * procese el callback con el state correspondiente, manteniendo la sesión del servidor en STATELESS.
 */
@Component
public class HttpCookieOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpCookieOAuth2AuthorizationRequestRepository.class);

    public static final String OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME = "oauth2_auth_request";
    private static final Duration COOKIE_EXPIRE_DURATION = Duration.ofMinutes(3);

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return obtenerCookie(request)
                .map(Cookie::getValue)
                .map(this::deserializar)
                .orElse(null);
    }

    @Override
    public void saveAuthorizationRequest(
            OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request,
            HttpServletResponse response) {

        if (authorizationRequest == null) {
            eliminarCookie(request, response);
            return;
        }

        String valor = serializar(authorizationRequest);
        ResponseCookie cookie = ResponseCookie.from(OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME, valor)
                .path("/")
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .maxAge(COOKIE_EXPIRE_DURATION)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(
            HttpServletRequest request,
            HttpServletResponse response) {
        OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
        if (authorizationRequest != null) {
            eliminarCookie(request, response);
        }
        return authorizationRequest;
    }

    private void eliminarCookie(HttpServletRequest request, HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME, "")
                .path("/")
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private Optional<Cookie> obtenerCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(c -> OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME.equals(c.getName()))
                .findFirst();
    }

    private String serializar(OAuth2AuthorizationRequest authorizationRequest) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(authorizationRequest);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            throw new IllegalArgumentException("No se pudo serializar el OAuth2AuthorizationRequest", e);
        }
    }

    private OAuth2AuthorizationRequest deserializar(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(Base64.getUrlDecoder().decode(base64));
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (OAuth2AuthorizationRequest) ois.readObject();
        } catch (Exception e) {
            LOGGER.warn("No se pudo deserializar el OAuth2AuthorizationRequest desde la cookie: {}", e.getMessage());
            return null;
        }
    }
}
