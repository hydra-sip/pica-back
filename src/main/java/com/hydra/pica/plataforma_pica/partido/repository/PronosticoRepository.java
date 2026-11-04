package com.hydra.pica.plataforma_pica.partido.repository;

import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.partido.domain.Pronostico;
import com.hydra.pica.plataforma_pica.partido.domain.PronosticoId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PronosticoRepository extends JpaRepository<Pronostico, PronosticoId> {

    @Query("select p from Pronostico p where p.id.partidoId = :partidoId")
    List<Pronostico> delPartido(@Param("partidoId") Long partidoId);

    @Query("select p from Pronostico p where p.id.usuarioId = :usuarioId")
    List<Pronostico> delUsuario(@Param("usuarioId") Long usuarioId);

    @Query("select p from Pronostico p where p.id.usuarioId = :usuarioId and p.id.partidoId = :partidoId")
    Optional<Pronostico> delUsuarioYPartido(@Param("usuarioId") Long usuarioId, @Param("partidoId") Long partidoId);

    /**
     * Agregación de estadísticas de pronósticos por usuario (HU-307, T307-1),
     * filtrando torneos vigentes (no eliminados y fuera de borrador), ordenados
     * por puntaje acumulado y primer pronóstico registrado (orden de carga).
     * El {@code eliminadoEn is null} del usuario va escrito a propósito: la consulta solo usa
     * {@code id.usuarioId}, y sin el join los usuarios dados de baja seguían ocupando puestos.
     */
    @Query("""
            select new com.hydra.pica.plataforma_pica.partido.dto.EstadisticaPronosticosUsuario(
                p.id.usuarioId,
                coalesce(sum(p.puntos), 0L),
                coalesce(sum(case when p.puntos is not null and p.puntos > 0 then 1L else 0L end), 0L),
                count(p),
                min(p.creadoEn)
            )
            from Pronostico p
              join p.usuario usuario
            where usuario.eliminadoEn is null
              and p.partido.torneo.eliminadoEn is null
              and p.partido.torneo.estado != com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo.BORRADOR
              and (:torneoId is null or p.partido.torneo.id = :torneoId)
            group by p.id.usuarioId
            order by coalesce(sum(p.puntos), 0L) desc, min(p.creadoEn) asc, p.id.usuarioId asc
            """)
    List<com.hydra.pica.plataforma_pica.partido.dto.EstadisticaPronosticosUsuario> rankingEstadisticas(
            @Param("torneoId") Long torneoId);
}

