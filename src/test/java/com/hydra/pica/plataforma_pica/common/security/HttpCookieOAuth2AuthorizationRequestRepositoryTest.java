package com.hydra.pica.plataforma_pica.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

class HttpCookieOAuth2AuthorizationRequestRepositoryTest {

    private HttpCookieOAuth2AuthorizationRequestRepository repository;

    @BeforeEach
    void setUp() {
        repository = new HttpCookieOAuth2AuthorizationRequestRepository();
    }

    @Test
    @DisplayName("Guarda el authorization request en una cookie HttpOnly y SameSite=Lax")
    void guardarAuthorizationRequestEnCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        OAuth2AuthorizationRequest authRequest = crearAuthorizationRequest();

        repository.saveAuthorizationRequest(authRequest, request, response);

        String setCookieHeader = response.getHeader("Set-Cookie");
        assertThat(setCookieHeader).isNotNull();
        assertThat(setCookieHeader).contains(HttpCookieOAuth2AuthorizationRequestRepository.OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME);
        assertThat(setCookieHeader).contains("HttpOnly");
        assertThat(setCookieHeader).contains("SameSite=Lax");
        assertThat(setCookieHeader).contains("Max-Age=180");
    }

    @Test
    @DisplayName("Carga correctamente el authorization request desde la cookie")
    void cargarAuthorizationRequestDesdeCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        OAuth2AuthorizationRequest original = crearAuthorizationRequest();
        repository.saveAuthorizationRequest(original, request, response);

        Cookie cookie = response.getCookie(HttpCookieOAuth2AuthorizationRequestRepository.OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME);
        assertThat(cookie).isNotNull();

        request.setCookies(cookie);

        OAuth2AuthorizationRequest cargado = repository.loadAuthorizationRequest(request);
        assertThat(cargado).isNotNull();
        assertThat(cargado.getState()).isEqualTo(original.getState());
        assertThat(cargado.getClientId()).isEqualTo(original.getClientId());
        assertThat(cargado.getAuthorizationUri()).isEqualTo(original.getAuthorizationUri());
        assertThat(cargado.getRedirectUri()).isEqualTo(original.getRedirectUri());
    }

    @Test
    @DisplayName("Remueve el authorization request devolviendo el objeto y limpiando la cookie")
    void removerAuthorizationRequestLimpiaCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        OAuth2AuthorizationRequest original = crearAuthorizationRequest();
        repository.saveAuthorizationRequest(original, request, response);

        Cookie cookie = response.getCookie(HttpCookieOAuth2AuthorizationRequestRepository.OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME);
        request.setCookies(cookie);

        MockHttpServletResponse removeResponse = new MockHttpServletResponse();
        OAuth2AuthorizationRequest removido = repository.removeAuthorizationRequest(request, removeResponse);

        assertThat(removido).isNotNull();
        assertThat(removido.getState()).isEqualTo(original.getState());

        String removeCookieHeader = removeResponse.getHeader("Set-Cookie");
        assertThat(removeCookieHeader).isNotNull();
        assertThat(removeCookieHeader).contains("Max-Age=0");
    }

    @Test
    @DisplayName("Cargar sin cookie devuelve null")
    void cargarSinCookieDevuelveNull() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThat(repository.loadAuthorizationRequest(request)).isNull();
    }

    private OAuth2AuthorizationRequest crearAuthorizationRequest() {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("test-client-id")
                .redirectUri("http://localhost:8080/login/oauth2/code/google")
                .scopes(Set.of("openid", "profile", "email"))
                .state("state-secreto-12345")
                .additionalParameters(Map.of("prompt", "select_account"))
                .build();
    }
}
