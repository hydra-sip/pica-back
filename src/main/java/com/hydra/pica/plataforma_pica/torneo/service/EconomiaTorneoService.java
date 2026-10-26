package com.hydra.pica.plataforma_pica.torneo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.hydra.pica.plataforma_pica.common.config.TorneoConfig.TorneoProperties;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.torneo.domain.Gasto;
import com.hydra.pica.plataforma_pica.torneo.domain.GastoId;
import com.hydra.pica.plataforma_pica.torneo.domain.Premio;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.dto.EconomiaTorneo;
import com.hydra.pica.plataforma_pica.torneo.dto.EconomiaTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.GastoDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.GastoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.ResumenEconomico;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoInformacion;
import com.hydra.pica.plataforma_pica.torneo.repository.GastoRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.PremioRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Paso 3 del asistente, el esquema económico (HU-601, PICA-203): valor de la inscripción, reparto de la bolsa,
 * premios por puesto y gastos previstos. Como los pasos 1 y 2, cada organizador toca solo sus torneos y se edita
 * solo en BORRADOR; el resumen se puede ver siempre.
 *
 * La bolsa (inscripciones y rifa del torneo) se reparte en pozo de competidores, premio de la rifa y comisión de la
 * plataforma, que tienen que sumar exactamente 100; la comisión no puede pasar el tope de la plataforma
 * ({@code app.torneos.comision-maxima}). Del pozo de competidores se descuentan los gastos y el resto se reparte por
 * puesto: en BORRADOR los puestos pueden sumar menos de 100, y que sumen 100 lo exige la publicación.
 */
@Service
@RequiredArgsConstructor
public class EconomiaTorneoService {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private final TorneosPropios torneosPropios;
    private final TorneoRepository torneoRepository;
    private final PremioRepository premioRepository;
    private final GastoRepository gastoRepository;
    private final TorneoProperties torneoProperties;
    private final AuditingHandler auditingHandler;

    /** Guarda el paso 3 y devuelve el torneo entero, como los pasos 1 y 2. */
    @Transactional
    public TorneoInformacion actualizarEconomia(Long organizadorId, Long id, EconomiaTorneoRequest request) {
        Torneo torneo = torneosPropios.buscarEditable(organizadorId, id);
        validar(torneo, request);

        torneo.setValorInscripcion(request.valorInscripcion());
        torneo.setPctPozoCompetidores(request.pctPozoCompetidores());
        torneo.setPctPremioRifa(request.pctPremioRifa());
        reemplazarPremios(torneo, request.premios());
        // si solo cambian los premios no se entera la auditoría; el flush es para que modificadoEn salga en la respuesta
        auditingHandler.markModified(torneo);
        return torneosPropios.informacion(torneoRepository.saveAndFlush(torneo));
    }

    @Transactional(readOnly = true)
    public List<GastoDetalle> gastos(Long organizadorId, Long id) {
        torneosPropios.buscarPropio(organizadorId, id);
        return gastoRepository.findByTorneoIdOrderByIdGastoIdAsc(id).stream().map(GastoDetalle::de).toList();
    }

    @Transactional
    public GastoDetalle agregarGasto(Long organizadorId, Long id, GastoRequest request) {
        // el número sale del máximo: con el torneo bloqueado, dos altas a la vez no toman el mismo
        Torneo torneo = torneosPropios.buscarEditable(organizadorId, id);
        Gasto gasto = new Gasto(torneo, gastoRepository.ultimoNumero(id) + 1);
        aplicar(gasto, request);
        return GastoDetalle.de(gastoRepository.save(gasto));
    }

    @Transactional
    public GastoDetalle modificarGasto(Long organizadorId, Long id, int gastoId, GastoRequest request) {
        torneosPropios.buscarEditable(organizadorId, id);
        Gasto gasto = buscarGasto(id, gastoId);
        aplicar(gasto, request);
        return GastoDetalle.de(gasto);
    }

    @Transactional
    public void eliminarGasto(Long organizadorId, Long id, int gastoId) {
        torneosPropios.buscarEditable(organizadorId, id);
        gastoRepository.delete(buscarGasto(id, gastoId));
    }

