package com.hydra.pica.plataforma_pica.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.config.JpaAuditingConfig;
import com.hydra.pica.plataforma_pica.common.security.SecurityContextCurrentUserProvider;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RNF-AUD-01 (PICA-279): Auditoría de solo agregado.
 *
 * Verifica de punta a punta que la aplicación no puede modificar ni borrar registros de auditoría:
 * 1. A nivel de JPA / ORM: mutaciones sobre la entidad no generan UPDATE en la base de datos
 *    gracias a {@code @Immutable} y {@code updatable = false} en todas las columnas persistentes.
 * 2. A nivel de JPQL y SQL: cualquier intento explícito de UPDATE es abortado por el trigger
 *    {@code trg_auditoria_solo_agregar} con el mensaje 'La auditoría no se modifica ni se borra'.
 * 3. A nivel de borrado (delete, deleteById, deleteAllInBatch, TRUNCATE): los triggers
 *    {@code trg_auditoria_solo_agregar} y {@code trg_auditoria_sin_truncate} impiden la eliminación
 *    y los registros continúan existiendo en la tabla.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SecurityContextCurrentUserProvider.class,
        TestcontainersConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuditoriaSoloAgregadoIntegracionTest {

    @Autowired private AuditoriaRepository auditoriaRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
    }

    private Long crearAuditoria(AccionAuditoria accion, String entidad, String entidadId, String ip, Map<String, Object> detalle) {
        return tx.execute(status -> {
            Auditoria a = new Auditoria();
            a.setAccion(accion);
            a.setEntidad(entidad);
            a.setEntidadId(entidadId);
            a.setIp(ip);
            a.setDetalle(detalle);
            return auditoriaRepository.save(a).getId();
        });
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Intento de modificación vía JPA / Repositorio (AuditoriaRepository + EntityManager)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("RNF-AUD-01: mutar campos y hacer saveAndFlush / flush no modifica los datos en la base (@Immutable y updatable=false)")
    void intentoDeModificacionViaJpaNoAlteraValoresEnBaseDeDatos() {
        Long id = crearAuditoria(
                AccionAuditoria.LOGIN,
                "USUARIO",
                "123",
                "192.168.1.100",
                Map.of("navegador", "Firefox", "version", 1));

        tx.executeWithoutResult(status -> {
            Auditoria a = auditoriaRepository.findById(id).orElseThrow();

            // Mutamos todos los campos modificables
            a.setAccion(AccionAuditoria.LOGOUT);
            a.setEntidad("TORNEO");
            a.setEntidadId("999");
            a.setIp("10.0.0.1");
            a.setDetalle(Map.of("navegador", "Chrome", "version", 2));

            // Intentamos persistir y forzar el flush hacia la base de datos
            auditoriaRepository.saveAndFlush(a);
            entityManager.clear();
        });

        // En una nueva transacción leemos desde la base y comprobamos que los valores originales siguen intactos
        tx.executeWithoutResult(status -> {
            Auditoria recargado = auditoriaRepository.findById(id).orElseThrow();
            assertThat(recargado.getAccion()).isEqualTo(AccionAuditoria.LOGIN);
            assertThat(recargado.getEntidad()).isEqualTo("USUARIO");
            assertThat(recargado.getEntidadId()).isEqualTo("123");
            assertThat(recargado.getIp()).isEqualTo("192.168.1.100");
            assertThat(recargado.getDetalle())
                    .containsEntry("navegador", "Firefox")
                    .containsEntry("version", 1);
        });
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Intento de modificación vía JPQL y SQL desde la aplicación
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("RNF-AUD-01: intento de UPDATE vía JPQL es rechazado por PostgreSQL y el registro original no cambia")
    void intentoDeModificacionViaJpqlEsRechazadoPorTriggerYRegistroNoCambia() {
        Long id = crearAuditoria(AccionAuditoria.LOGIN, "USUARIO", "1", "192.168.1.50", Map.of("k", "v"));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            entityManager.createQuery("UPDATE Auditoria a SET a.ip = '10.0.0.99', a.entidad = 'HACK' WHERE a.id = :id")
                    .setParameter("id", id)
                    .executeUpdate();
        }))
                .isInstanceOf(Exception.class)
                .rootCause().hasMessageContaining("La auditoría no se modifica ni se borra");

        // Verificamos que el registro original sigue exactamente igual
        tx.executeWithoutResult(status -> {
            Auditoria a = auditoriaRepository.findById(id).orElseThrow();
            assertThat(a.getIp()).isEqualTo("192.168.1.50");
            assertThat(a.getEntidad()).isEqualTo("USUARIO");
            assertThat(a.getAccion()).isEqualTo(AccionAuditoria.LOGIN);
        });
    }

    @Test
    @DisplayName("RNF-AUD-01: intento de UPDATE vía SQL nativo es rechazado por PostgreSQL y el registro original no cambia")
    void intentoDeModificacionViaSqlNativoEsRechazadoPorTriggerYRegistroNoCambia() {
        Long id = crearAuditoria(AccionAuditoria.LOGIN, "USUARIO", "1", "192.168.1.50", Map.of("k", "v"));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            entityManager.createNativeQuery("UPDATE auditoria SET ip = '10.0.0.99' WHERE id = :id")
                    .setParameter("id", id)
                    .executeUpdate();
        }))
                .isInstanceOf(Exception.class)
                .rootCause().hasMessageContaining("La auditoría no se modifica ni se borra");

        tx.executeWithoutResult(status -> {
            Auditoria a = auditoriaRepository.findById(id).orElseThrow();
            assertThat(a.getIp()).isEqualTo("192.168.1.50");
        });
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Intento de borrado vía AuditoriaRepository (delete, deleteById, deleteAllInBatch) y TRUNCATE
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("RNF-AUD-01: intento de borrado vía auditoriaRepository.delete(...) es rechazado y el registro continúa existiendo")
    void intentoDeBorradoViaDeleteEntityEsRechazadoPorTriggerYRegistroContinuaExistiendo() {
        Long id = crearAuditoria(AccionAuditoria.LOGIN, "USUARIO", "1", "192.168.1.1", Map.of("k", "v"));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            Auditoria a = auditoriaRepository.findById(id).orElseThrow();
            auditoriaRepository.delete(a);
            auditoriaRepository.flush();
        }))
                .isInstanceOf(Exception.class)
                .rootCause().hasMessageContaining("La auditoría no se modifica ni se borra");

        tx.executeWithoutResult(status -> {
            assertThat(auditoriaRepository.findById(id)).isPresent();
        });
    }

    @Test
    @DisplayName("RNF-AUD-01: intento de borrado vía auditoriaRepository.deleteById(...) es rechazado y el registro continúa existiendo")
    void intentoDeBorradoViaDeleteByIdEsRechazadoPorTriggerYRegistroContinuaExistiendo() {
        Long id = crearAuditoria(AccionAuditoria.LOGIN, "USUARIO", "1", "192.168.1.1", Map.of("k", "v"));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            auditoriaRepository.deleteById(id);
            auditoriaRepository.flush();
        }))
                .isInstanceOf(Exception.class)
                .rootCause().hasMessageContaining("La auditoría no se modifica ni se borra");

        tx.executeWithoutResult(status -> {
            assertThat(auditoriaRepository.findById(id)).isPresent();
        });
    }

    @Test
    @DisplayName("RNF-AUD-01: intento de borrado vía auditoriaRepository.deleteAllInBatch() es rechazado y el registro continúa existiendo")
    void intentoDeBorradoViaDeleteAllInBatchEsRechazadoPorTriggerYRegistroContinuaExistiendo() {
        Long id = crearAuditoria(AccionAuditoria.LOGIN, "USUARIO", "1", "192.168.1.1", Map.of("k", "v"));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            auditoriaRepository.deleteAllInBatch();
        }))
                .isInstanceOf(Exception.class)
                .rootCause().hasMessageContaining("La auditoría no se modifica ni se borra");

        tx.executeWithoutResult(status -> {
            assertThat(auditoriaRepository.findById(id)).isPresent();
        });
    }

    @Test
    @DisplayName("RNF-AUD-01: intento de TRUNCATE TABLE auditoria es rechazado por trg_auditoria_sin_truncate y el registro continúa existiendo")
    void intentoDeTruncateEsRechazadoPorTriggerYRegistroContinuaExistiendo() {
        Long id = crearAuditoria(AccionAuditoria.LOGIN, "USUARIO", "1", "192.168.1.1", Map.of("k", "v"));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            entityManager.createNativeQuery("TRUNCATE TABLE auditoria").executeUpdate();
        }))
                .isInstanceOf(Exception.class)
                .rootCause().hasMessageContaining("La auditoría no se modifica ni se borra");

        tx.executeWithoutResult(status -> {
            assertThat(auditoriaRepository.findById(id)).isPresent();
        });
    }
}
