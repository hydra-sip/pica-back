package com.hydra.pica.plataforma_pica.control.repository;

import java.util.Optional;

import com.hydra.pica.plataforma_pica.control.domain.AlertaFraude;
import com.hydra.pica.plataforma_pica.control.domain.EstadoAlertaFraude;
import com.hydra.pica.plataforma_pica.control.domain.ReglaFraude;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertaFraudeRepository extends JpaRepository<AlertaFraude, Long>, JpaSpecificationExecutor<AlertaFraude> {

    @Override
    @EntityGraph(attributePaths = {"usuario", "torneo", "rifa", "revisadaPor"})
    Page<AlertaFraude> findAll(Specification<AlertaFraude> filtros, Pageable pageable);

    @EntityGraph(attributePaths = {"usuario", "torneo", "rifa", "revisadaPor"})
    @Query("SELECT a FROM AlertaFraude a WHERE a.id = :id")
    Optional<AlertaFraude> findConRelacionesPorId(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AlertaFraude a WHERE a.id = :id")
    Optional<AlertaFraude> buscarBloqueado(@Param("id") Long id);

    boolean existsByReglaAndUsuarioIdAndRifaIdAndEstado(
            ReglaFraude regla, Long usuarioId, Long rifaId, EstadoAlertaFraude estado);

    boolean existsByReglaAndUsuarioIdAndEstado(
            ReglaFraude regla, Long usuarioId, EstadoAlertaFraude estado);

    boolean existsByReglaAndUsuarioIdAndTorneoIdAndEstado(
            ReglaFraude regla, Long usuarioId, Long torneoId, EstadoAlertaFraude estado);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM alerta_fraude
                WHERE regla = 'CUENTAS_COMPARTIDAS'
                  AND estado = 'PENDIENTE'
                  AND LOWER(datos ->> 'valor') = LOWER(:valor)
            )
            """, nativeQuery = true)
    boolean existsPendienteCuentaCompartidaPorValor(@Param("valor") String valor);
}
