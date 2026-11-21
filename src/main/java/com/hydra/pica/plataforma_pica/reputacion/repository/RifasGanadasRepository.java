package com.hydra.pica.plataforma_pica.reputacion.repository;

import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.rifa.domain.PremioRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.PremioRifaId;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Consultas sobre premios sorteados de rifas ganadas por un usuario (HU-804). */
public interface RifasGanadasRepository extends Repository<PremioRifa, PremioRifaId> {

    interface FilaRifaGanada {
        Long getRifaId();

        String getRifaTitulo();

        Long getTorneoId();

        String getTorneoNombre();

        String getTorneoSlug();

        int getPuesto();

        Integer getNumeroGanador();

        Instant getFecha();
    }

    @Query(value = """
            SELECT r.id AS rifaId,
                   r.titulo AS rifaTitulo,
                   t.id AS torneoId,
                   t.nombre AS torneoNombre,
                   t.slug AS torneoSlug,
                   pr.puesto AS puesto,
                   pr.numero_ganador AS numeroGanador,
                   COALESCE(r.semilla_obtenida_en, r.sorteada_en) AS fecha
            FROM premio_rifa pr
            JOIN rifa r ON r.id = pr.rifa_id
            JOIN numero n ON n.rifa_id = pr.rifa_id AND n.numero = pr.numero_ganador
            JOIN ticket tk ON tk.id = n.ticket_id
            LEFT JOIN torneo t ON t.id = r.torneo_id AND t.eliminado_en IS NULL
            WHERE tk.comprador_id = :usuarioId
              AND pr.numero_ganador IS NOT NULL
              AND r.eliminado_en IS NULL
              AND r.estado IN ('SORTEADA', 'LIQUIDADA')
            ORDER BY COALESCE(r.semilla_obtenida_en, r.sorteada_en) DESC NULLS LAST, r.id DESC, pr.puesto ASC""",
            nativeQuery = true)
    List<FilaRifaGanada> delUsuario(@Param("usuarioId") Long usuarioId);

    @Query(value = """
            SELECT DISTINCT tk.comprador_id
            FROM premio_rifa pr
            JOIN rifa r ON r.id = pr.rifa_id
            JOIN numero n ON n.rifa_id = pr.rifa_id AND n.numero = pr.numero_ganador
            JOIN ticket tk ON tk.id = n.ticket_id
            WHERE pr.rifa_id = :rifaId
              AND pr.numero_ganador IS NOT NULL
              AND r.eliminado_en IS NULL
              AND r.estado IN ('SORTEADA', 'LIQUIDADA')""", nativeQuery = true)
    List<Long> ganadoresDeRifa(@Param("rifaId") Long rifaId);
}
