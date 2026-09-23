package com.hydra.pica.plataforma_pica.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Red de seguridad para PICA-124: cada endpoint de /api/v1/admin/** tiene que declarar su permiso
 * con @PreAuthorize, como dice x-permiso en docs/api/openapi.yaml. @EnableMethodSecurity habilita
 * el mecanismo pero no obliga a nadie a usarlo, así que sin esto alcanza con que alguien se olvide
 * de la anotación para que el endpoint quede abierto a cualquier usuario autenticado.
 *
 * Los controllers de admin los van escribiendo las subtareas 114, 115, 116, 123, 125, 126 y 127;
 * el primero fue el de roles (125). Falla con el primero que llegue sin anotar.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class EndpointsAdminProtegidosTest {

    private static final String PREFIJO_ADMIN = "/api/v1/admin";

    // el qualifier hace falta porque actuator registra su propio handler mapping
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("Todo endpoint de /api/v1/admin/** declara su permiso con @PreAuthorize")
    void todoEndpointAdminDeclaraSuPermiso() {
        List<String> sinPermiso = new ArrayList<>();

        handlerMapping.getHandlerMethods().forEach((info, metodo) -> {
            if (rutas(info).stream().noneMatch(ruta -> ruta.startsWith(PREFIJO_ADMIN))) {
                return;
            }
            boolean anotado = AnnotatedElementUtils.hasAnnotation(metodo.getMethod(), PreAuthorize.class)
                    || AnnotatedElementUtils.hasAnnotation(metodo.getBeanType(), PreAuthorize.class);
            if (!anotado) {
                sinPermiso.add(rutas(info) + " -> " + metodo.getBeanType().getSimpleName()
                        + "." + metodo.getMethod().getName());
            }
        });

        assertThat(sinPermiso)
                .describedAs("Endpoints de admin sin @PreAuthorize; el permiso está en x-permiso del contrato")
                .isEmpty();
    }

    private static Set<String> rutas(RequestMappingInfo info) {
        return info.getPathPatternsCondition() != null
                ? info.getPathPatternsCondition().getPatternValues()
                : info.getPatternsCondition().getPatterns();
    }
}
