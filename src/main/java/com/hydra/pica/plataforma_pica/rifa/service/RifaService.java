package com.hydra.pica.plataforma_pica.rifa.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.PremioRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.dto.FaltantePublicacion;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaResumen;
import com.hydra.pica.plataforma_pica.rifa.repository.PremioRifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
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
 * mientras la rifa es borrador). La única regla que cruza las dos es que no haya más premios que
 * números, y se controla desde los dos lados.
 *
 * En BORRADOR se guarda incompleta: la ventana de venta puede faltar (las dos fechas van juntas) y
 * los premios pueden sumar menos de 100 %. Que sumen exactamente 100 y que estén los términos lo
 * exige la publicación.
 *
 * Sin torneo, la ventana de venta no se compara con fechas de torneo (alcanza con que sean fechas
 * posibles) y todo el pozo se reparte entre los premios de la rifa; eso último se aplica al liquidar,
 * no acá.
 */
@Service
@RequiredArgsConstructor
public class RifaService {

    private static final int CIEN = 100;

    private final RifaRepository rifaRepository;
    private final PremioRifaRepository premioRifaRepository;
    private final UsuarioRepository usuarioRepository;

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
        validar(request, 0);

        Rifa rifa = new Rifa();
        rifa.setOrganizador(usuarioRepository.getReferenceById(organizadorId));
        aplicar(rifa, request);
        return detalle(rifaRepository.saveAndFlush(rifa));
    }

    /** Reemplaza los datos generales; los premios quedan como estaban. */
    @Transactional
    public RifaDetalle modificar(Long organizadorId, Long id, RifaRequest request) {
        Rifa rifa = buscarEditable(organizadorId, id);
        validar(request, premioRifaRepository.contarDeRifa(rifa.getId()));

        aplicar(rifa, request);
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
     * (y la suma en {@code sumaPremios}). Una venta que ya empezó no se ajusta sola: el organizador
     * corrige {@code ventaDesde}.
     */
    @Transactional
    public RifaDetalle publicar(Long organizadorId, Long id) {
        Rifa rifa = buscarEditable(organizadorId, id);
        Instant ahora = Instant.now();
        List<PremioRifa> premios = premioRifaRepository.buscarPorRifa(rifa.getId());
        int sumaPremios = premios.stream().mapToInt(PremioRifa::getPct).sum();

        List<FaltantePublicacion> faltantes = new ArrayList<>();
        if (rifa.getVentaDesde() == null || rifa.getVentaHasta() == null) {
            faltantes.add(FaltantePublicacion.VENTANA);
        } else if (rifa.getVentaDesde().isBefore(ahora)) {
            // También cubre una venta que ya terminó: si ventaHasta pasó, ventaDesde (anterior) también.
            faltantes.add(FaltantePublicacion.VENTA_YA_EMPEZO);
        }
        if (rifa.getSorteadaEn() == null) {
            faltantes.add(FaltantePublicacion.FECHA_SORTEO);
        }
        if (premios.isEmpty()) {
            faltantes.add(FaltantePublicacion.SIN_PREMIOS);
        } else if (sumaPremios != CIEN) {
            faltantes.add(FaltantePublicacion.PREMIOS_NO_SUMAN_100);
        }
        if (!faltantes.isEmpty()) {
            throw new ConflictoException(CodigoError.RIFA_INCOMPLETA,
                    "A la rifa " + id + " le falta " + faltantes + " para publicarse")
                    .con("faltantes", faltantes)
                    .con("sumaPremios", sumaPremios);
        }

        rifa.setTerminosAceptadosEn(ahora);
        rifa.setLoteriaDeclaradaEn(ahora);
        rifa.setEstado(EstadoRifa.EN_VENTA);
        return RifaDetalle.de(rifaRepository.saveAndFlush(rifa), premios);
    }

    private Rifa buscarPropia(Long organizadorId, Long id) {
        return rifaRepository.findByIdAndOrganizadorIdAndEliminadoEnIsNull(id, organizadorId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.RIFA_NO_ENCONTRADA, "No existe la rifa " + id));
    }

    private Rifa buscarEditable(Long organizadorId, Long id) {
        Rifa rifa = buscarPropia(organizadorId, id);
        if (rifa.getEstado() != EstadoRifa.BORRADOR) {
            throw new ConflictoException(CodigoError.RIFA_NO_EDITABLE,
                    "La rifa " + id + " está " + rifa.getEstado() + ": solo se modifica en BORRADOR");
        }
        return rifa;
    }

    private void aplicar(Rifa rifa, RifaRequest request) {
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
        return RifaDetalle.de(rifa, premioRifaRepository.buscarPorRifa(rifa.getId()));
    }

    // --- validaciones entre campos ------------------------------------------

    /**
     * Junta todos los errores y los devuelve en un solo 400, como Bean Validation. {@code premiosCargados}
     * es para no achicar el rango por debajo de los premios que ya tiene.
     */
    private void validar(RifaRequest request, long premiosCargados) {
        List<ErrorCampo> errores = new ArrayList<>();
        if (request.numeroHasta() < request.numeroDesde()) {
            errores.add(new ErrorCampo("numeroHasta", ErrorCampo.Codigo.RANGO_INVALIDO,
                    "Tiene que ser mayor o igual que numeroDesde"));
        } else if (cantidadNumeros(request.numeroDesde(), request.numeroHasta()) < premiosCargados) {
            errores.add(new ErrorCampo("numeroHasta", ErrorCampo.Codigo.RANGO_INVALIDO,
                    "La rifa tiene " + premiosCargados + " premios: no puede tener menos números"));
        }
        validarVentana(request.ventaDesde(), request.ventaHasta(), errores);
        validarSorteo(request.fechaSorteo(), request.ventaHasta(), errores);
        lanzarSiHay(errores);
    }

    /** Sin torneo, alcanza con que sean fechas posibles: desde antes que hasta, y hasta en el futuro. */
    private void validarVentana(Instant desde, Instant hasta, List<ErrorCampo> errores) {
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
        }
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
        int suma = premios.stream().mapToInt(PremioRifaRequest::pctEntero).sum();
        if (suma > CIEN) {
            errores.add(new ErrorCampo("premios", ErrorCampo.Codigo.PREMIOS_INVALIDOS,
                    "Los premios suman " + suma + " %: no pueden pasar de 100"));
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
