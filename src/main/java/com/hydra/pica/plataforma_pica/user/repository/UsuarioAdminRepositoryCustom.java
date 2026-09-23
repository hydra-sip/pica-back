package com.hydra.pica.plataforma_pica.user.repository;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UsuarioAdminRepositoryCustom {

    Page<UsuarioAdminRow> buscarIncluyendoEliminados(
            String q, EstadoUsuario estado, Long rolId, Pageable pageable);

    record UsuarioAdminRow(
            Long id,
            String username,
            String email,
            EstadoUsuario estado,
            Instant eliminadoEn,
            Instant creadoEn,
            Long personaId,
            String nombreCompleto,
            String tipoDoc,
            String nroDoc,
            String rolesJson) {
    }
}
