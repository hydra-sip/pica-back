package com.hydra.pica.plataforma_pica.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

/**
 * El handler siempre vuelve al front: con el código para canjear o con el código de error. El flujo
 * contra Spring Security está en {@code OAuthLoginIntegracionTest}.
 */
@ExtendWith(MockitoExtension.class)
class OAuth2LoginHandlerTest {

    private static final String CALLBACK = "http://front.test/oauth/callback";

    @Mock private AuthService authService;
    @Mock private OAuthCodeStore oAuthCodeStore;

    private OAuth2LoginHandler handler;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        handler = new OAuth2LoginHandler(authService, oAuthCodeStore, "http://front.test");
        response = new MockHttpServletResponse();
    }

    @Test
    @DisplayName("Login resuelto: vuelve al front con el código")
    void exitoVuelveConCodigo() throws Exception {
        Usuario usuario = mock(Usuario.class);
        when(usuario.getId()).thenReturn(42L);
        when(authService.procesarLoginGoogle("sub-1", "ana@example.com", true, "Ana", "Pérez")).thenReturn(usuario);
        when(oAuthCodeStore.generarCodigo(42L)).thenReturn("codigo-1");

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, login(Map.of(
                "sub", "sub-1", "email", "ana@example.com", "email_verified", true,
                "given_name", "Ana", "family_name", "Pérez")));

        assertThat(response.getRedirectedUrl()).isEqualTo(CALLBACK + "?code=codigo-1");
    }

    @Test
    @DisplayName("Sin email_verified se toma como no verificado y el error del servicio vuelve al front")
    void errorDelServicioVuelveConSuCodigo() throws Exception {
        when(authService.procesarLoginGoogle("sub-2", "sin@example.com", false, "Sin", "Verificar"))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, CodigoError.EMAIL_NO_VERIFICADO, "no verificado"));

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, login(Map.of(
                "sub", "sub-2", "email", "sin@example.com", "given_name", "Sin", "family_name", "Verificar")));

        assertThat(response.getRedirectedUrl()).isEqualTo(CALLBACK + "?error=EMAIL_NO_VERIFICADO");
        verify(oAuthCodeStore, never()).generarCodigo(any());
    }

    @Test
    @DisplayName("Error inesperado: vuelve al front con ERROR_INTERNO")
    void errorInesperadoVuelveConErrorInterno() throws Exception {
        when(authService.procesarLoginGoogle("sub-3", "ana@example.com", true, "Ana", "Pérez"))
                .thenThrow(new IllegalStateException("se cayó la base"));

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, login(Map.of(
                "sub", "sub-3", "email", "ana@example.com", "email_verified", true,
                "given_name", "Ana", "family_name", "Pérez")));

        assertThat(response.getRedirectedUrl()).isEqualTo(CALLBACK + "?error=ERROR_INTERNO");
    }

    @Test
    @DisplayName("Falla en Google (por ejemplo, cancelado): vuelve al front con NO_AUTENTICADO")
    void fallaEnGoogleVuelveConNoAutenticado() throws Exception {
        handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied")));

        assertThat(response.getRedirectedUrl()).isEqualTo(CALLBACK + "?error=NO_AUTENTICADO");
    }

    private static OAuth2AuthenticationToken login(Map<String, Object> atributos) {
        var principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")), atributos, "sub");
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
    }
}
