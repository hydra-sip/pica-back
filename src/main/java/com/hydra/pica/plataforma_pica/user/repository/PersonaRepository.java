package com.hydra.pica.plataforma_pica.user.repository;

import java.util.Optional;

import com.hydra.pica.plataforma_pica.user.domain.Persona;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonaRepository extends JpaRepository<Persona, Long> {

    Optional<Persona> findByTipoDocAndNroDoc(String tipoDoc, String nroDoc);

    /** No ve registros con baja lógica; la BD sigue rechazando duplicados */
    boolean existsByTipoDocAndNroDoc(String tipoDoc, String nroDoc);

    /**
     * Igual que {@link #findByTipoDocAndNroDoc} pero trae también las eliminadas. Es nativa
     * a propósito: el {@code @SQLRestriction} de la entidad no se aplica a SQL nativo.
     * La usa el registro para distinguir "no existe" de "existe pero está dada de baja"
     * (el índice único cuenta a las eliminadas, así que no se puede crear otra igual).
     */
    @Query(value = "SELECT * FROM persona WHERE tipo_doc = :tipoDoc AND nro_doc = :nroDoc", nativeQuery = true)
    Optional<Persona> findByDocumentoIncluyendoEliminadas(@Param("tipoDoc") String tipoDoc,
                                                          @Param("nroDoc") String nroDoc);

    /**
     * La persona de un usuario aunque esté dada de baja. Para la ficha de un usuario eliminado
     * (GET /admin/usuarios/{id}): {@code usuario.getPersona()} pasa por el {@code @SQLRestriction}
     * de Persona y da EntityNotFoundException si también se la dio de baja.
     */
    @Query(value = "SELECT p.* FROM persona p JOIN usuario u ON u.persona_id = p.id WHERE u.id = :usuarioId",
            nativeQuery = true)
    Optional<Persona> findByUsuarioIdIncluyendoEliminadas(@Param("usuarioId") Long usuarioId);
}
