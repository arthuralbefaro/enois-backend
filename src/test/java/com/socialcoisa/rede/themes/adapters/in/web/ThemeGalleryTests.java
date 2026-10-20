package com.socialcoisa.rede.themes.adapters.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Galeria de temas da comunidade: publicar (validacao de schema E de contraste >= 4.5:1),
 * listagem publica, instalar conta visita, denunciar. Tema ilegivel NAO publica.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ThemeGalleryTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(name);
	}

	private static String pkg(String bg, String strong) {
		return "{\"palette\":{\"background\":\"" + bg + "\",\"surface\":\"" + bg
			+ "\",\"muted\":\"" + bg + "\",\"border\":\"" + bg
			+ "\",\"primary\":\"#7c3aed\",\"onPrimary\":\"#ffffff\",\"soft\":\"#ede8f5\",\"text\":{\"strong\":\""
			+ strong + "\",\"subtle\":\"#555555\",\"date\":\"#777777\"},\"radius\":\"8px\"}}";
	}

	private org.springframework.test.web.servlet.ResultActions publish(TestAuthHelper.AuthSession author, String key,
			String name, String packageJson) throws Exception {
		return mockMvc.perform(post("/temas/galeria").header("X-Session-Token", author.token())
			.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("name", name, "modeDefault", "light", "packageJson",
				packageJson))));
	}

	private org.springframework.test.web.servlet.ResultActions patchTheme(TestAuthHelper.AuthSession author,
			String themeId, Map<String, ?> body) throws Exception {
		return mockMvc.perform(patch("/temas/galeria/" + themeId).header("X-Session-Token", author.token())
			.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
	}

	private String publishId(TestAuthHelper.AuthSession author, String key, String name, String packageJson)
			throws Exception {
		return objectMapper.readTree(publish(author, key, name, packageJson).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).get("id").asText();
	}

	/** Paleta clara completa: o suficiente para passar no schema e chegar so ao gate de contraste. */
	private static final String LIGHT = "\"background\":\"#f5e6c8\",\"surface\":\"#f0e2c4\",\"muted\":\"#e8d9b8\","
		+ "\"border\":\"#d8c9a8\",\"primary\":\"#7c3aed\",\"onPrimary\":\"#ffffff\","
		+ "\"text\":{\"strong\":\"#3a2a10\",\"subtle\":\"#5a4a30\",\"date\":\"#777777\"}";

	@Test
	void paletteContractIsEnforcedOnPublish() throws Exception {
		var author = auth("tgal_contract");

		// radius na raiz nao e lido por ninguem — o front consome so o que esta dentro de
		// palette. Recusar com o caminho certo vale mais que publicar um tema cuja cor
		// sumiria sem explicacao nenhuma.
		publish(author, "tgal-c-root", "Radius Solto",
			"{\"palette\":{" + LIGHT + "},\"radius\":\"8px\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value(containsString("Mova para dentro de palette")));

		// muted nao e cor opcional: o front chama hexToRgb() nela sem guarda, entao a falta
		// quebra a aplicacao inteira em vez de degradar so um bloco.
		publish(author, "tgal-c-muted", "Sem Muted",
			"{\"palette\":{\"background\":\"#f5e6c8\",\"surface\":\"#f0e2c4\",\"border\":\"#d8c9a8\","
				+ "\"primary\":\"#7c3aed\",\"onPrimary\":\"#ffffff\","
				+ "\"text\":{\"strong\":\"#3a2a10\",\"subtle\":\"#5a4a30\",\"date\":\"#777777\"}}}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value(containsString("Cor invalida em muted")));

		// extra e CSS cru, e ia direto para o <style> do navegador.
		publish(author, "tgal-c-extra", "Com CSS Cru",
			"{\"palette\":{" + LIGHT + ",\"extra\":{\"--card\":\"url(evil)\"}}}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value(containsString("CSS cru")));

		// O buraco que publicava tema ilegiveis: o texto forte passa contra o FUNDO e contra o
		// CARTAO, mas nao contra o bloco alternativo — que e o --surface-2 do item selecionado
		// da navegacao lateral. So o gate antigo deixava esse passar.
		publish(author, "tgal-c-surface", "Menu Ilegivel",
			"{\"palette\":{\"background\":\"#2b2a33\",\"surface\":\"#3a3945\",\"muted\":\"#f2f0e8\","
				+ "\"border\":\"#4a4859\",\"primary\":\"#bd93f9\",\"onPrimary\":\"#282a36\","
				+ "\"text\":{\"strong\":\"#f8f8f2\",\"subtle\":\"#c8c6d2\",\"date\":\"#b0aec0\"}}}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("THEME_CONTRAST_LOW"))
			.andExpect(jsonPath("$.message").value(containsString("bloco alternativo")));
	}

	@Test
	void publishListInstallAndReport() throws Exception {
		var author = auth("tgal_author");

		MvcResult created = mockMvc.perform(post("/temas/galeria")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "tgal-pub-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", "Tema Bonito", "description", "tema de teste", "modeDefault", "light",
					"packageJson", pkg("#f5e6c8", "#3a2a10")))))
			.andExpect(status().isOk())
			.andReturn();
		String themeId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(get("/temas/galeria?limit=30"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + themeId + "')].name").value("Tema Bonito"));

		mockMvc.perform(post("/temas/galeria/" + themeId + "/install"))
			.andExpect(status().isOk());

		mockMvc.perform(post("/temas/galeria/" + themeId + "/report"))
			.andExpect(status().isOk());

		// Pacote invalido (url no lugar de cor) e rejeitado na porta.
		mockMvc.perform(post("/temas/galeria")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "tgal-invalid")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", "Tema Ruim", "modeDefault", "light",
					"packageJson", "{\"palette\":{\"background\":\"url(evil)\",\"text\":{\"strong\":\"#000\"}}}"))))
			.andExpect(status().isBadRequest());
	}

	@Test
	void lowContrastThemeIsRejected() throws Exception {
		var author = auth("tgal_author2");

		// Texto quase-branco sobre fundo quase-branco: o gate de contraste recusa.
		mockMvc.perform(post("/temas/galeria")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "tgal-lowc")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", "Tema Invisivel", "modeDefault", "light",
					"packageJson", pkg("#f8f8f8", "#f0f0f0")))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("THEME_CONTRAST_LOW"));
	}

	@Test
	void anonymousCanListButNotPublish() throws Exception {
		mockMvc.perform(get("/temas/galeria?limit=5"))
			.andExpect(status().isOk());

		mockMvc.perform(post("/temas/galeria")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Anon", "modeDefault", "light",
					"packageJson", pkg("#f5e6c8", "#3a2a10")))))
			.andExpect(status().isUnauthorized());
	}

	/**
	 * Ajustar um tema publicado nao deve publicar um tema novo e esconder o anterior — o que
	 * polui a galeria e quebra a contagem de installs de quem ja o usa.
	 */
	@Test
	void authorPatchesThePublishedThemeKeepingIdAndInstalls() throws Exception {
		var author = auth("tgal_patch");
		String themeId = publishId(author, "tgal-patch-1", "Tema Patch", pkg("#f5e6c8", "#3a2a10"));

		mockMvc.perform(post("/temas/galeria/" + themeId + "/install")).andExpect(status().isOk());
		mockMvc.perform(post("/temas/galeria/" + themeId + "/install")).andExpect(status().isOk());

		String novo = pkg("#eef3f7", "#1e2a36");
		patchTheme(author, themeId, Map.of("packageJson", novo, "description", "ajeitei o azul"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(themeId))
			.andExpect(jsonPath("$.name").value("Tema Patch"))
			.andExpect(jsonPath("$.description").value("ajeitei o azul"))
			.andExpect(jsonPath("$.installs").value(2))
			.andExpect(jsonPath("$.packageJson").value(novo));

		// O mesmo id, com o pacote novo — quem instalou antes recebe a correcao sem reinstalar.
		mockMvc.perform(get("/temas/galeria/" + themeId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.packageJson").value(novo))
			.andExpect(jsonPath("$.installs").value(2));
	}

	@Test
	void patchKeepsWhatTheBodyOmitsAndRevalidatesWhatItSends() throws Exception {
		var author = auth("tgal_patch2");
		String original = pkg("#f5e6c8", "#3a2a10");
		String themeId = publishId(author, "tgal-patch2-1", "Tema Omissao", original);

		// corpo vazio nao e motivo para destruir nada
		patchTheme(author, themeId, Map.of()).andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Tema Omissao"))
			.andExpect(jsonPath("$.packageJson").value(original));

		// so o nome: a paleta continua a mesma publicada
		patchTheme(author, themeId, Map.of("name", "Tema Renomeado")).andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Tema Renomeado"))
			.andExpect(jsonPath("$.packageJson").value(original));

		// o pacote revalidado na porta com as mesmas recusas da publicacao
		patchTheme(author, themeId, Map.of("packageJson", "{\"palette\":{\"background\":\"url(evil)\"}}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_THEME_PACKAGE"));
		patchTheme(author, themeId, Map.of("packageJson", pkg("#f8f8f8", "#f0f0f0")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("THEME_CONTRAST_LOW"));
		patchTheme(author, themeId, Map.of("name", "ab")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_THEME_NAME"));
		patchTheme(author, themeId, Map.of("modeDefault", "neon")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_THEME_MODE"));
		// a coluna e varchar(300): corpo maior que isso estouraria no banco e viraria 500
		patchTheme(author, themeId, Map.of("description", "d".repeat(301))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_THEME_DESCRIPTION"));
		mockMvc.perform(post("/temas/galeria").header("X-Session-Token", author.token())
			.header("Idempotency-Key", "tgal-patch2-desc").contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("name", "Descricao Cumprida", "modeDefault", "light",
				"description", "d".repeat(301), "packageJson", pkg("#f5e6c8", "#3a2a10")))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_THEME_DESCRIPTION"));

		// nenhuma das recusas tocou o que esta publicado
		mockMvc.perform(get("/temas/galeria/" + themeId))
			.andExpect(jsonPath("$.name").value("Tema Renomeado"))
			.andExpect(jsonPath("$.packageJson").value(original))
			.andExpect(jsonPath("$.modeDefault").value("light"));
	}

	@Test
	void onlyTheAuthorCanPatch() throws Exception {
		var author = auth("tgal_patch3");
		var stranger = auth("tgal_patch3_outsider");
		String themeId = publishId(author, "tgal-patch3-1", "Tema Fechado", pkg("#f5e6c8", "#3a2a10"));

		patchTheme(stranger, themeId, Map.of("name", "Sequestrado")).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_AUTHORIZED"));

		mockMvc.perform(patch("/temas/galeria/" + themeId).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Sem Sessao\"}")).andExpect(status().isUnauthorized());

		mockMvc.perform(get("/temas/galeria/" + themeId))
			.andExpect(jsonPath("$.name").value("Tema Fechado"));

		// theme inexistente: 404, nao 403 — a edicao nao existe sem o tema
		patchTheme(author, "theme_nao_existe", Map.of("name", "Fantasma")).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("THEME_NOT_FOUND"));
	}
}
