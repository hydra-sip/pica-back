package com.hydra.pica.plataforma_pica.reputacion.repository;

import java.util.List;
import java.util.Optional;
import com.hydra.pica.plataforma_pica.reputacion.domain.Sancion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SancionRepository extends JpaRepository<Sancion, Long> {

    @Query("""
            select s from Sancion s join fetch s.torneo join fetch s.usuario u join fetch u.persona
            join fetch s.aplicadaPor a join fetch a.persona left join fetch s.partido
            where s.torneo.id = :torneoId order by s.aplicadaEn desc, s.id desc""")
    List<Sancion> delTorneo(@Param("torneoId") Long torneoId);

    @Query("""
            select s from Sancion s join fetch s.torneo join fetch s.usuario u join fetch u.persona
            join fetch s.aplicadaPor a join fetch a.persona left join fetch s.partido
            where s.usuario.id = :usuarioId order by s.aplicadaEn desc, s.id desc""")
    List<Sancion> delJugador(@Param("usuarioId") Long usuarioId);

    @Query("""
            select s from Sancion s join fetch s.torneo join fetch s.usuario u join fetch u.persona
            join fetch s.aplicadaPor a join fetch a.persona left join fetch s.partido
            where s.id = :id""")
    Optional<Sancion> conTodo(@Param("id") Long id);

    /**
     * La inscripción confirmada del torneo en la que juega el usuario: la suya, si es individual, o la de un equipo
     * en cuya nómina figura.
     */
    @Query(value = """
            SELECT i.id FROM inscripcion i
            WHERE i.torneo_id = :torneoId AND i.estado = 'CONFIRMADA'
              AND (i.usuario_id = :usuarioId
                   OR EXISTS (SELECT 1 FROM nomina n WHERE n.inscripcion_id = i.id AND n.usuario_id = :usuarioId))
            ORDER BY i.id LIMIT 1""", nativeQuery = true)
    Optional<Long> inscripcionDelJugador(@Param("torneoId") Long torneoId, @Param("usuarioId") Long usuarioId);

    /** Una fila de {@link #sancionables}. */
    interface FilaSancionable {
        Long getInscripcionId();

        String getEquipo();

        Long getUsuarioId();
    }

    /**
     * Los jugadores de las inscripciones confirmadas del torneo: el inscripto, si es individual, o los de la nómina, si
     * es de equipo. Con partido, solo los de las dos inscripciones que lo juegan.
     */
    @Query(value = """
            SELECT i.id AS inscripcionId, e.nombre AS equipo, COALESCE(n.usuario_id, i.usuario_id) AS usuarioId
            FROM inscripcion i
            LEFT JOIN equipo e ON e.id = i.equipo_id
            LEFT JOIN nomina n ON n.inscripcion_id = i.id
            WHERE i.torneo_id = :torneoId AND i.estado = 'CONFIRMADA'
              AND COALESCE(n.usuario_id, i.usuario_id) IS NOT NULL
              AND (CAST(:partidoId AS bigint) IS NULL OR EXISTS (
                   SELECT 1 FROM juega j WHERE j.partido_id = CAST(:partidoId AS bigint) AND j.inscripcion_id = i.id))
            ORDER BY i.id, usuarioId""", nativeQuery = true)
    List<FilaSancionable> sancionables(@Param("torneoId") Long torneoId, @Param("partidoId") Long partidoId);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM juega WHERE partido_id = :partidoId AND inscripcion_id = :inscripcionId)",
            nativeQuery = true)
    boolean juega(@Param("partidoId") Long partidoId, @Param("inscripcionId") Long inscripcionId);
}
