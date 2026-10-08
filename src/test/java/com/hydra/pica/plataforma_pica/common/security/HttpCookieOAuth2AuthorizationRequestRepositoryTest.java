package com.hydra.pica.plataforma_pica.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Base64;
import java.util.HashMap;
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

    @Test
    @DisplayName("Una cookie con una clase que no es del request de OAuth se rechaza sin armarla")
    void cookieConOtraClaseSeRechaza() throws IOException {
        Ajena.armadas = 0;
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(cookieCon(new Ajena()));

        assertThat(repository.loadAuthorizationRequest(request)).isNull();
        assertThat(Ajena.armadas).isZero();
    }

    @Test
    @DisplayName("También se rechaza si la clase ajena viene adentro de una colección permitida")
    void claseAjenaAdentroDeUnMapaSeRechaza() throws IOException {
        Ajena.armadas = 0;
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(cookieCon(new HashMap<>(Map.of("x", new Ajena()))));

        assertThat(repository.loadAuthorizationRequest(request)).isNull();
        assertThat(Ajena.armadas).isZero();
    }

    /** Cuenta cuántas veces se la deserializa: con el filtro tiene que quedar en cero. */
    static class Ajena implements Serializable {
        static int armadas;

        private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
            in.defaultReadObject();
            armadas++;
        }
    }

    private static Cookie cookieCon(Object objeto) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bytes)) {
            oos.writeObject(objeto);
        }
        return new Cookie(HttpCookieOAuth2AuthorizationRequestRepository.OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME,
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray()));
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
