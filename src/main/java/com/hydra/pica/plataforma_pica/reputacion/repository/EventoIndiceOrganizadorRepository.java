package com.hydra.pica.plataforma_pica.reputacion.repository;

import java.util.List;
import com.hydra.pica.plataforma_pica.reputacion.domain.EventoIndiceOrganizador;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventoIndiceOrganizadorRepository extends JpaRepository<EventoIndiceOrganizador, Long> {

    boolean existsByTorneoId(Long torneoId);

    @Query("""
            select e from EventoIndiceOrganizador e join fetch e.torneo
            where e.usuarioId = :usuarioId order by e.creadoEn desc, e.id desc""")
    List<EventoIndiceOrganizador> delUsuario(@Param("usuarioId") Long usuarioId);
}
