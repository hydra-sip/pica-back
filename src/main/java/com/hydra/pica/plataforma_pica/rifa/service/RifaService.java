package com.hydra.pica.plataforma_pica.rifa.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.hydra.pica.plataforma_pica.common.config.RifaConfig.RifaProperties;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.control.service.ParametrosPlataforma;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoNumero;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.PremioRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.dto.FaltantePublicacion;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaResumen;
import com.hydra.pica.plataforma_pica.rifa.repository.NumeroRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.PremioRifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alta, modificación y listado de las rifas propias (PICA-226). Quién puede organizar rifas lo decide
 * el permiso RIFA_GESTIONAR en {@link com.hydra.pica.plataforma_pica.rifa.controller.RifaController};
 * acá cada uno ve y edita solo las suyas, y la de otro responde 404 como si no existiera. Solo se
 * modifica en BORRADOR.
 *
 * Los datos generales y los premios se guardan por separado, como las pestañas de la pantalla: crear
 * o modificar la rifa no toca los premios, y {@link #reemplazarPremios} los reemplaza enteros (se borran
 * todos y se insertan los que vienen; son pocos, no tienen auditoría propia y nada los referencia
 * mientras la rifa es borrador). Las reglas que cruzan las dos (que no haya más premios que números,
 * y que los premios y lo destinado al torneo no pasen de 100 %) se controlan desde los dos lados.
 *
 * En BORRADOR se guarda incompleta: la ventana de venta puede faltar (las dos fechas van juntas) y
 * los premios pueden sumar menos de 100 %. Que sumen exactamente 100 y que estén los términos lo
 * exige la publicación.
 *
 * Sin torneo, la ventana de venta no se compara con fechas de torneo (alcanza con que sean fechas
 * posibles) y todo el pozo se reparte entre los premios de la rifa; eso último se aplica al liquidar,
 * no acá.
 *
 * Con torneo (HU-401), el torneo es del mismo organizador y puede no estar publicado todavía: en
 * BORRADOR la ventana se compara solo con las fechas que el torneo ya tiene, y publicar la rifa exige el
 * torneo publicado. La rifa recauda para el torneo: {@code pctDestinadoTorneo} de lo vendido va a su pozo
 * y el resto a los premios, así que los dos juntos suman 100.
 */
@Service
@RequiredArgsConstructor
public class RifaService {

    private static final int CIEN = 100;
    /** La rifa se asocia a un torneo en borrador o publicado que todavía no empezó. */
    private static final Set<EstadoTorneo> ESTADOS_TORNEO_CON_RIFA =
            Set.of(EstadoTorneo.BORRADOR, EstadoTorneo.INSCRIPCION_ABIERTA, EstadoTorneo.INSCRIPCION_CERRADA);

    private final RifaRepository rifaRepository;
    private final PremioRifaRepository premioRifaRepository;
    private final UsuarioRepository usuarioRepository;
    private final TorneoRepository torneoRepository;
    private final ParametrosPlataforma parametrosPlataforma;
    private final NumeroRepository numeroRepository;
    private final RifaProperties rifaProperties;

