package com.hydra.pica.plataforma_pica.common.security;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Resuelve los parámetros {@link UsuarioActual} con {@link CurrentUserProvider}.
 * El proveedor se busca en cada request: así los tests de un controller que no lo usa no necesitan mockearlo.
 */
@Component
public class UsuarioActualResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    private final ObjectProvider<CurrentUserProvider> currentUserProvider;

    public UsuarioActualResolver(ObjectProvider<CurrentUserProvider> currentUserProvider) {
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(UsuarioActual.class) && parameter.getParameterType() == Long.class;
    }

    @Override
    public Long resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        var usuarioId = currentUserProvider.getObject().getCurrentUserId();
        if (!parameter.getParameterAnnotation(UsuarioActual.class).requerido()) {
            return usuarioId.orElse(null);
        }
        return usuarioId.orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }
}
