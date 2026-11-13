package com.hydra.pica.plataforma_pica.billetera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.hydra.pica.plataforma_pica.EscenarioTorneo;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.billetera.service.LectorDeCadena;
import com.hydra.pica.plataforma_pica.billetera.service.LectorDeCadena.CargaRegistrada;
import com.hydra.pica.plataforma_pica.billetera.service.LectorDeCadena.Transaccion;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.security.JwtService;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaRepository;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** La carga de saldo pagada con la wallet en Base Sepolia (HU-609, PICA-333). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, EscenarioTorneo.class})
class CargaCriptoIntegracionTest {

    private static final String CONTRATO = "0x36e633eDD1E79Ef2b9A3c6A099D873D52e44EDF0";
    private static final String PAGADOR = "0x262dfbfa37436e5b99a53377e633509d1a9b301a";
    private static final long BLOQUE = 57254703;

    @Autowired private MockMvc mockMvc;
    @Autowired private EscenarioTorneo escenario;
    @Autowired private JwtService jwtService;
    @Autowired private AuditoriaRepository auditoriaRepository;

    // los tests no salen a Base Sepolia: cada uno dice qué ve el nodo
    @MockitoBean private LectorDeCadena cadena;

    @Test
    void alIniciarLaCargaDevuelveLaReferenciaElContratoYLosWeiAPagar() throws Exception {
        Usuario usuario = crearUsuario("cripto");

        iniciar(usuario, "10000")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.referencia").value(matchesPattern("^0x[0-9a-f]{64}$")))
                .andExpect(jsonPath("$.contrato").value(CONTRATO))
                .andExpect(jsonPath("$.chainId").value(84532))
                // con la cotización por defecto, 10^10 wei por peso: $10.000 son 0,0001 ETH
                .andExpect(jsonPath("$.montoWei").value("100000000000000"))
                .andExpect(jsonPath("$.montoPesos").value(10000));
    }

    @Test
    void conCentavosLosWeiSonExactos() throws Exception {
        // $0,01 son 10^8 wei, y $1.234,56 son 123456 × 10^8
        iniciar(crearUsuario("cripto"), "0.01")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.montoWei").value("100000000"));
        iniciar(crearUsuario("cripto"), "1234.56")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.montoWei").value("12345600000000"))
                .andExpect(jsonPath("$.montoPesos").value(1234.56));
    }

    @Test
    void cadaCargaTieneSuPropiaReferencia() throws Exception {
        Usuario usuario = crearUsuario("cripto");

        String primera = JsonPath.read(iniciar(usuario, "500").andReturn().getResponse().getContentAsString(),
                "$.referencia");
        String segunda = JsonPath.read(iniciar(usuario, "500").andReturn().getResponse().getContentAsString(),
                "$.referencia");

        assertThat(primera).isNotEqualTo(segunda);
    }

