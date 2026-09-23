package com.hydra.pica.plataforma_pica.common.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/** Forma común de todas las páginas de la API (spring.data.web.pageable.serialization-mode=via_dto). */
public record PaginaResponse<T>(List<T> content, PageInfo page) {

    public static <T> PaginaResponse<T> desde(Page<T> pagina) {
        return new PaginaResponse<>(pagina.getContent(), PageInfo.desde(pagina));
    }
}