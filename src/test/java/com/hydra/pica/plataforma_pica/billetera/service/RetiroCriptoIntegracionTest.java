package com.hydra.pica.plataforma_pica.billetera.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.hydra.pica.plataforma_pica.EscenarioTorneo;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoRetiroCripto;
import com.hydra.pica.plataforma_pica.billetera.service.LectorDeCadena.RetiroCobrado;
import com.hydra.pica.plataforma_pica.billetera.service.LectorDeCadena.Transaccion;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.security.JwtService;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

/**
 * El retiro de saldo a la wallet (HU-610, PICA-455): pedirlo descuenta el saldo y devuelve el permiso firmado; el
 * cobro leído en la cadena lo confirma; un permiso vencido sin cobrar devuelve el saldo. El contrato y la clave del
 * firmante son los de prueba de {@code src/test/resources/application.yml}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, EscenarioTorneo.class})
class RetiroCriptoIntegracionTest {

    private static final String CONTRATO = "0x00000000000000000000000000000000000ca5a5";
    private static final String FIRMANTE = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";
    private static final String WALLET = "0x262dfbfa37436e5b99a53377e633509d1a9b301a";
    private static final long BLOQUE = 57254703;

    @Autowired private MockMvc mockMvc;
    @Autowired private EscenarioTorneo escenario;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ControlDeBalance control;
    @Autowired private FirmanteDeRetiros firmante;
    @Autowired private RetiroCriptoService retiroCriptoService;
    @Autowired private DevolucionRetirosCripto devolucion;

    // los tests no salen a Base Sepolia: cada uno dice qué ve el nodo
    @MockitoBean private LectorDeCadena cadena;

    @Test
    void alPedirloDescuentaElSaldoYDevuelveElPermisoFirmadoParaEsaWallet() throws Exception {
        Usuario usuario = conSaldo("15000");
        long antes = Instant.now().getEpochSecond();

        // la wallet en mayúsculas (checksum) se guarda y se firma en minúscula, como la ve el contrato
        String respuesta = solicitar(usuario, "10000", "0x262DFbfA37436e5B99A53377e633509D1a9B301A")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.referencia").value(matchesPattern("^0x[0-9a-f]{64}$")))
                .andExpect(jsonPath("$.contrato").value(CONTRATO))
                .andExpect(jsonPath("$.chainId").value(84532))
                .andExpect(jsonPath("$.destino").value(WALLET))
                .andExpect(jsonPath("$.montoPesos").value(10000))
                // con la cotización por defecto, 10^10 wei por peso: $10.000 son 0,0001 ETH
                .andExpect(jsonPath("$.montoWei").value("100000000000000"))
                .andExpect(jsonPath("$.firma").value(matchesPattern("^0x[0-9a-f]{130}$")))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andReturn().getResponse().getContentAsString();

        String referencia = JsonPath.read(respuesta, "$.referencia");
        long vence = ((Number) JsonPath.read(respuesta, "$.vence")).longValue();
        assertThat(vence).isBetween(antes + 3600, Instant.now().getEpochSecond() + 3600);
        // la firma la recupera el contrato como la del firmante configurado
        assertThat(quienFirmo(JsonPath.read(respuesta, "$.firma"),
                firmante.digest(referencia, WALLET, new BigInteger("100000000000000"), vence))).isEqualTo(FIRMANTE);

        verBilletera(usuario).andExpect(jsonPath("$.saldoDisponible").value(5000));
        mockMvc.perform(get("/api/v1/billetera/movimientos").header("Authorization", bearer(usuario)))
                .andExpect(jsonPath("$.content[0].tipo").value("RETIRO"))
                .andExpect(jsonPath("$.content[0].estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.content[0].monto").value(10000))
                .andExpect(jsonPath("$.content[0].descripcion").value("Retiro a la wallet 0x262d…301a"));
        assertThat(control.billeteras(billeteraDe(usuario))).isEmpty();
    }

    @Test
    void tieneLosMismosTopesYElMismoControlDeSaldoQueElRetiroAUnaCuenta() throws Exception {
        Usuario usuario = conSaldo("5000");

        solicitar(usuario, "999", WALLET)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("RETIRO_FUERA_DE_TOPES"));
        solicitar(usuario, "500001", WALLET)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("RETIRO_FUERA_DE_TOPES"));
        solicitar(usuario, "5000.01", WALLET)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("SALDO_INSUFICIENTE"));

        verBilletera(usuario).andExpect(jsonPath("$.saldoDisponible").value(5000));
        solicitar(usuario, "5000", WALLET).andExpect(status().isCreated());
        verBilletera(usuario).andExpect(jsonPath("$.saldoDisponible").value(0));
    }

    @Test
    void unaWalletQueNoEsUnaDireccionSeRechaza() throws Exception {
        Usuario usuario = conSaldo("5000");

        for (String wallet : List.of("0x123", "262dfbfa37436e5b99a53377e633509d1a9b301a",
                "0x262dfbfa37436e5b99a53377e633509d1a9b301g", "0x0000000000000000000000000000000000000000")) {
            solicitar(usuario, "1000", wallet)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("VALIDACION"));
        }
        verBilletera(usuario).andExpect(jsonPath("$.saldoDisponible").value(5000));
    }

    @Test
    void sinSesionNoSePideUnRetiro() throws Exception {
        mockMvc.perform(post("/api/v1/billetera/retiros/cripto")
                        .contentType(APPLICATION_JSON)
                        .content("{\"monto\": 1000, \"destino\": \"" + WALLET + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void conElCobroConfirmadoEnLaCadenaQuedaCobradoConElHashYElMovimientoConfirmado() throws Exception {
        Usuario usuario = conSaldo("10000");
        String referencia = referencia(solicitar(usuario, "10000", WALLET));
        String hash = hashAlAzar();
        cadenaVe(hash, cobroDe(CONTRATO, referencia, WALLET, "100000000000000"), BLOQUE + 2);

        confirmar(usuario, referencia, hash)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("COBRADO"))
                .andExpect(jsonPath("$.hash").value(hash))
                .andExpect(jsonPath("$.enlaceExplorador").value("https://sepolia.basescan.org/tx/" + hash));

        mockMvc.perform(get("/api/v1/billetera/movimientos").header("Authorization", bearer(usuario)))
                .andExpect(jsonPath("$.content[0].tipo").value("RETIRO"))
                .andExpect(jsonPath("$.content[0].estado").value("CONFIRMADO"))
                .andExpect(jsonPath("$.content[0].referenciaExterna").value(hash));
        pendientes(usuario).andExpect(jsonPath("$").isEmpty());
        verBilletera(usuario).andExpect(jsonPath("$.saldoDisponible").value(0));
        assertThat(control.billeteras(billeteraDe(usuario))).isEmpty();
    }

    @Test
    void sinLasConfirmacionesSigueEnEsperaYDespuesSeConfirma() throws Exception {
        Usuario usuario = conSaldo("2000");
        String referencia = referencia(solicitar(usuario, "2000", WALLET));
        String hash = hashAlAzar();
        cadenaVe(hash, cobroDe(CONTRATO, referencia, WALLET, "20000000000000"), BLOQUE + 1);

        confirmar(usuario, referencia, hash)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("TRANSACCION_PENDIENTE"));

        when(cadena.bloqueActual()).thenReturn(BLOQUE + 2);
        confirmar(usuario, referencia, hash).andExpect(jsonPath("$.estado").value("COBRADO"));
    }

    @Test
    void unaTransaccionQueNoCobraEsteRetiroNoLoConfirma() throws Exception {
        Usuario usuario = conSaldo("3000");
        String referencia = referencia(solicitar(usuario, "3000", WALLET));
        String otraWallet = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";
        String otroContrato = "0x36e633edd1e79ef2b9a3c6a099d873d52e44edf0";

        Map<Transaccion, String> casos = Map.of(
                cobroDe(otroContrato, referencia, WALLET, "30000000000000"), "TRANSACCION_SIN_RETIRO",
                cobroDe(CONTRATO, hashAlAzar(), WALLET, "30000000000000"), "TRANSACCION_SIN_RETIRO",
                cobroDe(CONTRATO, referencia, otraWallet, "30000000000000"), "TRANSACCION_SIN_RETIRO",
                cobroDe(CONTRATO, referencia, WALLET, "1"), "TRANSACCION_SIN_RETIRO",
                new Transaccion.Minada(false, BLOQUE, List.of()), "TRANSACCION_FALLIDA",
                new Transaccion.Inexistente(), "TRANSACCION_INEXISTENTE");
        for (var caso : casos.entrySet()) {
            String hash = hashAlAzar();
            cadenaVe(hash, caso.getKey(), BLOQUE + 10);
            confirmar(usuario, referencia, hash)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value(caso.getValue()));
        }

        pendientes(usuario)
                .andExpect(jsonPath("$[0].referencia").value(referencia))
                .andExpect(jsonPath("$[0].estado").value("PENDIENTE"));
        verBilletera(usuario).andExpect(jsonPath("$.saldoDisponible").value(0));
    }

    @Test
    void confirmarDosVecesConElMismoHashNoVuelveAConsultarLaRedYConOtroHashSeRechaza() throws Exception {
        Usuario usuario = conSaldo("1000");
        String referencia = referencia(solicitar(usuario, "1000", WALLET));
        String hash = hashAlAzar();
        cadenaVe(hash, cobroDe(CONTRATO, referencia, WALLET, "10000000000000"), BLOQUE + 5);
        confirmar(usuario, referencia, hash).andExpect(status().isOk());

        confirmar(usuario, referencia, hash.toUpperCase().replace("0X", "0x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("COBRADO"));
        confirmar(usuario, referencia, hashAlAzar())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("RETIRO_YA_COBRADO"));

        verify(cadena, times(1)).transaccion(anyString());
    }

    @Test
    void elRetiroDeOtroUsuarioNoSeEncuentra() throws Exception {
        Usuario usuario = conSaldo("1000");
        String referencia = referencia(solicitar(usuario, "1000", WALLET));

        confirmar(conSaldo("0"), referencia, hashAlAzar())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("RETIRO_NO_ENCONTRADO"));
    }

    @Test
    void unPermisoVencidoSinCobrarDevuelveElSaldoYYaNoSePuedeConfirmar() throws Exception {
        Usuario usuario = conSaldo("4000");
        String referencia = referencia(solicitar(usuario, "4000", WALLET));
        vencer(referencia);
        when(cadena.retiroCobrado(CONTRATO, referencia)).thenReturn(false);

        assertThat(devolucion.resolverVencidos()).isGreaterThanOrEqualTo(1);

        assertThat(estado(referencia)).isEqualTo("DEVUELTO");
        verBilletera(usuario).andExpect(jsonPath("$.saldoDisponible").value(4000));
        mockMvc.perform(get("/api/v1/billetera/movimientos").header("Authorization", bearer(usuario)))
                .andExpect(jsonPath("$.content[0].tipo").value("RETIRO"))
                .andExpect(jsonPath("$.content[0].estado").value("RECHAZADO"));
        assertThat(control.billeteras(billeteraDe(usuario))).isEmpty();

        confirmar(usuario, referencia, hashAlAzar())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("RETIRO_VENCIDO"));
        // una segunda vuelta no lo devuelve dos veces
        assertThat(retiroCriptoService.devolverSiVencio(id(referencia))).isEqualTo(EstadoRetiroCripto.DEVUELTO);
        verBilletera(usuario).andExpect(jsonPath("$.saldoDisponible").value(4000));
    }

    @Test
    void unPermisoVencidoQueElContratoPagoQuedaCobradoSinDevolverNada() throws Exception {
        Usuario usuario = conSaldo("4000");
        String referencia = referencia(solicitar(usuario, "4000", WALLET));
        vencer(referencia);
        when(cadena.retiroCobrado(CONTRATO, referencia)).thenReturn(true);

        assertThat(retiroCriptoService.devolverSiVencio(id(referencia))).isEqualTo(EstadoRetiroCripto.COBRADO);

        verBilletera(usuario).andExpect(jsonPath("$.saldoDisponible").value(0));
        mockMvc.perform(get("/api/v1/billetera/movimientos").header("Authorization", bearer(usuario)))
                .andExpect(jsonPath("$.content[0].estado").value("CONFIRMADO"));
        assertThat(control.billeteras(billeteraDe(usuario))).isEmpty();
    }

    @Test
    void siLaRedNoContestaElVencidoQuedaPendienteParaLaVueltaSiguiente() throws Exception {
        Usuario usuario = conSaldo("4000");
        String referencia = referencia(solicitar(usuario, "4000", WALLET));
        vencer(referencia);
        when(cadena.retiroCobrado(CONTRATO, referencia)).thenThrow(
                new ApiException(HttpStatus.SERVICE_UNAVAILABLE, CodigoError.RED_NO_DISPONIBLE, "sin red"));

        devolucion.resolverVencidos();

        assertThat(estado(referencia)).isEqualTo("PENDIENTE");
        verBilletera(usuario).andExpect(jsonPath("$.saldoDisponible").value(0));
    }

    @Test
    void unPermisoQueNoVencioNoSeRevisa() throws Exception {
        Usuario usuario = conSaldo("4000");
        String referencia = referencia(solicitar(usuario, "4000", WALLET));

        devolucion.resolverVencidos();

        assertThat(estado(referencia)).isEqualTo("PENDIENTE");
        verify(cadena, never()).retiroCobrado(anyString(), eq(referencia));
    }

    @Test
    void losPendientesSonSoloLosDelUsuarioQueTodaviaPuedeCobrar() throws Exception {
        Usuario usuario = conSaldo("3000");
        String primero = referencia(solicitar(usuario, "1000", WALLET));
        String segundo = referencia(solicitar(usuario, "2000", WALLET));
        referencia(solicitar(conSaldo("1000"), "1000", WALLET));

        pendientes(usuario)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].referencia").value(segundo))
                .andExpect(jsonPath("$[1].referencia").value(primero))
                .andExpect(jsonPath("$[1].firma").value(matchesPattern("^0x[0-9a-f]{130}$")));
    }

    // --- ayudas ---

    private static String quienFirmo(String firma, byte[] digest) throws Exception {
        byte[] bytes = Numeric.hexStringToByteArray(firma);
        Sign.SignatureData datos = new Sign.SignatureData(bytes[64], Arrays.copyOfRange(bytes, 0, 32),
                Arrays.copyOfRange(bytes, 32, 64));
        return "0x" + Keys.getAddress(Sign.signedMessageHashToKey(digest, datos));
    }

    private void vencer(String referencia) {
        jdbc.update("UPDATE retiro_cripto SET vence_en = now() - interval '1 hour' WHERE referencia = ?", referencia);
    }

    private String estado(String referencia) {
        return jdbc.queryForObject("SELECT estado FROM retiro_cripto WHERE referencia = ?", String.class, referencia);
    }

    private Long id(String referencia) {
        return jdbc.queryForObject("SELECT id FROM retiro_cripto WHERE referencia = ?", Long.class, referencia);
    }

    private List<Long> billeteraDe(Usuario usuario) {
        return jdbc.queryForList("SELECT id FROM billetera WHERE usuario_id = ?", Long.class, usuario.getId());
    }

    private void cadenaVe(String hash, Transaccion transaccion, long bloqueActual) {
        when(cadena.transaccion(hash)).thenReturn(transaccion);
        when(cadena.bloqueActual()).thenReturn(bloqueActual);
    }

    // un cobro exitoso como lo emite el contrato
    private static Transaccion cobroDe(String contrato, String referencia, String destino, String wei) {
        return new Transaccion.Minada(true, BLOQUE, List.of(),
                List.of(new RetiroCobrado(contrato, referencia, destino, new BigInteger(wei))));
    }

    private static String hashAlAzar() {
        return "0x" + (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }

    private static String referencia(ResultActions solicitud) throws Exception {
        return JsonPath.read(solicitud.andReturn().getResponse().getContentAsString(), "$.referencia");
    }

    private ResultActions solicitar(Usuario usuario, String monto, String destino) throws Exception {
        return mockMvc.perform(post("/api/v1/billetera/retiros/cripto")
                .header("Authorization", bearer(usuario))
                .contentType(APPLICATION_JSON)
                .content("{\"monto\": " + monto + ", \"destino\": \"" + destino + "\"}"));
    }

    private ResultActions confirmar(Usuario usuario, String referencia, String hash) throws Exception {
        return mockMvc.perform(post("/api/v1/billetera/retiros/cripto/{referencia}/confirmar", referencia)
                .header("Authorization", bearer(usuario))
                .contentType(APPLICATION_JSON)
                .content("{\"hash\": \"" + hash + "\"}"));
    }

    private ResultActions pendientes(Usuario usuario) throws Exception {
        return mockMvc.perform(get("/api/v1/billetera/retiros/cripto").header("Authorization", bearer(usuario)));
    }

    private ResultActions verBilletera(Usuario usuario) throws Exception {
        return mockMvc.perform(get("/api/v1/billetera").header("Authorization", bearer(usuario)));
    }

    // un participante nuevo con ese saldo disponible, cargado como una carga confirmada
    private Usuario conSaldo(String monto) {
        Usuario usuario = escenario.usuario("retiro" + UUID.randomUUID().toString().substring(0, 8), "PARTICIPANTE");
        if (!"0".equals(monto)) {
            BalanceCero.cargar(jdbc, usuario.getId(), monto);
        }
        return usuario;
    }

    private String bearer(Usuario usuario) {
        return "Bearer " + jwtService.generarAccessToken(usuario.getId(), usuario.getUsername(),
                List.of("PARTICIPANTE"), List.of(), usuario.getVersionSesion());
    }
}