    /** La más nueva primero; el orden no se elige desde afuera. */
    @Transactional(readOnly = true)
    public Page<RifaResumen> listarMias(Long organizadorId, Pageable pageable) {
        Pageable porId = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "id"));
        return rifaRepository.findByOrganizadorIdAndEliminadoEnIsNull(organizadorId, porId).map(RifaResumen::de);
    }

    @Transactional(readOnly = true)
    public RifaDetalle detalle(Long organizadorId, Long id) {
        return detalle(buscarPropia(organizadorId, id));
    }

    /** Nace sin premios; se cargan con {@link #reemplazarPremios}. */
    @Transactional
    public RifaDetalle crear(Long organizadorId, RifaRequest request) {
        Torneo torneo = buscarTorneo(organizadorId, null, request.torneoId());
        validar(request, torneo, List.of());

        Rifa rifa = new Rifa();
        rifa.setOrganizador(usuarioRepository.getReferenceById(organizadorId));
        aplicar(rifa, request, torneo);
        return detalle(rifaRepository.saveAndFlush(rifa));
    }

    /** Reemplaza los datos generales; los premios quedan como estaban. */
    @Transactional
    public RifaDetalle modificar(Long organizadorId, Long id, RifaRequest request) {
        Rifa rifa = buscarEditable(organizadorId, id);
        Torneo torneo = buscarTorneo(organizadorId, id, request.torneoId());
        validar(request, torneo, premioRifaRepository.buscarPorRifa(rifa.getId()));

        aplicar(rifa, request, torneo);
        // el flush es para que modificadoEn salga actualizado en la respuesta
        return detalle(rifaRepository.saveAndFlush(rifa));
    }

    /** Lista completa, en orden de puesto: reemplaza la que había (vacía = sin premios). */
    @Transactional
    public RifaDetalle reemplazarPremios(Long organizadorId, Long id, List<PremioRifaRequest> premios) {
        Rifa rifa = buscarEditable(organizadorId, id);
        validarPremios(rifa, premios);

        premioRifaRepository.borrarDeRifa(rifa.getId());
        List<PremioRifa> nuevos = new ArrayList<>();
        for (int i = 0; i < premios.size(); i++) {
            PremioRifa premio = new PremioRifa(rifa, i + 1);
            premio.setPct(premios.get(i).pctEntero());
            premio.setDescripcion(limpiar(premios.get(i).descripcion()));
            nuevos.add(premio);
        }
        premioRifaRepository.saveAll(nuevos);
        return detalle(rifa);
    }

    /**
     * Pasa la rifa de BORRADOR a EN_VENTA (PICA-219). Que el organizador aceptó los términos y declaró la
     * autorización de la lotería lo exige el controller; acá se registra cuándo. No crea números: se
     * insertan al elegirlos (V13).
     *
     * Si le falta algo para venderse, 409 RIFA_INCOMPLETA con todo lo que falta en {@code faltantes}
     * (y la suma en {@code sumaPremios}). Un inicio de venta pasado se acepta y la venta arranca al
     * publicar (PICA-287); un fin pasado no. Con torneo, el torneo tiene que estar publicado y la venta
     * dentro de sus fechas: en BORRADOR pudieron faltar o cambiar.
     */
    @Transactional
    public RifaDetalle publicar(Long organizadorId, Long id) {
        Rifa rifa = buscarEditable(organizadorId, id);
        Instant ahora = Instant.now();
        List<PremioRifa> premios = premioRifaRepository.buscarPorRifa(rifa.getId());
        int sumaPremios = premios.stream().mapToInt(PremioRifa::getPct).sum();
        boolean conVentana = rifa.getVentaDesde() != null && rifa.getVentaHasta() != null;

        List<FaltantePublicacion> faltantes = new ArrayList<>();
        if (!conVentana) {
            faltantes.add(FaltantePublicacion.VENTANA);
        } else if (!rifa.getVentaHasta().isAfter(ahora)) {
            faltantes.add(FaltantePublicacion.VENTA_TERMINADA);
        }
        if (rifa.getSorteadaEn() == null) {
            faltantes.add(FaltantePublicacion.FECHA_SORTEO);
        }
        if (premios.isEmpty()) {
            faltantes.add(FaltantePublicacion.SIN_PREMIOS);
        } else if (rifa.getPctDestinadoTorneo() + sumaPremios != CIEN) {
            faltantes.add(FaltantePublicacion.PREMIOS_NO_SUMAN_100);
        }
        Torneo torneo = rifa.getTorneo();
        if (torneo != null && torneo.getPublicadoEn() == null) {
            faltantes.add(FaltantePublicacion.TORNEO_NO_PUBLICADO);
        } else if (torneo != null && conVentana && (empiezaAntesDelTorneo(rifa.getVentaDesde(), torneo)
                || terminaDespuesDelTorneo(rifa.getVentaHasta(), torneo))) {
            faltantes.add(FaltantePublicacion.VENTANA_FUERA_DEL_TORNEO);
        }
        if (!faltantes.isEmpty()) {
            throw new ConflictoException(CodigoError.RIFA_INCOMPLETA,
                    "A la rifa " + id + " le falta " + faltantes + " para publicarse")
                    .con("faltantes", faltantes)
                    .con("sumaPremios", sumaPremios);
        }

        rifa.setTerminosAceptadosEn(ahora);
        rifa.setLoteriaDeclaradaEn(ahora);
        // los gastos que paga el comprador quedan fijos desde acá, aunque después cambie el parámetro (V27)
        rifa.setPctGastosAdministrativos(parametrosPlataforma.gastosAdministrativos());
        rifa.setEstado(EstadoRifa.EN_VENTA);
        return RifaDetalle.de(rifaRepository.saveAndFlush(rifa), premios, 0);
    }

    /**
     * Vuelve una rifa EN_VENTA a BORRADOR para modificarla (PICA-287), aunque la venta ya haya empezado,
     * mientras nadie haya comprado ni esté pagando ({@link #sinCompradores}). Se borran la aceptación de
     * los términos, la declaración de la lotería y los gastos fijados: al publicar de nuevo se vuelven a
     * registrar.
     */
    @Transactional
    public RifaDetalle volverABorrador(Long organizadorId, Long id) {
        Rifa rifa = noSellada(buscarPropia(organizadorId, id));
        if (rifa.getEstado() != EstadoRifa.EN_VENTA) {
            throw new ConflictoException(CodigoError.RIFA_NO_EDITABLE,
                    "La rifa " + id + " está " + rifa.getEstado() + ": solo vuelve a borrador una EN_VENTA");
        }
        sinCompradores(rifa);

        rifa.setTerminosAceptadosEn(null);
        rifa.setLoteriaDeclaradaEn(null);
        rifa.setPctGastosAdministrativos(null);
        rifa.setEstado(EstadoRifa.BORRADOR);
        return detalle(rifaRepository.saveAndFlush(rifa));
    }

    /**
     * Baja lógica de una rifa en BORRADOR o EN_VENTA sin compradores (PICA-287): deja de aparecer en todos
     * lados y su torneo queda libre para otra rifa (V30).
     */
    @Transactional
    public void eliminar(Long organizadorId, Long id) {
        Rifa rifa = noSellada(buscarPropia(organizadorId, id));
        if (rifa.getEstado() != EstadoRifa.BORRADOR && rifa.getEstado() != EstadoRifa.EN_VENTA) {
            throw new ConflictoException(CodigoError.RIFA_NO_EDITABLE,
                    "La rifa " + id + " está " + rifa.getEstado() + ": solo se elimina en BORRADOR o EN_VENTA");
        }
        sinCompradores(rifa);

        rifa.setEliminadoEn(Instant.now());
        rifaRepository.saveAndFlush(rifa);
    }

    /**
     * Ningún número vendido ni reservado por alguien que está pagando; las reservas vencidas no cuentan y
     * se borran.
     */
    private void sinCompradores(Rifa rifa) {
        long vendidos = numeroRepository.countByIdRifaIdAndEstado(rifa.getId(), EstadoNumero.VENDIDO);
        if (vendidos > 0) {
            throw new ConflictoException(CodigoError.RIFA_CON_VENTAS,
                    "La rifa " + rifa.getId() + " tiene " + vendidos + " números vendidos")
                    .con("numerosVendidos", vendidos);
        }
        Instant vencidasDesde = Instant.now().minus(rifaProperties.duracionReserva());
        if (numeroRepository.countByIdRifaIdAndEstadoAndCreadoEnAfter(rifa.getId(), EstadoNumero.RESERVADO,
                vencidasDesde) > 0) {
            throw new ConflictoException(CodigoError.RIFA_CON_RESERVAS,
                    "Alguien está pagando números de la rifa " + rifa.getId());
        }
        numeroRepository.borrarReservas(rifa.getId());
    }

    private Rifa buscarPropia(Long organizadorId, Long id) {
        return rifaRepository.findByIdAndOrganizadorIdAndEliminadoEnIsNull(id, organizadorId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.RIFA_NO_ENCONTRADA, "No existe la rifa " + id));
    }

    /** Con la venta cerrada, la lista de vendidos está sellada y la rifa ya no cambia (HU-407). */
    private static Rifa noSellada(Rifa rifa) {
        if (rifa.getListaSelladaEn() != null) {
            throw CierreVentaService.listaSellada(rifa.getId());
        }
        return rifa;
    }

    private Rifa buscarEditable(Long organizadorId, Long id) {
        Rifa rifa = buscarPropia(organizadorId, id);
        if (rifa.getEstado() != EstadoRifa.BORRADOR) {
            throw new ConflictoException(CodigoError.RIFA_NO_EDITABLE,
                    "La rifa " + id + " está " + rifa.getEstado() + ": solo se modifica en BORRADOR");
        }
        return rifa;
    }

    /**
     * El torneo al que se asocia la rifa: tiene que ser del mismo organizador (el de otro responde 404, como
     * si no existiera), estar en borrador o publicado sin empezar, y no tener otra rifa (409). Sin
     * {@code torneoId}, null.
     */
    private Torneo buscarTorneo(Long organizadorId, Long rifaId, Long torneoId) {
        if (torneoId == null) {
            return null;
        }
        Torneo torneo = torneoRepository.findByIdAndOrganizadorIdAndEliminadoEnIsNull(torneoId, organizadorId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO,
                        "No existe el torneo " + torneoId));
        if (!ESTADOS_TORNEO_CON_RIFA.contains(torneo.getEstado())) {
            throw new ConflictoException(CodigoError.TORNEO_NO_ADMITE_RIFA,
                    "El torneo " + torneoId + " está " + torneo.getEstado()
                            + ": la rifa va con uno en borrador o con la inscripción abierta o cerrada");
        }
        Optional<Rifa> otra = rifaRepository.findByTorneoIdAndEliminadoEnIsNull(torneoId)
                .filter(r -> !r.getId().equals(rifaId));
        if (otra.isPresent()) {
            throw new ConflictoException(CodigoError.TORNEO_CON_RIFA,
                    "El torneo " + torneoId + " ya tiene la rifa " + otra.get().getId());
        }
        return torneo;
    }

    private void aplicar(Rifa rifa, RifaRequest request, Torneo torneo) {
        rifa.setTorneo(torneo);
        rifa.setPctDestinadoTorneo(request.pctDestinadoTorneoEntero());
        rifa.setTitulo(request.titulo().strip());
        rifa.setDescripcion(limpiar(request.descripcion()));
        rifa.setNumeroDesde(request.numeroDesde());
        rifa.setNumeroHasta(request.numeroHasta());
        rifa.setPrecioNumero(request.precioNumero());
        rifa.setVentaDesde(request.ventaDesde());
        rifa.setVentaHasta(request.ventaHasta());
        rifa.setSorteadaEn(request.fechaSorteo());
    }

    private RifaDetalle detalle(Rifa rifa) {
        return RifaDetalle.de(rifa, premioRifaRepository.buscarPorRifa(rifa.getId()),
                numeroRepository.countByIdRifaIdAndEstado(rifa.getId(), EstadoNumero.VENDIDO));
    }

    // --- validaciones entre campos ------------------------------------------

    /**
     * Junta todos los errores y los devuelve en un solo 400, como Bean Validation. {@code premiosCargados}
     * es para no achicar el rango por debajo de los premios que ya tiene, ni pasar de 100 % con lo
     * destinado al torneo.
     */
    private void validar(RifaRequest request, Torneo torneo, List<PremioRifa> premiosCargados) {
        List<ErrorCampo> errores = new ArrayList<>();
        if (request.numeroHasta() < request.numeroDesde()) {
            errores.add(new ErrorCampo("numeroHasta", ErrorCampo.Codigo.RANGO_INVALIDO,
                    "Tiene que ser mayor o igual que numeroDesde"));
        } else if (cantidadNumeros(request.numeroDesde(), request.numeroHasta()) < premiosCargados.size()) {
            errores.add(new ErrorCampo("numeroHasta", ErrorCampo.Codigo.RANGO_INVALIDO,
                    "La rifa tiene " + premiosCargados.size() + " premios: no puede tener menos números"));
        }
        int pctDestinadoTorneo = request.pctDestinadoTorneoEntero();
        int suma = premiosCargados.stream().mapToInt(PremioRifa::getPct).sum() + pctDestinadoTorneo;
        if (torneo == null && pctDestinadoTorneo != 0) {
            errores.add(new ErrorCampo("pctDestinadoTorneo", ErrorCampo.Codigo.PREMIOS_INVALIDOS,
                    "La rifa no tiene torneo: no se le puede destinar nada"));
        } else if (suma > CIEN) {
            errores.add(new ErrorCampo("pctDestinadoTorneo", ErrorCampo.Codigo.PREMIOS_INVALIDOS,
                    "Con los premios cargados suma " + suma + "%: no puede pasar de 100"));
        }
        validarVentana(request.ventaDesde(), request.ventaHasta(), torneo, errores);
        validarSorteo(request.fechaSorteo(), request.ventaHasta(), errores);
        lanzarSiHay(errores);
    }

    /**
     * Tienen que ser fechas posibles: desde antes que hasta, y hasta en el futuro. Con torneo, además,
     * dentro de las fechas que el torneo ya tenga.
     */
    private void validarVentana(Instant desde, Instant hasta, Torneo torneo, List<ErrorCampo> errores) {
        if (desde == null && hasta == null) {
            return;
        }
        if (desde == null || hasta == null) {
            errores.add(new ErrorCampo(desde == null ? "ventaDesde" : "ventaHasta", ErrorCampo.Codigo.REQUERIDO,
                    "ventaDesde y ventaHasta van juntas: las dos o ninguna"));
            return;
        }
        if (!desde.isBefore(hasta)) {
            errores.add(new ErrorCampo("ventaHasta", ErrorCampo.Codigo.VENTANA_INVALIDA,
                    "Tiene que ser posterior a ventaDesde"));
        } else if (!hasta.isAfter(Instant.now())) {
            errores.add(new ErrorCampo("ventaHasta", ErrorCampo.Codigo.VENTANA_INVALIDA,
                    "La venta no puede terminar en el pasado"));
        } else if (torneo != null) {
            if (empiezaAntesDelTorneo(desde, torneo)) {
                errores.add(new ErrorCampo("ventaDesde", ErrorCampo.Codigo.VENTANA_INVALIDA,
                        "La venta no puede empezar antes de que se publique el torneo"));
            }
            if (terminaDespuesDelTorneo(hasta, torneo)) {
                errores.add(new ErrorCampo("ventaHasta", ErrorCampo.Codigo.VENTANA_INVALIDA,
                        torneo.getFechaFin() != null
                                ? "La fecha debe ser anterior a la finalización del torneo"
                                : "La fecha debe ser anterior al inicio del torneo"));
            }
        }
    }

    /**
     * Con torneo, la venta va de la publicación del torneo a su finalización; si el torneo no tiene fecha de fin, a
     * su inicio. La fecha que el torneo todavía no tiene (borrador) no se controla.
     */
    private static boolean empiezaAntesDelTorneo(Instant ventaDesde, Torneo torneo) {
        return torneo.getPublicadoEn() != null && ventaDesde.isBefore(torneo.getPublicadoEn());
    }

    private static boolean terminaDespuesDelTorneo(Instant ventaHasta, Torneo torneo) {
        Instant limite = torneo.getFechaFin() != null ? torneo.getFechaFin() : torneo.getFechaInicio();
        return limite != null && ventaHasta.isAfter(limite);
    }

    /** La fecha programada del sorteo (opcional en borrador) es futura y posterior al fin de la venta. */
    private void validarSorteo(Instant fechaSorteo, Instant ventaHasta, List<ErrorCampo> errores) {
        if (fechaSorteo == null) {
            return;
        }
        if (!fechaSorteo.isAfter(Instant.now())) {
            errores.add(new ErrorCampo("fechaSorteo", ErrorCampo.Codigo.VENTANA_INVALIDA,
                    "El sorteo no puede ser en el pasado"));
        } else if (ventaHasta != null && !fechaSorteo.isAfter(ventaHasta)) {
            errores.add(new ErrorCampo("fechaSorteo", ErrorCampo.Codigo.VENTANA_INVALIDA,
                    "El sorteo tiene que ser después de que termina la venta"));
        }
    }

    private void validarPremios(Rifa rifa, List<PremioRifaRequest> premios) {
        List<ErrorCampo> errores = new ArrayList<>();
        int suma = premios.stream().mapToInt(PremioRifaRequest::pctEntero).sum() + rifa.getPctDestinadoTorneo();
        if (suma > CIEN) {
            errores.add(new ErrorCampo("premios", ErrorCampo.Codigo.PREMIOS_INVALIDOS,
                    "Los premios y lo destinado al torneo suman " + suma + "%: no pueden pasar de 100"));
        }
        long cantidadNumeros = cantidadNumeros(rifa.getNumeroDesde(), rifa.getNumeroHasta());
        if (premios.size() > cantidadNumeros) {
            errores.add(new ErrorCampo("premios", ErrorCampo.Codigo.PREMIOS_INVALIDOS,
                    "Hay " + premios.size() + " premios y solo " + cantidadNumeros + " números"));
        }
        lanzarSiHay(errores);
    }

    private static long cantidadNumeros(int desde, int hasta) {
        return (long) hasta - desde + 1;
    }

    private static void lanzarSiHay(List<ErrorCampo> errores) {
        if (!errores.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                    .con("errores", errores);
        }
    }

    private static String limpiar(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }
}
