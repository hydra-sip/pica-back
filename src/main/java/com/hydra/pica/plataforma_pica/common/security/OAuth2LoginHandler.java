package com.hydra.pica.plataforma_pica.common.security;

import java.io.IOException;

import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.service.AuthService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Cierra el login con Google volviendo siempre al front, a {@code {APP_WEB_URL}/oauth/callback}: con
 * {@code ?code=} para canjear en POST /auth/exchange, o con {@code ?error=} y el código de error si
 * algo falló (cancelado en Google, mail sin verificar, otra cuenta de Google ya vinculada...).
 */
@Component
public class OAuth2LoginHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(OAuth2LoginHandler.class);

    private final AuthService authService;
    private final OAuthCodeStore oAuthCodeStore;
    private final String appWebUrl;

    public OAuth2LoginHandler(
            AuthService authService,
            OAuthCodeStore oAuthCodeStore,
            @Value("${app.web-url:http://localhost:5173}") String appWebUrl) {
        this.authService = authService;
        this.oAuthCodeStore = oAuthCodeStore;
        this.appWebUrl = appWebUrl;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException {

        OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();

        String googleSub = oauth2User.getAttribute("sub");
        if (googleSub == null) {
            googleSub = oauth2User.getName();
        }
        String email = oauth2User.getAttribute("email");
        boolean emailVerificado = Boolean.TRUE.equals(oauth2User.getAttribute("email_verified"));
        String givenName = oauth2User.getAttribute("given_name");
        String familyName = oauth2User.getAttribute("family_name");
        String name = oauth2User.getAttribute("name");

        String nombres = givenName;
        String apellidos = familyName;

        if (nombres == null || nombres.isBlank()) {
            if (name != null && !name.isBlank()) {
                String[] partes = name.trim().split("\\s+", 2);
                nombres = partes[0];
                apellidos = partes.length > 1 ? partes[1] : "";
            } else {
                nombres = "Usuario";
                apellidos = "Google";
            }
        }
        if (apellidos == null) {
            apellidos = "";
        }

        String destino;
        try {
            Usuario usuario = authService.procesarLoginGoogle(googleSub, email, emailVerificado, nombres, apellidos);
            destino = callback("code", oAuthCodeStore.generarCodigo(usuario.getId()));
        } catch (ApiException e) {
            destino = callback("error", e.getCodigo().name());
        } catch (RuntimeException e) {
            LOGGER.error("Falló el login con Google", e);
            destino = callback("error", CodigoError.ERROR_INTERNO.name());
        }
        response.sendRedirect(destino);
    }

    // Cancelado en Google o respuesta inválida del proveedor: sin esto Spring redirige a /login?error del back
    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        response.sendRedirect(callback("error", CodigoError.NO_AUTENTICADO.name()));
    }

    private String callback(String parametro, String valor) {
        return UriComponentsBuilder.fromUriString(appWebUrl)
                .path("/oauth/callback")
                .queryParam(parametro, valor)
                .build()
                .toUriString();
    }
}