    /** Las cuentas con el cupo completo; ver {@link ResumenEconomico}. */
    @Transactional(readOnly = true)
    public ResumenEconomico resumen(Long organizadorId, Long id) {
        Torneo torneo = torneosPropios.buscarPropio(organizadorId, id);
        BigDecimal gastos = gastoRepository.findByTorneoIdOrderByIdGastoIdAsc(id).stream()
                .map(Gasto::getMonto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal recaudacion = torneo.getValorInscripcion() == null || torneo.getCupoMaximo() == null ? null
                : torneo.getValorInscripcion().multiply(BigDecimal.valueOf(torneo.getCupoMaximo()));
        BigDecimal pctComision = EconomiaTorneo.comision(torneo.getPctPozoCompetidores(), torneo.getPctPremioRifa());
        BigDecimal pozoCompetidores = porcentaje(recaudacion, torneo.getPctPozoCompetidores());
        BigDecimal pozoNeto = pozoCompetidores == null ? null : pozoCompetidores.subtract(gastos);

        List<ResumenEconomico.Puesto> premios = premioRepository.findByTorneoIdOrderByIdPuestoAsc(id).stream()
                .map(p -> new ResumenEconomico.Puesto(p.getId().getPuesto(), p.getPct(), porcentaje(pozoNeto, p.getPct())))
                .toList();
        return new ResumenEconomico(torneo.getCupoMaximo(), torneo.getValorInscripcion(), torneo.getMoneda(),
                recaudacion, porcentaje(recaudacion, pctComision), porcentaje(recaudacion, torneo.getPctPremioRifa()),
                pozoCompetidores, gastos, pozoNeto, premios);
    }

    // --- premios y gastos ---------------------------------------------------

    /**
     * Actualiza los puestos que siguen, agrega los nuevos y borra los que sobran. Se cambian en el lugar y no
     * "borrar todo y volver a insertar" porque Hibernate inserta antes de borrar y chocaría la clave (torneo, puesto).
     */
    private void reemplazarPremios(Torneo torneo, List<BigDecimal> pcts) {
        List<Premio> actuales = premioRepository.findByTorneoIdOrderByIdPuestoAsc(torneo.getId());
        for (int i = 0; i < pcts.size(); i++) {
            Premio premio = i < actuales.size() ? actuales.get(i) : new Premio(torneo, i + 1);
            premio.setPct(pcts.get(i));
            premioRepository.save(premio);
        }
        if (actuales.size() > pcts.size()) {
            premioRepository.deleteAll(actuales.subList(pcts.size(), actuales.size()));
        }
    }

    private Gasto buscarGasto(Long torneoId, int gastoId) {
        return gastoRepository.findById(new GastoId(torneoId, gastoId))
                .orElseThrow(() -> new NoEncontradoException(CodigoError.GASTO_NO_ENCONTRADO,
                        "El torneo " + torneoId + " no tiene el gasto " + gastoId));
    }

    private static void aplicar(Gasto gasto, GastoRequest request) {
        gasto.setDescripcion(request.descripcion().strip());
        gasto.setMonto(request.monto());
    }

    // --- validaciones entre campos ------------------------------------------

    /** Junta todos los errores y los devuelve en un solo 400, como Bean Validation. */
    private void validar(Torneo torneo, EconomiaTorneoRequest request) {
        List<ErrorCampo> errores = new ArrayList<>();
        BigDecimal suma = request.pctPozoCompetidores().add(request.pctPremioRifa()).add(request.pctComision());
        if (suma.compareTo(CIEN) != 0) {
            errores.add(new ErrorCampo("pctPozoCompetidores", ErrorCampo.Codigo.REPARTO_INVALIDO,
                    "Competidores, rifa y comisión suman " + suma.stripTrailingZeros().toPlainString()
                            + " %: tienen que sumar 100"));
        }
        BigDecimal tope = torneoProperties.comisionMaxima();
        if (request.pctComision().compareTo(tope) > 0) {
            errores.add(new ErrorCampo("pctComision", ErrorCampo.Codigo.COMISION_EXCEDIDA,
                    "La comisión de la plataforma no puede pasar del " + tope.stripTrailingZeros().toPlainString()
                            + " %"));
        }
        BigDecimal premios = request.premios().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (premios.compareTo(CIEN) > 0) {
            errores.add(new ErrorCampo("premios", ErrorCampo.Codigo.PREMIOS_INVALIDOS,
                    "Los premios suman " + premios.stripTrailingZeros().toPlainString() + " %: no pueden pasar de 100"));
        } else if (torneo.getCupoMaximo() != null && request.premios().size() > torneo.getCupoMaximo()) {
            errores.add(new ErrorCampo("premios", ErrorCampo.Codigo.PREMIOS_INVALIDOS,
                    "Hay " + request.premios().size() + " puestos con premio y el cupo es de " + torneo.getCupoMaximo()));
        }
        if (!errores.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                    .con("errores", errores)
                    .con("comisionMaxima", tope);
        }
    }

    private static BigDecimal porcentaje(BigDecimal monto, BigDecimal pct) {
        return monto == null || pct == null ? null : monto.multiply(pct).divide(CIEN, 2, RoundingMode.HALF_UP);
    }
}
