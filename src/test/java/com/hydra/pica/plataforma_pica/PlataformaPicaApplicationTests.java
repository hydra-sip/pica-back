package com.hydra.pica.plataforma_pica;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("dev")
class PlataformaPicaApplicationTests {

	@Test
	void contextLoads() {
	}

}
