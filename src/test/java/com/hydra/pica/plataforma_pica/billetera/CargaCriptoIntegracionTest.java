package com.hydra.pica.plataforma_pica.billetera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import com.hydra.pica.plataforma_pica.billetera.domain.Billetera;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoBilletera;
import com.hydra.pica.plataforma_pica.billetera.repository.BilleteraRepository;
import com.hydra.pica.plataforma_pica.common.security.JwtService;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** La carga de saldo pagada con la wallet en Base Sepolia (HU-609, PICA-333). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class CargaCriptoIntegracionTest {

    private static final String CONTRATO = "0x36e633eDD1E79Ef2b9A3c6A099D873D52e44EDF0";

    @Autowired private MockMvc mockMvc;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private BilleteraRepository billeteraRepository;
    @Autowired private JwtService jwtService;

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

    private ResultActions iniciar(Usuario usuario, String monto) throws Exception {
        return mockMvc.perform(post("/api/v1/billetera/cargas/cripto")
                .header("Authorization", "Bearer " + token(usuario))
                .contentType(APPLICATION_JSON)
                .content("{\"monto\": " + monto + "}"));
    }

    private Usuario crearUsuario(String prefijo) {
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
        billetera.setSaldoDisponible(BigDecimal.ZERO);
        billetera.setSaldoRetenido(BigDecimal.ZERO);
        billetera.setGananciaAcumulada(BigDecimal.ZERO);
        billetera.setMoneda("ARS");
        billetera.setEstado(EstadoBilletera.ACTIVA);
        billeteraRepository.save(billetera);

        return usuario;
    }

    private String token(Usuario usuario) {
        return jwtService.generarAccessToken(usuario.getId(), usuario.getUsername(), List.of("PARTICIPANTE"),
                List.of(), usuario.getVersionSesion());
    }
}
