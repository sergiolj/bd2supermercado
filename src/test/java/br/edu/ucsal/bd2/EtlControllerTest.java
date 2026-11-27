package br.edu.ucsal.bd2;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import br.edu.ucsal.bd2.controller.EtlController;
import br.edu.ucsal.bd2.dto.response.EtlRunResponse;
import br.edu.ucsal.bd2.service.EtlService;

// Testa só a camada web. O ETL em si é testado contra o PostgreSQL em EtlIntegrationTest.
@WebMvcTest(EtlController.class)
class EtlControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private EtlService service;

	@Test
	@DisplayName("Should run an incremental load by default and return the row counts")
	void shouldRunIncrementalByDefault() throws Exception {
		when(service.run(false)).thenReturn(new EtlRunResponse(7L, "INCREMENTAL", 120L, 3, 3, 0,
				List.of(new EtlRunResponse.TableCount("dw_estrela.fato_venda", 10L, 13L))));

		mockMvc.perform(post("/api/etl/run"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tipo").value("INCREMENTAL"))
				.andExpect(jsonPath("$.fatosInseridos").value(3))
				.andExpect(jsonPath("$.tabelas[0].tabela").value("dw_estrela.fato_venda"))
				.andExpect(jsonPath("$.tabelas[0].antes").value(10))
				.andExpect(jsonPath("$.tabelas[0].depois").value(13));
		verify(service).run(false);
	}

	@Test
	@DisplayName("Should run a full reload when completa=true")
	void shouldRunFullReload() throws Exception {
		when(service.run(true)).thenReturn(new EtlRunResponse(8L, "COMPLETA", 900L, 13, 13, 0, List.of()));

		mockMvc.perform(post("/api/etl/run").param("completa", "true"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tipo").value("COMPLETA"));
		verify(service).run(true);
	}
}
