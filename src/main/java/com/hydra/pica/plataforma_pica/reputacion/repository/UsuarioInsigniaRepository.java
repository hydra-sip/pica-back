package com.hydra.pica.plataforma_pica.reputacion.repository;

import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.reputacion.domain.UsuarioInsignia;
import com.hydra.pica.plataforma_pica.reputacion.domain.UsuarioInsigniaId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsuarioInsigniaRepository extends JpaRepository<UsuarioInsignia, UsuarioInsigniaId> {

    @Query("SELECT ui FROM UsuarioInsignia ui JOIN FETCH ui.insignia "
            + "WHERE ui.id.usuarioId = :usuarioId ORDER BY ui.otorgadaEn DESC, ui.id.codigo ASC")
    List<UsuarioInsignia> delUsuario(@Param("usuarioId") Long usuarioId);

    /** Otorga la insignia de forma idempotente: la PK compuesta impide duplicados. Devuelve 1 si la insertó, 0 si ya existía. */
    @Modifying
    @Query(value = """
            INSERT INTO usuario_insignia (usuario_id, codigo, otorgada_en)
            VALUES (:usuarioId, :codigo, :otorgadaEn)
            ON CONFLICT (usuario_id, codigo) DO NOTHING""", nativeQuery = true)
    int otorgarSiNoTiene(
            @Param("usuarioId") Long usuarioId,
            @Param("codigo") String codigo,
            @Param("otorgadaEn") Instant otorgadaEn);

    /**
     * Secuencia cronológica inversa ({@code 'CHECKIN'} / {@code 'AUSENCIA'}) para evaluar la racha de check-ins
     * seguidos del usuario sin duplicar si hay registro tanto en {@code partido}/{@code juega} como en
     * {@code fair_play_evento}.
     */
    @Query(value = """
            WITH inscripciones_usuario AS (
                SELECT i.id, COALESCE(i.usuario_id, e.capitan_id) AS titular_id
                FROM inscripcion i
                LEFT JOIN equipo e ON e.id = i.equipo_id
                WHERE i.usuario_id = :usuarioId
                   OR EXISTS (SELECT 1 FROM nomina n WHERE n.inscripcion_id = i.id AND n.usuario_id = :usuarioId)
            ), partidos_usuario AS (
                SELECT j.partido_id, iu.titular_id
                FROM juega j
                JOIN inscripciones_usuario iu ON iu.id = j.inscripcion_id
            ), eventos_checkin AS (
                SELECT j.partido_id, 'CHECKIN' AS tipo, j.checkin_en AS fecha
                FROM juega j
                JOIN inscripciones_usuario iu ON iu.id = j.inscripcion_id
                WHERE j.checkin_en IS NOT NULL
                UNION
                SELECT j.partido_id, 'AUSENCIA' AS tipo, COALESCE(p.modificado_en, p.creado_en) AS fecha
                FROM juega j
                JOIN inscripciones_usuario iu ON iu.id = j.inscripcion_id
                JOIN partido p ON p.id = j.partido_id
                WHERE p.estado = 'WALKOVER' AND j.es_ganador = FALSE AND j.checkin_en IS NULL
                UNION
                SELECT fe.partido_id, 'AUSENCIA' AS tipo, fe.creado_en AS fecha
                FROM fair_play_evento fe
                JOIN partidos_usuario pu ON pu.partido_id = fe.partido_id AND fe.usuario_id = pu.titular_id
                WHERE fe.tipo = 'AUSENCIA' AND fe.partido_id IS NOT NULL
            ), deduplicados AS (
                SELECT partido_id, tipo, MAX(fecha) AS fecha
                FROM eventos_checkin
                GROUP BY partido_id, tipo
            )
            SELECT tipo
            FROM deduplicados
            ORDER BY fecha DESC, partido_id DESC, CASE WHEN tipo = 'AUSENCIA' THEN 0 ELSE 1 END""", nativeQuery = true)
    List<String> rachaCheckinsRecientes(@Param("usuarioId") Long usuarioId);
}
