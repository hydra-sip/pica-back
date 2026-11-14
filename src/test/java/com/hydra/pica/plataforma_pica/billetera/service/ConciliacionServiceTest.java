package com.hydra.pica.plataforma_pica.billetera.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import com.hydra.pica.plataforma_pica.billetera.domain.CargaCripto;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoCargaCripto;
import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.dto.ConciliacionCargas.Estado;
import com.hydra.pica.plataforma_pica.billetera.dto.ConciliacionCargas.Registro;
import com.hydra.pica.plataforma_pica.billetera.service.EventosDelContrato.Evento;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** El cruce de las cargas con los eventos del contrato (HU-608, PICA-343), con eventos simulados. */
class ConciliacionServiceTest {

    private static final BigInteger CIEN_MIL_PESOS = new BigInteger("1000000000000000");
    private static final String PAGADOR = "0x262dfbfa37436e5b99a53377e633509d1a9b301a";

    @Test
    @DisplayName("Una carga acreditada y registrada en la cadena por el mismo monto queda conciliada")
    void conciliada() {
        CargaCripto carga = acreditada(1, ref(1), hash(1), CIEN_MIL_PESOS);

        List<Registro> registros = ConciliacionService.conciliar(List.of(carga),
                List.of(evento(ref(1), hash(1), CIEN_MIL_PESOS, 100)));

        assertThat(registros).singleElement().satisfies(r -> {
            assertThat(r.estado()).isEqualTo(Estado.CONCILIADA);
            assertThat(r.cargaId()).isEqualTo(1L);
            assertThat(r.username()).isEqualTo("ana");
            assertThat(r.movimientoId()).isEqualTo(10L);
            assertThat(r.montoWei()).isEqualTo(r.montoWeiEnCadena()).isEqualTo(CIEN_MIL_PESOS.toString());
            assertThat(r.bloque()).isEqualTo(100L);
        });
    }

    @Test
    @DisplayName("Detecta los tres casos: movimiento sin evento, evento sin movimiento y montos distintos")
    void lasTresDiferencias() {
        CargaCripto huerfana = acreditada(1, ref(1), hash(1), CIEN_MIL_PESOS);
        CargaCripto distinta = acreditada(2, ref(2), hash(2), CIEN_MIL_PESOS);
        CargaCripto pendiente = pendiente(3, ref(3), CIEN_MIL_PESOS);
        CargaCripto conciliada = acreditada(4, ref(4), hash(4), CIEN_MIL_PESOS);

        List<Registro> registros = ConciliacionService.conciliar(List.of(huerfana, distinta, pendiente, conciliada),
                List.of(evento(ref(2), hash(2), BigInteger.ONE, 200),
                        evento(ref(3), hash(3), CIEN_MIL_PESOS, 300),
                        evento(ref(9), hash(9), CIEN_MIL_PESOS, 900),
                        evento(ref(4), hash(4), CIEN_MIL_PESOS, 400)));

        // primero las diferencias, por bloque (la huérfana no tiene), y al final la conciliada
        assertThat(registros).extracting(Registro::estado, Registro::referencia, Registro::cargaId).containsExactly(
                tuple(Estado.HUERFANA, ref(1), 1L),
                tuple(Estado.MONTO_DISTINTO, ref(2), 2L),
                tuple(Estado.NO_ACREDITADA, ref(3), 3L),
                tuple(Estado.NO_ACREDITADA, ref(9), null),
                tuple(Estado.CONCILIADA, ref(4), 4L));
        assertThat(registros.get(0).hashEnCadena()).isNull();
        assertThat(registros.get(1).montoWeiEnCadena()).isEqualTo("1");
        assertThat(registros.get(2).movimientoId()).isNull();
        assertThat(registros.get(3).username()).isNull();
    }

    @Test
    @DisplayName("Una referencia pagada dos veces: la transacción de la carga concilia y la otra es un pago no acreditado")
    void pagadaDosVeces() {
        CargaCripto carga = acreditada(1, ref(1), hash(2), CIEN_MIL_PESOS);

        List<Registro> registros = ConciliacionService.conciliar(List.of(carga),
                List.of(evento(ref(1), hash(1), CIEN_MIL_PESOS, 100), evento(ref(1), hash(2), CIEN_MIL_PESOS, 101)));

        assertThat(registros).extracting(Registro::estado, Registro::hashEnCadena).containsExactly(
                tuple(Estado.NO_ACREDITADA, hash(1)),
                tuple(Estado.CONCILIADA, hash(2)));
    }

    @Test
    @DisplayName("La referencia y el hash guardados se comparan sin importar mayúsculas")
    void mayusculas() {
        CargaCripto carga = acreditada(1, ref(1).toUpperCase().replace("0X", "0x"), hash(1).toUpperCase(),
                CIEN_MIL_PESOS);

        assertThat(ConciliacionService.conciliar(List.of(carga), List.of(evento(ref(1), hash(1), CIEN_MIL_PESOS, 1))))
                .extracting(Registro::estado).containsExactly(Estado.CONCILIADA);
    }

    private static CargaCripto acreditada(long id, String referencia, String hash, BigInteger wei) {
        CargaCripto carga = pendiente(id, referencia, wei);
        carga.setEstado(EstadoCargaCripto.ACREDITADA);
        carga.setHash(hash);
        carga.setPagador(PAGADOR);
        Movimiento movimiento = new Movimiento();
        ReflectionTestUtils.setField(movimiento, "id", id * 10);
        carga.setMovimiento(movimiento);
        return carga;
    }

    private static CargaCripto pendiente(long id, String referencia, BigInteger wei) {
        Usuario usuario = new Usuario();
        usuario.setUsername("ana");
        CargaCripto carga = new CargaCripto();
        ReflectionTestUtils.setField(carga, "id", id);
        carga.setReferencia(referencia);
        carga.setUsuario(usuario);
        carga.setMontoPesos(new BigDecimal("100000.00"));
        carga.setMontoWei(wei);
        return carga;
    }

    private static Evento evento(String referencia, String hash, BigInteger monto, long bloque) {
        return new Evento(referencia, PAGADOR, monto, hash, bloque);
    }

    private static String ref(int i) {
        return "0x%064x".formatted(i);
    }

    private static String hash(int i) {
        return "0x%064x".formatted(1000 + i);
    }
}
