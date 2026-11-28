package com.hydra.pica.plataforma_pica.billetera.service;

import java.time.Clock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;

import com.hydra.pica.plataforma_pica.billetera.repository.BilleteraRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.RetiroCriptoRepository;
import com.hydra.pica.plataforma_pica.common.config.CriptoConfig.CriptoProperties;
import com.hydra.pica.plataforma_pica.common.config.CriptoConfig.RetirosCriptoProperties;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** Sin el contrato o la clave del firmante configurados, los retiros a la wallet no se piden (HU-610). */
class RetiroCriptoServiceTest {

    @Test
    void sinFirmanteConfiguradoResponde503YNoTocaElSaldo() {
        FirmanteDeRetiros firmante = mock(FirmanteDeRetiros.class);
        when(firmante.habilitado()).thenReturn(false);
        RetiroService retiroService = mock(RetiroService.class);
        RetiroCriptoService servicio = new RetiroCriptoService(mock(RetiroCriptoRepository.class),
                mock(BilleteraRepository.class), mock(UsuarioRepository.class), retiroService, firmante,
                mock(LectorDeCadena.class), mock(CriptoProperties.class), mock(RetirosCriptoProperties.class),
                mock(TransactionTemplate.class), mock(AuditoriaService.class), Clock.systemUTC());

        assertThatThrownBy(() -> servicio.solicitar(1L, new BigDecimal("1000"),
                "0x262dfbfa37436e5b99a53377e633509d1a9b301a"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getCodigo())
                            .isEqualTo(CodigoError.RETIROS_CRIPTO_NO_DISPONIBLES);
                });
        verify(firmante).habilitado();
        verifyNoInteractions(retiroService);
    }

    @Test
    void laClaveDelFirmanteNoApareceAlImprimirLaConfiguracion() {
        RetirosCriptoProperties props = new RetirosCriptoProperties("0xabc", "0x59c6995e998f97a5",
                Duration.ofHours(1), Duration.ofMinutes(5));

        assertThat(props.toString())
                .doesNotContain("59c6995e")
                .contains("claveFirmante=***");
        assertThat(props.habilitados()).isTrue();
    }
}
