package com.hydra.pica.plataforma_pica.user.repository;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PersonaAdminRepositoryCustom {

    /**
     * Listado del ABM de personas. Es SQL nativo para poder incluir a las eliminadas (el
     * {@code @SQLRestriction} de la entidad no aplica) y traer {@code tieneUsuario} en la misma
     * consulta, sin una por fila.
     */
    Page<PersonaAdminRow> buscar(String q, EstadoGeneral estado, boolean incluirEliminados, Pageable pageable);

    record PersonaAdminRow(
            Long id,
            String nombres,
            String apellidos,
            String tipoDoc,
            String nroDoc,
            EstadoGeneral estado,
            Instant eliminadoEn,
            boolean tieneUsuario) {
    }
}
