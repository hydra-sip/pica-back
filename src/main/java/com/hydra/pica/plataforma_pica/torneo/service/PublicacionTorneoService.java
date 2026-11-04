package com.hydra.pica.plataforma_pica.torneo.service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.control.service.ParametrosPlataforma;
import com.hydra.pica.plataforma_pica.torneo.domain.AsistenteTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Gasto;
import com.hydra.pica.plataforma_pica.torneo.domain.Premio;
import com.hydra.pica.plataforma_pica.torneo.domain.SelloTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.dto.FaltanteTorneo;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoInformacion;
import com.hydra.pica.plataforma_pica.torneo.repository.GastoRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.PremioRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publicar el torneo, el último botón del paso 3 (HU-601, PICA-204): revisa que esté todo, le fija el % de gastos
 * administrativos vigente (PICA-283), sella el esquema económico y el reglamento con SHA-256 ({@link SelloTorneo}), le
 * da el enlace público (slug) y abre la inscripción.
 *
 * El torneo queda bloqueado mientras se publica, igual que en los cambios del asistente: así no se publica dos veces
 * ni entra un gasto después del sello.
 */
@Service
@RequiredArgsConstructor
public class PublicacionTorneoService {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);
    /**
     * Slugs que chocan con rutas: /torneos/nuevo del front y los GET /torneos/{id}/... del back, que con el slug en
     * lugar del id empatan con /torneos/slug/{slug}. Se tratan como tomados.
     */
    private static final Set<String> SLUGS_RESERVADOS = Set.of("nuevo", "gastos", "resumen-economico", "reglamento");

    private final TorneosPropios torneosPropios;
    private final TorneoRepository torneoRepository;
    private final PremioRepository premioRepository;
    private final GastoRepository gastoRepository;
    private final ParametrosPlataforma parametrosPlataforma;

    /** Si le falta algo, 409 TORNEO_INCOMPLETO con todo lo que falta en {@code faltantes}; no se publica nada. */
    @Transactional
    public TorneoInformacion publicar(Long organizadorId, Long id) {
        Torneo torneo = torneosPropios.buscarEditable(organizadorId, id);
        List<Premio> premios = premioRepository.findByTorneoIdOrderByIdPuestoAsc(id);
        List<Gasto> gastos = gastoRepository.findByTorneoIdOrderByIdGastoIdAsc(id);
        Instant ahora = Instant.now();
        validar(torneo, premios, gastos, ahora);

        // el % queda fijo desde acá: un cambio del parámetro ya no lo toca
        torneo.setPctGastosAdministrativos(parametrosPlataforma.gastosAdministrativos());
        torneo.setEsquemaHash(SelloTorneo.esquema(torneo, premios, gastos));
        // si hay PDF, el hash es el suyo y lo puso la subida (ReglamentoTorneoService)
        if (torneo.getReglamentoHash() == null && torneo.getReglamentoTexto() != null) {
            torneo.setReglamentoHash(SelloTorneo.sha256(torneo.getReglamentoTexto()));
        }
        torneo.setSlug(slugLibre(torneo.getNombre()));
        torneo.setEstado(EstadoTorneo.INSCRIPCION_ABIERTA);
        torneo.setPublicadoEn(ahora);
        return torneosPropios.informacion(torneoRepository.saveAndFlush(torneo));
    }

    private static void validar(Torneo torneo, List<Premio> premios, List<Gasto> gastos, Instant ahora) {
        List<FaltanteTorneo> faltantes = new ArrayList<>();
        if (!AsistenteTorneo.datosGeneralesCompletos(torneo)) {
            faltantes.add(FaltanteTorneo.INFORMACION);
        } else if (!torneo.getCierreInscripcionEn().isAfter(ahora)) {
            faltantes.add(FaltanteTorneo.CIERRE_VENCIDO);
        }
        if (!AsistenteTorneo.cuposCompletos(torneo)) {
            faltantes.add(FaltanteTorneo.CUPOS);
        }
        boolean conEconomia = torneo.getValorInscripcion() != null;
        if (!conEconomia) {
            faltantes.add(FaltanteTorneo.ECONOMIA);
        }
        BigDecimal sumaPremios = premios.stream().map(Premio::getPct).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (premios.isEmpty()) {
            faltantes.add(FaltanteTorneo.SIN_PREMIOS);
        } else if (sumaPremios.compareTo(CIEN) != 0) {
            faltantes.add(FaltanteTorneo.PREMIOS_NO_SUMAN_100);
        }
        if (conEconomia && torneo.getCupoMinimo() != null && pozoNetoConElMinimo(torneo, gastos).signum() <= 0) {
            faltantes.add(FaltanteTorneo.POZO_NETO_NO_POSITIVO);
        }
        if (!faltantes.isEmpty()) {
            throw new ConflictoException(CodigoError.TORNEO_INCOMPLETO,
                    "Al torneo " + torneo.getId() + " le falta " + faltantes + " para publicarse")
                    .con("faltantes", faltantes)
                    .con("sumaPremios", sumaPremios);
        }
    }

    /** Si arranca con lo justo, los gastos tienen que quedar cubiertos igual. */
    private static BigDecimal pozoNetoConElMinimo(Torneo torneo, List<Gasto> gastos) {
        BigDecimal pozo = torneo.getValorInscripcion().multiply(BigDecimal.valueOf(torneo.getCupoMinimo()));
        return gastos.stream().map(Gasto::getMonto).reduce(pozo, BigDecimal::subtract);
    }

    /** El nombre en minúsculas, sin tildes y con guiones; si ya está tomado, con -2, -3... */
    private String slugLibre(String nombre) {
        String base = Normalizer.normalize(nombre, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (base.isEmpty()) {
            base = "torneo";
        }
        String slug = base;
        for (int n = 2; SLUGS_RESERVADOS.contains(slug) || torneoRepository.existsBySlug(slug); n++) {
            slug = base + "-" + n;
        }
        return slug;
    }
}
