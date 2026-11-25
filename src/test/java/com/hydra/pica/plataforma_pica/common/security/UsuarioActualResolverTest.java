package com.hydra.pica.plataforma_pica.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

class UsuarioActualResolverTest {

    @SuppressWarnings("unused")
    static class Endpoints {
        void requerido(@UsuarioActual Long usuarioId) { }

        void opcional(@UsuarioActual(requerido = false) Long usuarioId) { }

        void sinAnotacion(Long usuarioId) { }
    }

    private static UsuarioActualResolver resolverCon(Optional<Long> usuarioId) {
        CurrentUserProvider proveedor = new CurrentUserProvider() {
            @Override
            public Optional<Long> getCurrentUserId() {
                return usuarioId;
            }

            @Override
            public Set<String> getPermisos() {
                return Set.of();
            }
        };
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("currentUserProvider", proveedor);
        return new UsuarioActualResolver(beans.getBeanProvider(CurrentUserProvider.class));
    }

    private static MethodParameter parametro(String metodo) throws NoSuchMethodException {
        Method m = Endpoints.class.getDeclaredMethod(metodo, Long.class);
        return new MethodParameter(m, 0);
    }

    @Test
    @DisplayName("Con sesión, el parámetro recibe el id del usuario")
    void conSesionDaElId() throws Exception {
        assertThat(resolverCon(Optional.of(7L)).resolveArgument(parametro("requerido"), null, null, null))
                .isEqualTo(7L);
    }

    @Test
    @DisplayName("Sin sesión, un parámetro requerido da 401")
    void sinSesionRequeridoFalla() throws Exception {
        MethodParameter requerido = parametro("requerido");
        UsuarioActualResolver resolver = resolverCon(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveArgument(requerido, null, null, null))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class)
                .hasMessage("Sin usuario en la sesión");
    }

    @Test
    @DisplayName("Sin sesión, un parámetro opcional queda en null")
    void sinSesionOpcionalEsNull() throws Exception {
        assertThat(resolverCon(Optional.empty()).resolveArgument(parametro("opcional"), null, null, null)).isNull();
    }

    @Test
    @DisplayName("Solo resuelve los Long anotados")
    void soloLosAnotados() throws Exception {
        UsuarioActualResolver resolver = resolverCon(Optional.of(7L));

        assertThat(resolver.supportsParameter(parametro("requerido"))).isTrue();
        assertThat(resolver.supportsParameter(parametro("sinAnotacion"))).isFalse();
    }
}
