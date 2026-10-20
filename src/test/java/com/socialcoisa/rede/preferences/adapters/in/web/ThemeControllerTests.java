package com.socialcoisa.rede.preferences.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * O tema da conta: leitura, troca, limpeza e a recusa do que o backend nao conhece.
 * A escolha e individual e persistente — estes testes sao o contrato do par de
 * endpoints que a galeria de temas consome.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ThemeControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	/** O contrato de autenticacao da casa: id da conta + token de sessao no cabecalho. */
	private MockHttpServletRequestBuilder withSession(MockHttpServletRequestBuilder request,
			TestAuthHelper.AuthSession session) {
		return request.header("X-User-Id", session.userId()).header("X-Session-Token", session.token());
	}

	@Test
	void withoutPreferenceReturnsNull() throws Exception {
		var session = auth.authenticate("user_theme_empty");

		mockMvc.perform(withSession(get("/me/theme"), session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.theme").value(nullValue()));
	}

	@Test
	void setValidThemePersistsIt() throws Exception {
		var session = auth.authenticate("user_theme_set");

		mockMvc.perform(withSession(put("/me/theme"), session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"theme\":\"win95\"}"))
			.andExpect(status().isNoContent());

		mockMvc.perform(withSession(get("/me/theme"), session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.theme").value("win95"));

		// Troca de tema: o valor anterior e substituido, nao acumulado.
		mockMvc.perform(withSession(put("/me/theme"), session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"theme\":\"synthwave\"}"))
			.andExpect(status().isNoContent());

		mockMvc.perform(withSession(get("/me/theme"), session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.theme").value("synthwave"));
	}

	@Test
	void nullThemeClearsThePreference() throws Exception {
		var session = auth.authenticate("user_theme_clear");

		mockMvc.perform(withSession(put("/me/theme"), session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"theme\":\"terminal\"}"))
			.andExpect(status().isNoContent());

		mockMvc.perform(withSession(put("/me/theme"), session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"theme\":null}"))
			.andExpect(status().isNoContent());

		mockMvc.perform(withSession(get("/me/theme"), session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.theme").value(nullValue()));
	}

	@Test
	void unknownThemeIsRefused() throws Exception {
		var session = auth.authenticate("user_theme_invalid");

		mockMvc.perform(withSession(put("/me/theme"), session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"theme\":\"tema-inventado\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_THEME"));

		// A recusa nao grava nada: a preferencia continua inexistente.
		mockMvc.perform(withSession(get("/me/theme"), session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.theme").value(nullValue()));
	}

	/**
	 * Tema da galeria da comunidade como preferencia da conta.
	 *
	 * O id e "theme_" + UUID sem tracos, mais do que a coluna de 32 comportava: a validacao
	 * passava e o INSERT morria. Por isso o teste confere a volta pelo GET, nao so o 204.
	 */
	@Test
	void galleryThemeSurvivesTheAccountPreference() throws Exception {
		var author = auth.authenticate("user_theme_gallery");

		MvcResult created = mockMvc.perform(post("/temas/galeria")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "theme-pref-gallery")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Tema da Comunidade", "modeDefault", "light",
					"packageJson", "{\"palette\":{\"background\":\"#f5e6c8\",\"surface\":\"#f0e2c4\","
						+ "\"muted\":\"#e8d9b8\",\"border\":\"#d8c9a8\",\"primary\":\"#7c3aed\","
						+ "\"onPrimary\":\"#ffffff\",\"text\":{\"strong\":\"#3a2a10\","
						+ "\"subtle\":\"#5a4a30\",\"date\":\"#777777\"}}}"))))
			.andExpect(status().isOk())
			.andReturn();
		String themeId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
		assertThat(themeId.length()).isGreaterThan(32);

		mockMvc.perform(withSession(put("/me/theme"), author).contentType(MediaType.APPLICATION_JSON)
			.content("{\"theme\":\"" + themeId + "\"}"))
			.andExpect(status().isNoContent());

		mockMvc.perform(withSession(get("/me/theme"), author))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.theme").value(themeId));
	}

	@Test
	void requiresSession() throws Exception {
		mockMvc.perform(get("/me/theme")).andExpect(status().isUnauthorized());
		mockMvc.perform(put("/me/theme").contentType(MediaType.APPLICATION_JSON).content("{\"theme\":\"win95\"}"))
			.andExpect(status().isUnauthorized());
	}
}
