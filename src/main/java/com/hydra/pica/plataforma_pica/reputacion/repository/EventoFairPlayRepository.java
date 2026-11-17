package com.hydra.pica.plataforma_pica.reputacion.repository;

import java.util.List;
import com.hydra.pica.plataforma_pica.reputacion.domain.EventoFairPlay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventoFairPlayRepository extends JpaRepository<EventoFairPlay, Long> {

    @Query("""
            select e from EventoFairPlay e join fetch e.torneo left join fetch e.sancion
            where e.usuarioId = :usuarioId order by e.creadoEn desc, e.id desc""")
    List<EventoFairPlay> delUsuario(@Param("usuarioId") Long usuarioId);

    boolean existsBySancionId(Long sancionId);
}
