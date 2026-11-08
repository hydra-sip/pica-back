package com.hydra.pica.plataforma_pica.billetera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.billetera.domain.Billetera;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoBilletera;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.repository.BilleteraRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
import com.hydra.pica.plataforma_pica.billetera.service.CargaService;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.common.security.JwtService;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaRepository;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pruebas de integración de acreditación de carga e idempotencia concurrente contra PostgreSQL (HU-602, T602-2).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class CargaIntegracionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private CargaService cargaService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private BilleteraRepository billeteraRepository;
    @Autowired private MovimientoRepository movimientoRepository;
    @Autowired private AuditoriaRepository auditoriaRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("Endpoint POST /api/v1/billetera/cargas suma saldo, genera movimiento CARGA CONFIRMADO y audita")
    void cargaExitosaViaEndpointAcreditaSaldoYRegistraMovimiento() throws Exception {
        Usuario usuario = crearUsuarioConBilletera("usercargaendp", EstadoBilletera.ACTIVA, BigDecimal.ZERO);
        String token = generarToken(usuario);

        mockMvc.perform(post("/api/v1/billetera/cargas")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"monto\": 15000.50}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("CARGA"))
                .andExpect(jsonPath("$.monto").value(15000.50))
                .andExpect(jsonPath("$.saldoResultante").value(15000.50))
                .andExpect(jsonPath("$.estado").value("CONFIRMADO"))
                .andExpect(jsonPath("$.moneda").value("ARS"))
                .andExpect(jsonPath("$.referenciaExterna").exists());

        Billetera billeteraActualizada = billeteraRepository.findByUsuarioId(usuario.getId()).orElseThrow();
        assertThat(billeteraActualizada.getSaldoDisponible()).isEqualByComparingTo("15000.50");

        List<Auditoria> auditorias = auditoriaRepository.findAll().stream()
                .filter(a -> a.getUsuario() != null && usuario.getId().equals(a.getUsuario().getId()))
                .filter(a -> a.getAccion() == AccionAuditoria.PAGO)
                .toList();
        assertThat(auditorias).isNotEmpty();
    }

    @Test
    @DisplayName("Idempotencia secuencial: la misma referencia dos veces no duplica el saldo ni los movimientos")
    void idempotenciaSecuencialMismaReferenciaNoDuplicaSaldo() {
        Usuario usuario = crearUsuarioConBilletera("usercargasec", EstadoBilletera.ACTIVA, new BigDecimal("1000.00"));
        String referencia = "ref-sec-" + UUID.randomUUID();

        Movimiento mov1 = cargaService.acreditar(
                usuario.getId(),
                new BigDecimal("5000.00"),
                referencia,
                "Primera acreditación");

        assertThat(mov1.getId()).isNotNull();
        assertThat(mov1.getSaldoResultante()).isEqualByComparingTo("6000.00");
        assertThat(mov1.getEstado()).isEqualTo(EstadoMovimiento.CONFIRMADO);

        Billetera bIntermedia = billeteraRepository.findByUsuarioId(usuario.getId()).orElseThrow();
        assertThat(bIntermedia.getSaldoDisponible()).isEqualByComparingTo("6000.00");

        // Reintento con la misma referencia
        Movimiento mov2 = cargaService.acreditar(
                usuario.getId(),
                new BigDecimal("5000.00"),
                referencia,
                "Reintento de acreditación");

        assertThat(mov2.getId()).isEqualTo(mov1.getId());
        assertThat(mov2.getSaldoResultante()).isEqualByComparingTo("6000.00");

        Billetera bFinal = billeteraRepository.findByUsuarioId(usuario.getId()).orElseThrow();
        assertThat(bFinal.getSaldoDisponible()).isEqualByComparingTo("6000.00");
    }

    @Test
    @DisplayName("Idempotencia concurrente con 8 hilos en paralelo: exactamente una sola acreditación")
    void idempotenciaConcurrenteConMultiplesHilosAcreditaUnaSolaVez() throws Exception {
        Usuario usuario = crearUsuarioConBilletera("usercargaconc", EstadoBilletera.ACTIVA, BigDecimal.ZERO);
        String referencia = "ref-conc-" + UUID.randomUUID();
        BigDecimal monto = new BigDecimal("2500.00");

        int hilos = 8;
        ExecutorService executor = Executors.newFixedThreadPool(hilos);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(hilos);
        List<Future<Movimiento>> futures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < hilos; i++) {
            futures.add(executor.submit(() -> {
                startGate.await(); // Esperar el disparo simultáneo
                try {
                    return cargaService.acreditar(usuario.getId(), monto, referencia, "Carga concurrente");
                } finally {
                    endGate.countDown();
                }
            }));
        }

        startGate.countDown(); // Disparar todos los hilos simultáneamente
        boolean completaron = endGate.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completaron).isTrue();

        List<Long> idsResultantes = new ArrayList<>();
        for (Future<Movimiento> f : futures) {
            Movimiento mov = f.get();
            assertThat(mov).isNotNull();
            idsResultantes.add(mov.getId());
        }

        // Todos los hilos deben haber obtenido exactamente el mismo movimiento
        Long primerId = idsResultantes.getFirst();
        assertThat(idsResultantes).allMatch(id -> id.equals(primerId));

        // El saldo final debe haber incrementado EXACTAMENTE una vez (2500.00)
        Billetera billeteraFinal = billeteraRepository.findByUsuarioId(usuario.getId()).orElseThrow();
        assertThat(billeteraFinal.getSaldoDisponible()).isEqualByComparingTo("2500.00");

        // En la base debe existir un único movimiento con esa referencia
        List<Movimiento> movimientos = movimientoRepository.findAll().stream()
                .filter(m -> referencia.equals(m.getReferenciaExterna()))
                .toList();
        assertThat(movimientos).hasSize(1);
    }

    @Test
    @DisplayName("Carga rechazada: no toca el saldo de la billetera y deja auditoría con estado RECHAZADA")
    void cargaRechazadaNoModificaSaldoYQuedaEnAuditoria() {
        Usuario usuario = crearUsuarioConBilletera("usercargarech", EstadoBilletera.ACTIVA, new BigDecimal("7000.00"));
        String referencia = "ref-rechazo-" + UUID.randomUUID();

        cargaService.registrarRechazo(
                usuario.getId(),
                new BigDecimal("3000.00"),
                referencia,
                "Tarjeta rechazada por fondos insuficientes");

        Billetera billetera = billeteraRepository.findByUsuarioId(usuario.getId()).orElseThrow();
        assertThat(billetera.getSaldoDisponible()).isEqualByComparingTo("7000.00");

        List<Auditoria> auditorias = auditoriaRepository.findAll().stream()
                .filter(a -> a.getUsuario() != null && usuario.getId().equals(a.getUsuario().getId()))
                .filter(a -> a.getDetalle() != null && "RECHAZADA".equals(a.getDetalle().get("estado")))
                .toList();

        assertThat(auditorias).isNotEmpty();
        Auditoria rechazo = auditorias.getFirst();
        assertThat(rechazo.getDetalle().get("referencia")).isEqualTo(referencia);
        assertThat(rechazo.getDetalle().get("motivo")).isEqualTo("Tarjeta rechazada por fondos insuficientes");
    }

    @Test
    @DisplayName("Validación de montos: 0 o negativos devuelven 400 VALIDACION")
    void montosInvalidosResponden400() throws Exception {
        Usuario usuario = crearUsuarioConBilletera("usercargainval", EstadoBilletera.ACTIVA, BigDecimal.ZERO);
        String token = generarToken(usuario);

        mockMvc.perform(post("/api/v1/billetera/cargas")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"monto\": 0.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));

        mockMvc.perform(post("/api/v1/billetera/cargas")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"monto\": -500.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));

        assertThatThrownBy(() -> cargaService.acreditar(usuario.getId(), BigDecimal.ZERO, "ref-zero", "desc"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("El monto debe ser mayor a cero");
    }

    @Test
    @DisplayName("Billetera bloqueada: rechaza acreditación con 403 BILLETERA_BLOQUEADA")
    void billeteraBloqueadaRechazaCon403() {
        Usuario usuario = crearUsuarioConBilletera("usercargabloq", EstadoBilletera.BLOQUEADA, new BigDecimal("1000.00"));

        assertThatThrownBy(() -> cargaService.acreditar(
                usuario.getId(),
                new BigDecimal("500.00"),
                "ref-bloq-" + UUID.randomUUID(),
                "Intento sobre bloqueada"))
                .isInstanceOf(ProhibidoException.class)
                .satisfies(ex -> {
                    ProhibidoException pe = (ProhibidoException) ex;
                    assertThat(pe.getCodigo()).isEqualTo(CodigoError.BILLETERA_BLOQUEADA);
                });

        Billetera billetera = billeteraRepository.findByUsuarioId(usuario.getId()).orElseThrow();
        assertThat(billetera.getSaldoDisponible()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("Referencia ya utilizada por otro usuario rechaza con conflicto CLAVE_IDEMPOTENCIA_USADA")
    void referenciaDeOtroUsuarioLanzaConflicto() {
        Usuario userA = crearUsuarioConBilletera("usercargaa", EstadoBilletera.ACTIVA, BigDecimal.ZERO);
        Usuario userB = crearUsuarioConBilletera("usercargab", EstadoBilletera.ACTIVA, BigDecimal.ZERO);
        String referencia = "ref-compartida-" + UUID.randomUUID();

        cargaService.acreditar(userA.getId(), new BigDecimal("1000.00"), referencia, "Carga user A");

        assertThatThrownBy(() -> cargaService.acreditar(
                userB.getId(),
                new BigDecimal("1000.00"),
                referencia,
                "Carga user B"))
                .isInstanceOf(ConflictoException.class)
                .satisfies(ex -> {
                    ConflictoException ce = (ConflictoException) ex;
                    assertThat(ce.getCodigo()).isEqualTo(CodigoError.CLAVE_IDEMPOTENCIA_USADA);
                });
    }

    @Test
    @DisplayName("Validación de montos: excede el límite máximo permitido (1.000.000,00) devuelve 400 VALIDACION")
    void montoExcedeMaximoResponde400() throws Exception {
        Usuario usuario = crearUsuarioConBilletera("usercargamax", EstadoBilletera.ACTIVA, BigDecimal.ZERO);
        String token = generarToken(usuario);

        mockMvc.perform(post("/api/v1/billetera/cargas")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"monto\": 1000000.01}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));
    }

    @Test
    @DisplayName("Referencia existente vinculada a un movimiento que no es CARGA lanza CLAVE_IDEMPOTENCIA_USADA")
    void referenciaDeOtroTipoMovimientoLanzaConflicto() {
        Usuario usuario = crearUsuarioConBilletera("usercarganocarga", EstadoBilletera.ACTIVA, BigDecimal.ZERO);
        Billetera billetera = billeteraRepository.findByUsuarioId(usuario.getId()).orElseThrow();
        String referencia = "ref-premio-" + UUID.randomUUID();

        Movimiento movPremio = new Movimiento();
        movPremio.setBilletera(billetera);
        movPremio.setTipo(TipoMovimiento.PREMIO);
        movPremio.setMonto(new BigDecimal("500.00"));
        movPremio.setMoneda("ARS");
        movPremio.setSaldoResultante(new BigDecimal("500.00"));
        movPremio.setEstado(EstadoMovimiento.CONFIRMADO);
        movPremio.setReferenciaExterna(referencia);
        movPremio.setDescripcion("Premio torneo");
        movimientoRepository.save(movPremio);

        assertThatThrownBy(() -> cargaService.acreditar(
                usuario.getId(),
                new BigDecimal("500.00"),
                referencia,
                "Intento de carga con referencia de premio"))
                .isInstanceOf(ConflictoException.class)
                .satisfies(ex -> {
                    ConflictoException ce = (ConflictoException) ex;
                    assertThat(ce.getCodigo()).isEqualTo(CodigoError.CLAVE_IDEMPOTENCIA_USADA);
                });
    }

    @Test
    @DisplayName("Concurrencia entre distintos usuarios con la misma referencia: uno acredita y el otro falla con CLAVE_IDEMPOTENCIA_USADA")
    void concurrenciaDistintosUsuariosMismaReferenciaUnoExitosoYUnoConflicto() throws Exception {
        Usuario userA = crearUsuarioConBilletera("usercargadistA", EstadoBilletera.ACTIVA, BigDecimal.ZERO);
        Usuario userB = crearUsuarioConBilletera("usercargadistB", EstadoBilletera.ACTIVA, BigDecimal.ZERO);
        String referencia = "ref-cross-" + UUID.randomUUID();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(2);

        List<Throwable> errores = Collections.synchronizedList(new ArrayList<>());
        List<Movimiento> exitosos = Collections.synchronizedList(new ArrayList<>());

        executor.submit(() -> {
            try {
                startGate.await();
                exitosos.add(cargaService.acreditar(userA.getId(), new BigDecimal("1000.00"), referencia, "Carga user A"));
            } catch (Throwable t) {
                errores.add(t);
            } finally {
                endGate.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startGate.await();
                exitosos.add(cargaService.acreditar(userB.getId(), new BigDecimal("1000.00"), referencia, "Carga user B"));
            } catch (Throwable t) {
                errores.add(t);
            } finally {
                endGate.countDown();
            }
        });

        startGate.countDown();
        boolean completaron = endGate.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completaron).isTrue();
        assertThat(exitosos).hasSize(1);
        assertThat(errores).hasSize(1);

        Throwable error = errores.getFirst();
        assertThat(error).isInstanceOf(ConflictoException.class);
        assertThat(((ConflictoException) error).getCodigo()).isEqualTo(CodigoError.CLAVE_IDEMPOTENCIA_USADA);
    }

    @Test
    @DisplayName("registrarRechazo persiste la auditoría en transacción autónoma incluso si la transacción externa hace rollback")
    void registrarRechazoPersisteAuditoriaAunqueTransaccionExternaHagaRollback() {
        Usuario usuario = crearUsuarioConBilletera("usercargatxrech", EstadoBilletera.ACTIVA, new BigDecimal("2000.00"));
        String referencia = "ref-tx-rechazo-" + UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.execute(status -> {
            cargaService.registrarRechazo(
                    usuario.getId(),
                    new BigDecimal("4000.00"),
                    referencia,
                    "Rechazo simulado");
            throw new RuntimeException("Forzar rollback de transacción externa");
        })).isInstanceOf(RuntimeException.class).hasMessageContaining("Forzar rollback");

        List<Auditoria> auditorias = auditoriaRepository.findAll().stream()
                .filter(a -> a.getUsuario() != null && usuario.getId().equals(a.getUsuario().getId()))
                .filter(a -> a.getDetalle() != null && referencia.equals(a.getDetalle().get("referencia")))
                .toList();

        assertThat(auditorias).hasSize(1);
        assertThat(auditorias.getFirst().getDetalle().get("motivo")).isEqualTo("Rechazo simulado");
    }

    private Usuario crearUsuarioConBilletera(String prefijo, EstadoBilletera estadoBilletera, BigDecimal saldoInicial) {
        String username = prefijo + UUID.randomUUID().toString().substring(0, 5);

        Persona persona = new Persona();
        persona.setNombres("Nombre");
        persona.setApellidos("Apellido");
        persona.setEstado(EstadoGeneral.ACTIVO);
        persona = personaRepository.save(persona);

        Usuario usuario = new Usuario();
        usuario.setUsername(username);
        usuario.setEmail(username + "@example.com");
        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setEmailVerificado(true);
        usuario.setPersona(persona);
        usuario = usuarioRepository.save(usuario);

        Billetera billetera = new Billetera();
        billetera.setUsuario(usuario);
        billetera.setSaldoDisponible(saldoInicial);
        billetera.setSaldoRetenido(BigDecimal.ZERO);
        billetera.setGananciaAcumulada(BigDecimal.ZERO);
        billetera.setMoneda("ARS");
        billetera.setEstado(estadoBilletera);
        billeteraRepository.save(billetera);

        return usuario;
    }

    private String generarToken(Usuario usuario) {
        return jwtService.generarAccessToken(
                usuario.getId(),
                usuario.getUsername(),
                List.of("PARTICIPANTE"),
                List.of(),
                usuario.getVersionSesion());
    }
}