    @Test
    void elMontoTieneLosMismosLimitesQueLaCargaDeSaldo() throws Exception {
        Usuario usuario = crearUsuario("cripto");

        for (String monto : List.of("0", "-500", "1000000.01", "10.001")) {
            iniciar(usuario, monto)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("VALIDACION"));
        }
        iniciar(usuario, "1000000").andExpect(status().isCreated())
                .andExpect(jsonPath("$.montoWei").value("10000000000000000"));
    }

    @Test
    void sinSesionNoSeIniciaUnaCarga() throws Exception {
        mockMvc.perform(post("/api/v1/billetera/cargas/cripto")
                        .contentType(APPLICATION_JSON)
                        .content("{\"monto\": 100}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void alConfirmarUnPagoConfirmadoSeAcreditaConElHashYElEnlaceAlExplorador() throws Exception {
        Usuario usuario = crearUsuario("cripto");
        String referencia = referencia(iniciar(usuario, "10000"));
        String hash = hashAlAzar();
        cadenaVe(hash, pagoDe(referencia, "100000000000000"), BLOQUE + 10);

        confirmar(usuario, referencia, hash)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo").value("CARGA"))
                .andExpect(jsonPath("$.estado").value("CONFIRMADO"))
                .andExpect(jsonPath("$.monto").value(10000))
                .andExpect(jsonPath("$.saldoResultante").value(10000))
                .andExpect(jsonPath("$.referenciaExterna").value(hash))
                .andExpect(jsonPath("$.descripcion").value("Carga con cripto"))
                .andExpect(jsonPath("$.enlaceExplorador").value("https://sepolia.basescan.org/tx/" + hash));

        mockMvc.perform(get("/api/v1/billetera").header("Authorization", "Bearer " + token(usuario)))
                .andExpect(jsonPath("$.saldoDisponible").value(10000));
    }

    @Test
    void elEnlaceAlExploradorApareceEnLosMovimientosSoloEnLasCargasConCripto() throws Exception {
        Usuario usuario = crearUsuario("cripto");
        mockMvc.perform(post("/api/v1/billetera/cargas").header("Authorization", "Bearer " + token(usuario))
                .contentType(APPLICATION_JSON).content("{\"monto\": 50}")).andExpect(status().isCreated());
        String referencia = referencia(iniciar(usuario, "10000"));
        String hash = hashAlAzar();
        cadenaVe(hash, pagoDe(referencia, "100000000000000"), BLOQUE + 10);
        confirmar(usuario, referencia, hash).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/billetera/movimientos").header("Authorization", "Bearer " + token(usuario)))
                .andExpect(jsonPath("$.content[0].descripcion").value("Carga con cripto"))
                .andExpect(jsonPath("$.content[0].enlaceExplorador").value("https://sepolia.basescan.org/tx/" + hash))
                .andExpect(jsonPath("$.content[1].enlaceExplorador").doesNotExist());
    }

    @Test
    void unaCargaDeOtroUsuarioOQueNoExisteNoSeEncuentra() throws Exception {
        Usuario duenio = crearUsuario("cripto");
        Usuario otro = crearUsuario("cripto");
        String referencia = referencia(iniciar(duenio, "10000"));
        String hash = hashAlAzar();
        cadenaVe(hash, pagoDe(referencia, "100000000000000"), BLOQUE + 10);

        confirmar(otro, referencia, hash)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("CARGA_NO_ENCONTRADA"));
        confirmar(duenio, hashAlAzar(), hash)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("CARGA_NO_ENCONTRADA"));
        mockMvc.perform(get("/api/v1/billetera").header("Authorization", "Bearer " + token(otro)))
                .andExpect(jsonPath("$.saldoDisponible").value(0));
    }

    @Test
    void unHashQueNoTieneElFormatoDeUnaTransaccionEsInvalido() throws Exception {
        Usuario usuario = crearUsuario("cripto");
        String referencia = referencia(iniciar(usuario, "10000"));

        for (String hash : List.of("", "0x123", "2f27d3c26ea9b5b2dd0a441a17a70ea3dc2830757e2f2cf564f0cf6d9479e9eb0x",
                "0x" + "g".repeat(64))) {
            confirmar(usuario, referencia, hash)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("VALIDACION"));
        }
    }

    @Test
    void unaTransaccionQueRevirtioNoAcreditaYQuedaEnAuditoria() throws Exception {
        Usuario usuario = crearUsuario("cripto");
        String referencia = referencia(iniciar(usuario, "10000"));
        String hash = hashAlAzar();
        cadenaVe(hash, new Transaccion.Minada(false, BLOQUE, List.of()), BLOQUE + 10);

        confirmar(usuario, referencia, hash)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("TRANSACCION_FALLIDA"));

        assertThat(rechazos(usuario)).singleElement().satisfies(rechazo -> {
            assertThat(rechazo.getDetalle()).containsEntry("referencia", referencia);
            assertThat(rechazo.getDetalle()).containsEntry("hash", hash);
            assertThat(rechazo.getDetalle().get("motivo")).asString().contains("revirtió");
        });
        sinSaldo(usuario);
    }

    @Test
    void unEventoIgualEmitidoPorOtroContratoNoPagaLaCarga() throws Exception {
        Usuario usuario = crearUsuario("cripto");
        String referencia = referencia(iniciar(usuario, "10000"));
        String hash = hashAlAzar();
        cadenaVe(hash, new Transaccion.Minada(true, BLOQUE, List.of(new CargaRegistrada(
                "0x000000000000000000000000000000000000dead", referencia, PAGADOR,
                new BigInteger("100000000000000")))), BLOQUE + 10);

        confirmar(usuario, referencia, hash)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("TRANSACCION_SIN_CARGA"));
        assertThat(rechazos(usuario)).hasSize(1);
        sinSaldo(usuario);
    }

    @Test
    void elPagoDeOtraReferenciaNoPagaEstaCarga() throws Exception {
        Usuario usuario = crearUsuario("cripto");
        String referencia = referencia(iniciar(usuario, "10000"));
        String otra = referencia(iniciar(usuario, "10000"));
        String hash = hashAlAzar();
        cadenaVe(hash, pagoDe(otra, "100000000000000"), BLOQUE + 10);

        confirmar(usuario, referencia, hash)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("TRANSACCION_SIN_CARGA"));
        sinSaldo(usuario);
    }

    @Test
    void unPagoDeMasODeMenosNoAcredita() throws Exception {
        Usuario usuario = crearUsuario("cripto");
        String referencia = referencia(iniciar(usuario, "10000"));

        for (String wei : List.of("100000000000001", "99999999999999")) {
            String hash = hashAlAzar();
            cadenaVe(hash, pagoDe(referencia, wei), BLOQUE + 10);
            confirmar(usuario, referencia, hash)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("MONTO_NO_COINCIDE"));
        }
        assertThat(rechazos(usuario)).hasSize(2);
        sinSaldo(usuario);
    }

    @Test
    void despuesDeUnRechazoLaCargaSeConfirmaConLaTransaccionCorrecta() throws Exception {
        Usuario usuario = crearUsuario("cripto");
        String referencia = referencia(iniciar(usuario, "10000"));
        String fallida = hashAlAzar();
        String buena = hashAlAzar();
        cadenaVe(fallida, new Transaccion.Minada(false, BLOQUE, List.of()), BLOQUE + 10);
        cadenaVe(buena, pagoDe(referencia, "100000000000000"), BLOQUE + 10);

        confirmar(usuario, referencia, fallida).andExpect(status().isBadRequest());
        confirmar(usuario, referencia, buena)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenciaExterna").value(buena));
        mockMvc.perform(get("/api/v1/billetera").header("Authorization", "Bearer " + token(usuario)))
                .andExpect(jsonPath("$.saldoDisponible").value(10000));
    }

    @Test
    void unaTransaccionQueTodaviaNoEntroEnUnBloqueEstaPendiente() throws Exception {
        Usuario usuario = crearUsuario("cripto");
        String referencia = referencia(iniciar(usuario, "10000"));
        String hash = hashAlAzar();
        cadenaVe(hash, new Transaccion.Pendiente(), BLOQUE);

        confirmar(usuario, referencia, hash)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("TRANSACCION_PENDIENTE"));
        sinSaldo(usuario);
    }

    @Test
    void conMenosConfirmacionesQueLasMinimasEstaPendienteYConLasSuficientesAcredita() throws Exception {
        Usuario usuario = crearUsuario("cripto");
        String referencia = referencia(iniciar(usuario, "10000"));
        String hash = hashAlAzar();

        // 3 confirmaciones por defecto, contando el bloque de la transacción: con 2 todavía no
        cadenaVe(hash, pagoDe(referencia, "100000000000000"), BLOQUE + 1);
        confirmar(usuario, referencia, hash)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("TRANSACCION_PENDIENTE"));
        sinSaldo(usuario);

        cadenaVe(hash, pagoDe(referencia, "100000000000000"), BLOQUE + 2);
        confirmar(usuario, referencia, hash).andExpect(status().isOk());
    }

    @Test
    void unHashQueElNodoNoConoceNoExisteYQuedaEnAuditoria() throws Exception {
        Usuario usuario = crearUsuario("cripto");
        String referencia = referencia(iniciar(usuario, "10000"));
        String hash = hashAlAzar();
        cadenaVe(hash, new Transaccion.Inexistente(), BLOQUE);

        confirmar(usuario, referencia, hash)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("TRANSACCION_INEXISTENTE"));
        assertThat(rechazos(usuario)).hasSize(1);
        sinSaldo(usuario);
    }

    @Test
    void reintentarUnHashQueElNodoTodaviaNoVeDejaUnSoloRechazoYDespuesAcredita() throws Exception {
        Usuario usuario = crearUsuario("cripto");
        String referencia = referencia(iniciar(usuario, "10000"));
        String hash = hashAlAzar();
        // el nodo del back va atrás del que leyó el recibo: el front reintenta cada 3 s
        cadenaVe(hash, new Transaccion.Inexistente(), BLOQUE);
        for (int intento = 0; intento < 3; intento++) {
            confirmar(usuario, referencia, hash)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("TRANSACCION_INEXISTENTE"));
        }
        assertThat(rechazos(usuario)).hasSize(1);

        cadenaVe(hash, pagoDe(referencia, "100000000000000"), BLOQUE + 10);
        confirmar(usuario, referencia, hash).andExpect(status().isOk());
        assertThat(rechazos(usuario)).hasSize(1);
    }

    @Test
    void unaReferenciaQueNoTieneElFormatoDeLaDelContratoEsInvalida() throws Exception {
        Usuario usuario = crearUsuario("cripto");
        iniciar(usuario, "10000");

        for (String referencia : List.of("0x123", "0x" + "g".repeat(64), "6d6f26c23203ae638686312ea1c8b3d6c83c0e4c")) {
            confirmar(usuario, referencia, hashAlAzar())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                    .andExpect(jsonPath("$.errores[0].codigo").value("FORMATO_INVALIDO"));
        }
        verifyNoInteractions(cadena);
    }

    @Test
    void siLaRedNoEstaDisponibleNoCambiaNada() throws Exception {
        Usuario usuario = crearUsuario("cripto");
        String referencia = referencia(iniciar(usuario, "10000"));
        when(cadena.transaccion(anyString())).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                CodigoError.RED_NO_DISPONIBLE, "No se pudo consultar la red"));

        confirmar(usuario, referencia, hashAlAzar())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.codigo").value("RED_NO_DISPONIBLE"));
        assertThat(rechazos(usuario)).isEmpty();
        sinSaldo(usuario);
    }

    @Test
    void reconfirmarConElMismoHashDevuelveElMismoMovimientoSinConsultarLaRed() throws Exception {
        Usuario usuario = crearUsuario("cripto");
        String referencia = referencia(iniciar(usuario, "10000"));
        String hash = hashAlAzar();
        cadenaVe(hash, pagoDe(referencia, "100000000000000"), BLOQUE + 10);
        Integer movimiento = JsonPath.read(confirmar(usuario, referencia, hash).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");

        // un reintento del front, aunque mande el hash en mayúsculas
        for (String mismo : List.of(hash, "0x" + hash.substring(2).toUpperCase(Locale.ROOT))) {
            confirmar(usuario, referencia, mismo)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(movimiento))
                    .andExpect(jsonPath("$.referenciaExterna").value(hash));
        }
        verify(cadena, times(1)).transaccion(anyString());
        mockMvc.perform(get("/api/v1/billetera").header("Authorization", "Bearer " + token(usuario)))
                .andExpect(jsonPath("$.saldoDisponible").value(10000));
    }

    @Test
    void unaCargaAcreditadaNoSeVuelveAAcreditarConOtraTransaccion() throws Exception {
        Usuario usuario = crearUsuario("cripto");
        String referencia = referencia(iniciar(usuario, "10000"));
        String hash = hashAlAzar();
        String otro = hashAlAzar();
        cadenaVe(hash, pagoDe(referencia, "100000000000000"), BLOQUE + 10);
        cadenaVe(otro, pagoDe(referencia, "100000000000000"), BLOQUE + 10);
        confirmar(usuario, referencia, hash).andExpect(status().isOk());

        confirmar(usuario, referencia, otro)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CARGA_YA_ACREDITADA"));
        mockMvc.perform(get("/api/v1/billetera").header("Authorization", "Bearer " + token(usuario)))
                .andExpect(jsonPath("$.saldoDisponible").value(10000));
    }

    @Test
    void dosConfirmacionesAlMismoTiempoAcreditanUnaSolaVez() throws Exception {
        Usuario usuario = crearUsuario("cripto");
        String referencia = referencia(iniciar(usuario, "10000"));
        String hash = hashAlAzar();
        cadenaVe(hash, pagoDe(referencia, "100000000000000"), BLOQUE + 10);

        int hilos = 4;
        ExecutorService executor = Executors.newFixedThreadPool(hilos);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<String>> respuestas = new ArrayList<>();
        for (int i = 0; i < hilos; i++) {
            respuestas.add(executor.submit(() -> {
                largada.await();
                return confirmar(usuario, referencia, hash).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();
            }));
        }
        largada.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        List<Object> ids = new ArrayList<>();
        for (Future<String> respuesta : respuestas) {
            ids.add(JsonPath.read(respuesta.get(), "$.id"));
        }
        assertThat(ids).hasSize(hilos).containsOnly(ids.getFirst());
        mockMvc.perform(get("/api/v1/billetera").header("Authorization", "Bearer " + token(usuario)))
                .andExpect(jsonPath("$.saldoDisponible").value(10000));
        mockMvc.perform(get("/api/v1/billetera/movimientos").header("Authorization", "Bearer " + token(usuario)))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void unaTransaccionQueRevirtioSeRechazaSinEsperarLasConfirmaciones() throws Exception {
        Usuario usuario = crearUsuario("cripto");
        String referencia = referencia(iniciar(usuario, "10000"));
        String hash = hashAlAzar();
        cadenaVe(hash, new Transaccion.Minada(false, BLOQUE, List.of()), BLOQUE);

        confirmar(usuario, referencia, hash)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("TRANSACCION_FALLIDA"));
    }

    @Test
    void unaTransaccionQueYaAcreditoUnaCargaNoAcreditaOtra() throws Exception {
        Usuario usuario = crearUsuario("cripto");
        String primera = referencia(iniciar(usuario, "10000"));
        String segunda = referencia(iniciar(usuario, "10000"));
        String hash = hashAlAzar();
        // una smart wallet que paga las dos cargas en una sola transacción
        cadenaVe(hash, new Transaccion.Minada(true, BLOQUE, List.of(
                new CargaRegistrada(CONTRATO.toLowerCase(), primera, PAGADOR, new BigInteger("100000000000000")),
                new CargaRegistrada(CONTRATO.toLowerCase(), segunda, PAGADOR, new BigInteger("100000000000000")))),
                BLOQUE + 10);
        confirmar(usuario, primera, hash).andExpect(status().isOk());

        confirmar(usuario, segunda, hash)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("TRANSACCION_YA_USADA"));
        assertThat(rechazos(usuario)).hasSize(1);
        mockMvc.perform(get("/api/v1/billetera").header("Authorization", "Bearer " + token(usuario)))
                .andExpect(jsonPath("$.saldoDisponible").value(10000));
    }

    private List<Auditoria> rechazos(Usuario usuario) {
        return auditoriaRepository.findAll().stream()
                .filter(a -> a.getUsuario() != null && usuario.getId().equals(a.getUsuario().getId()))
                .filter(a -> a.getDetalle() != null && "RECHAZADA".equals(a.getDetalle().get("estado")))
                .toList();
    }

    private void sinSaldo(Usuario usuario) throws Exception {
        mockMvc.perform(get("/api/v1/billetera").header("Authorization", "Bearer " + token(usuario)))
                .andExpect(jsonPath("$.saldoDisponible").value(0));
        mockMvc.perform(get("/api/v1/billetera/movimientos").header("Authorization", "Bearer " + token(usuario)))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    private void cadenaVe(String hash, Transaccion transaccion, long bloqueActual) {
        when(cadena.transaccion(hash)).thenReturn(transaccion);
        when(cadena.bloqueActual()).thenReturn(bloqueActual);
    }

    // una carga exitosa como la emite el contrato desplegado
    private static Transaccion pagoDe(String referencia, String wei) {
        return new Transaccion.Minada(true, BLOQUE,
                List.of(new CargaRegistrada(CONTRATO.toLowerCase(), referencia, PAGADOR, new BigInteger(wei))));
    }

    private static String hashAlAzar() {
        return "0x" + (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }

    private static String referencia(ResultActions inicio) throws Exception {
        return JsonPath.read(inicio.andReturn().getResponse().getContentAsString(), "$.referencia");
    }

    private ResultActions confirmar(Usuario usuario, String referencia, String hash) throws Exception {
        return mockMvc.perform(post("/api/v1/billetera/cargas/cripto/{referencia}/confirmar", referencia)
                .header("Authorization", "Bearer " + token(usuario))
                .contentType(APPLICATION_JSON)
                .content("{\"hash\": \"" + hash + "\"}"));
    }

    private ResultActions iniciar(Usuario usuario, String monto) throws Exception {
        return mockMvc.perform(post("/api/v1/billetera/cargas/cripto")
                .header("Authorization", "Bearer " + token(usuario))
                .contentType(APPLICATION_JSON)
                .content("{\"monto\": " + monto + "}"));
    }

    // un participante con un nombre que no se repite entre tests
    private Usuario crearUsuario(String prefijo) {
        return escenario.usuario(prefijo + UUID.randomUUID().toString().substring(0, 8), "PARTICIPANTE");
    }

    private String token(Usuario usuario) {
        return jwtService.generarAccessToken(usuario.getId(), usuario.getUsername(), List.of("PARTICIPANTE"),
                List.of(), usuario.getVersionSesion());
    }
}
