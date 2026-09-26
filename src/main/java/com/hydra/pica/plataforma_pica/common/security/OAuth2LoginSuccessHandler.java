package com.hydra.pica.plataforma_pica.common.security;

import java.io.IOException;

import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.service.AuthService;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final AuthService authService;
    private final OAuthCodeStore oAuthCodeStore;
    private final String appWebUrl;

    public OAuth2LoginSuccessHandler(
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
            Authentication authentication) throws IOException, ServletException {

        OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();

        String googleSub = oauth2User.getAttribute("sub");
        if (googleSub == null) {
            googleSub = oauth2User.getName();
        }
        String email = oauth2User.getAttribute("email");
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

        Usuario usuario = authService.procesarLoginGoogle(googleSub, email, nombres, apellidos);
        String code = oAuthCodeStore.generarCodigo(usuario.getId());

        String targetUrl = UriComponentsBuilder.fromUriString(appWebUrl)
                .path("/oauth/callback")
                .queryParam("code", code)
                .build()
                .toUriString();

        response.sendRedirect(targetUrl);
    }
}
