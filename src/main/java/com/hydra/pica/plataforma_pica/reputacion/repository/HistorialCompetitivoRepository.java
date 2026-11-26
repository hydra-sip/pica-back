package com.hydra.pica.plataforma_pica.reputacion.repository;

import java.time.Instant;
import java.util.List;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Las consultas del historial competitivo (HU-801): leen partidos, lados e inscripciones; no guardan nada. */
public interface HistorialCompetitivoRepository extends Repository<Inscripcion, Long> {

    /** Una fila de {@link #historial}. */
    interface Historial {
        int getTorneosJugados();

        int getVictorias();

        int getDerrotas();

        int getPodios();
    }

    /**
     * El historial del usuario, a partir de sus inscripciones: la suya, si es individual, o la de un equipo en cuya
     * nómina figura. La nómina es la definitiva: el que salió por un reemplazo ya no está.
     * <ul>
     *   <li>Cuentan los partidos cerrados (CONFIRMADO, CORREGIDO, WALKOVER) que tienen rival: el BYE y el pase directo
     *       no se jugaron.</li>
     *   <li>Un empate de grupos deja a los dos lados sin ganar, igual que un partido sin presentes con
     *       DESCALIFICAR_AMBOS; los separa el estado: el empate queda CONFIRMADO o CORREGIDO y no suma, el sin
     *       presentes queda WALKOVER y es una derrota para los dos.</li>
     *   <li>Un torneo jugado es uno FINALIZADO en el que jugó alguno de esos partidos.</li>
     *   <li>Un podio es una inscripción en un torneo FINALIZADO con puesto 1, 2 o 3 (los dos semifinalistas comparten
     *       el 3.º).</li>
     * </ul>
     */
    @Query(value = """
            WITH inscripciones AS (
                SELECT i.id, i.torneo_id, i.posicion FROM inscripcion i
                WHERE i.usuario_id = :usuarioId
                   OR EXISTS (SELECT 1 FROM nomina n WHERE n.inscripcion_id = i.id AND n.usuario_id = :usuarioId)
            ), lados AS (
                SELECT j.es_ganador, r.es_ganador AS rival_gano, p.estado, p.torneo_id
                FROM juega j
                JOIN inscripciones i ON i.id = j.inscripcion_id
                JOIN partido p ON p.id = j.partido_id
                JOIN juega r ON r.partido_id = j.partido_id AND r.inscripcion_id <> j.inscripcion_id
                WHERE p.estado IN ('CONFIRMADO', 'CORREGIDO', 'WALKOVER')
            )
            SELECT (SELECT count(DISTINCT l.torneo_id) FROM lados l JOIN torneo t ON t.id = l.torneo_id
                    WHERE t.estado = 'FINALIZADO') AS torneosJugados,
                   (SELECT count(*) FROM lados WHERE es_ganador) AS victorias,
                   (SELECT count(*) FROM lados
                    WHERE NOT es_ganador AND (rival_gano OR estado = 'WALKOVER')) AS derrotas,
                   (SELECT count(*) FROM inscripciones i JOIN torneo t ON t.id = i.torneo_id
                    WHERE t.estado = 'FINALIZADO' AND i.posicion BETWEEN 1 AND 3) AS podios""", nativeQuery = true)
    Historial historial(@Param("usuarioId") Long usuarioId);

    /** Una fila de {@link #ultimosTorneos}. */
    interface TorneoJugado {
        Long getTorneoId();

        String getSlug();

        String getNombre();

        String getDisciplina();

        Instant getFechaFin();

        Integer getPosicion();

        String getEquipo();
    }

    /**
     * Los últimos 5 torneos jugados del usuario, con el mismo criterio que {@code torneosJugados} de
     * {@link #historial}: FINALIZADOS, en los que una inscripción suya jugó al menos un partido cerrado con rival. Van
     * del más reciente al más viejo por la fecha de fin planificada (o la de inicio, si no tiene) y, si empatan, por
     * id. {@code posicion} es null si quedó afuera en grupos; {@code equipo}, si el torneo era individual.
     */
    @Query(value = """
            WITH inscripciones AS (
                SELECT i.id, i.torneo_id, i.posicion, i.equipo_id FROM inscripcion i
                WHERE i.usuario_id = :usuarioId
                   OR EXISTS (SELECT 1 FROM nomina n WHERE n.inscripcion_id = i.id AND n.usuario_id = :usuarioId)
            ), jugadas AS (
                SELECT DISTINCT j.inscripcion_id
                FROM juega j
                JOIN inscripciones i ON i.id = j.inscripcion_id
                JOIN partido p ON p.id = j.partido_id
                JOIN juega r ON r.partido_id = j.partido_id AND r.inscripcion_id <> j.inscripcion_id
                WHERE p.estado IN ('CONFIRMADO', 'CORREGIDO', 'WALKOVER')
            )
            SELECT t.id AS torneoId, t.slug AS slug, t.nombre AS nombre, t.disciplina AS disciplina,
                   t.fecha_fin AS fechaFin, i.posicion AS posicion, e.nombre AS equipo
            FROM inscripciones i
            JOIN jugadas jg ON jg.inscripcion_id = i.id
            JOIN torneo t ON t.id = i.torneo_id
            LEFT JOIN equipo e ON e.id = i.equipo_id
            WHERE t.estado = 'FINALIZADO'
            ORDER BY COALESCE(t.fecha_fin, t.fecha_inicio) DESC, t.id DESC
            LIMIT 5""", nativeQuery = true)
    List<TorneoJugado> ultimosTorneos(@Param("usuarioId") Long usuarioId);
}
