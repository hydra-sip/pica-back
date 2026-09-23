package com.hydra.pica.plataforma_pica.common.dto;

import org.springframework.data.domain.Page;

public record PageInfo(int size, int number, long totalElements, int totalPages) {

    public static PageInfo desde(Page<?> pagina) {
        return new PageInfo(pagina.getSize(), pagina.getNumber(), pagina.getTotalElements(), pagina.getTotalPages());
    }
}