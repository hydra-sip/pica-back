package com.hydra.pica.plataforma_pica.partido.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;

import java.util.List;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.partido.domain.SorteoGrupos;
import com.hydra.pica.plataforma_pica.partido.dto.SorteoGruposDetalle;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** El sorteo verificable de los grupos (CR-02-3, PICA-397) contra Postgres, con drand simulado. */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class SorteoGruposIntegracionTest extends TorneoConGruposBase {

    @Autowired private SorteoGruposService sorteoGruposService;

    @Test
    void alCerrarLaInscripcionSeFijaUnaRondaQueNoSalioYSePublicaElCompromiso() {
        inscribir(8);
        long rondaAlCerrar = drand.ronda(java.time.Instant.now());

        cerrar();

        Torneo cerrado = torneoRepository.findById(torneo).orElseThrow();
        assertThat(cerrado.getSemillaRonda()).isGreaterThan(rondaAlCerrar);
        assertThat(cerrado.getCompromisoHash())
                .isEqualTo(SorteoGrupos.compromiso(cerrado.getListaHash(), cerrado.getSemillaRonda()));
        assertThat(cerrado.getSemillaBracket()).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT detalle ->> 'compromisoHash' FROM auditoria
                WHERE entidad = 'TORNEO' AND entidad_id = ? AND accion = 'CIERRE_INSCRIPCION'""",
                String.class, String.valueOf(torneo))).isEqualTo(cerrado.getCompromisoHash());
    }

    @Test
    void conLaListaYLaSemillaSaleExactamenteElMismoReparto() {
        List<Long> inscriptos = inscribir(8);
        cerrar();

        bracketService.generar(organizador, torneo);
        entityManager.flush();
        SorteoGruposDetalle sorteo = sorteoGruposService.ver(torneo);

        assertThat(sorteo.semilla()).isEqualTo(valorDeLaRonda(sorteo.semillaRonda()));
        assertThat(sorteo.semillaFirma()).isEqualTo("firma-" + sorteo.semillaRonda());
        assertThat(sorteo.semillaUrl()).endsWith("/public/" + sorteo.semillaRonda());
        assertThat(sorteo.inscripciones()).containsExactlyElementsOf(inscriptos);
        List<List<Long>> esperado = SorteoGrupos.repartir(sorteo.semilla(), inscriptos, 2);
        assertThat(sorteo.grupos()).extracting(SorteoGruposDetalle.GrupoSorteado::letra).containsExactly("A", "B");
        assertThat(sorteo.grupos().get(0).integrantes()).extracting(SorteoGruposDetalle.Integrante::inscripcionId)
                .containsExactlyElementsOf(esperado.get(0));
        assertThat(sorteo.grupos().get(1).integrantes()).extracting(SorteoGruposDetalle.Integrante::inscripcionId)
                .containsExactlyElementsOf(esperado.get(1));
        assertThat(sorteo.compromisoHash()).isEqualTo(SorteoGrupos.compromiso(sorteo.listaHash(), sorteo.semillaRonda()));
        assertThat(jdbc.queryForObject("SELECT estado FROM torneo WHERE id = ?", String.class, torneo))
                .isEqualTo("EN_CURSO");
    }

    @Test
    void sinElCupoLlenoLosGruposQuedanDesparejos() {
        inscribir(7);
        cerrar();

        bracketService.generar(organizador, torneo);
        entityManager.flush();

        assertThat(sorteoGruposService.ver(torneo).grupos()).extracting(g -> g.integrantes().size())
                .containsExactly(3, 4);
    }

    @Test
    void cadaGrupoNecesitaUnoMasQueLosQuePasan() {
        // 2 grupos y pasan 2: hacen falta 6
        inscribir(5);
        cerrarPorDebajoDelMinimo();

        assertThatThrownBy(() -> bracketService.generar(organizador, torneo))
                .extracting("codigo").isEqualTo(CodigoError.PARTICIPANTES_INSUFICIENTES);
    }

    @Test
    void hastaQueSalgaLaRondaNoSeSortea() {
        inscribir(8);
        cerrar();
        doReturn(false).when(drand).salio(anyLong());

        assertThatThrownBy(() -> bracketService.generar(organizador, torneo))
                .extracting("codigo").isEqualTo(CodigoError.SEMILLA_NO_DISPONIBLE);
    }

    @Test
    void elOrganizadorNoPuedeVolverASortear() {
        inscribir(8);
        cerrar();
        bracketService.generar(organizador, torneo);

        assertThatThrownBy(() -> bracketService.regenerar(organizador, torneo))
                .extracting("codigo").isEqualTo(CodigoError.GRUPOS_YA_SORTEADOS);
    }

    @Test
    void antesDelSorteoSeVeElCompromisoSinSemilla() {
        inscribir(8);
        cerrar();

        SorteoGruposDetalle sorteo = sorteoGruposService.ver(torneo);

        assertThat(sorteo.compromisoHash()).hasSize(64);
        assertThat(sorteo.semilla()).isNull();
        assertThat(sorteo.grupos()).isEmpty();
        assertThat(sorteo.cantidadGrupos()).isEqualTo(2);
    }

    @Test
    void elSorteoQuedaEnLaAuditoria() {
        inscribir(8);
        cerrar();

        bracketService.generar(organizador, torneo);
        entityManager.flush();

        assertThat(jdbc.queryForObject("""
                SELECT detalle ->> 'semilla' FROM auditoria
                WHERE entidad = 'TORNEO' AND entidad_id = ? AND accion = 'SORTEO'""",
                String.class, String.valueOf(torneo))).isEqualTo(sorteoGruposService.ver(torneo).semilla());
    }
}
