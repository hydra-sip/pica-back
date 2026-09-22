package com.hydra.pica.plataforma_pica.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.config.JpaAuditingConfig;
import com.hydra.pica.plataforma_pica.common.security.SecurityContextCurrentUserProvider;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * Comprueba contra Postgres real que la búsqueda por documento del registro ve a las personas
 * eliminadas, cosa que el @SQLRestriction de la entidad le oculta a los métodos derivados.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SecurityContextCurrentUserProvider.class,
        TestcontainersConfiguration.class
})
class PersonaRepositoryTest {

    @Autowired
    private PersonaRepository personaRepository;

    @Test
    void laBusquedaDelRegistroIncluyeEliminadasYLaDerivadaNo() {
        Persona eliminada = nuevaPersona("DNI", "40111222");
        eliminada.setEliminadoEn(Instant.now());
        personaRepository.saveAndFlush(eliminada);

        assertThat(personaRepository.findByTipoDocAndNroDoc("DNI", "40111222")).isEmpty();
        assertThat(personaRepository.findByDocumentoIncluyendoEliminadas("DNI", "40111222"))
                .get()
                .satisfies(p -> {
                    assertThat(p.getId()).isEqualTo(eliminada.getId());
                    assertThat(p.getEliminadoEn()).isNotNull();
                });
    }

    @Test
    void laBusquedaDelRegistroTambienEncuentraActivas() {
        Persona activa = personaRepository.saveAndFlush(nuevaPersona("DNI", "40111333"));

        assertThat(personaRepository.findByDocumentoIncluyendoEliminadas("DNI", "40111333"))
                .get()
                .extracting(Persona::getId)
                .isEqualTo(activa.getId());
        assertThat(personaRepository.findByDocumentoIncluyendoEliminadas("DNI", "99999999")).isEmpty();
    }

    private static Persona nuevaPersona(String tipoDoc, String nroDoc) {
        Persona persona = new Persona();
        persona.setNombres("Ana");
        persona.setApellidos("García");
        persona.setTipoDoc(tipoDoc);
        persona.setNroDoc(nroDoc);
        persona.setEstado(EstadoGeneral.ACTIVO);
        return persona;
    }
}
