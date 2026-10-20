package com.socialcoisa.rede.themes.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.themes.application.port.in.ThemeGalleryUseCase;
import com.socialcoisa.rede.themes.application.port.out.ThemeGalleryRepository;
import com.socialcoisa.rede.themes.domain.ThemeGalleryStatus;
import com.socialcoisa.rede.themes.domain.ThemePackage;

/**
 * Galeria de temas da comunidade (Fase 2). O pacote publicado e JSON de TOKENS — a validacao
 * e estrita por schema (hex, enums, teto de tamanho); CSS livre, url() e imagens sao
 * rejeitados na porta. A cor de texto de cada modo e conferida contra o fundo pelo mesmo
 * calculo do auditor de contraste (>= 4.5:1) — tema ilegivel nao publica.
 */
@Service
public class ThemeGalleryService implements ThemeGalleryUseCase {

	private static final int MAX_PACKAGE_CHARS = 4096;
	private static final int MAX_DESCRIPTION_CHARS = 300;
	private static final Set<String> MODES = Set.of("light", "dark");
	private static final String HEX = "#[0-9a-fA-F]{6}";

	private final ThemeGalleryRepository themes;
	private final Clock clock;

	public ThemeGalleryService(ThemeGalleryRepository themes, Clock clock) {
		this.themes = themes;
		this.clock = clock;
	}

	/** Formas de cor permitidas: hex de 6 digitos, ou color-mix dos dois hex do pacote. */
	private static final java.util.regex.Pattern HEX_OK = java.util.regex.Pattern.compile("^" + HEX + "$");
	private static final java.util.regex.Pattern GRADIENT_OK = java.util.regex.Pattern
		.compile("^linear-gradient\\(\\d+deg, " + HEX + ", " + HEX + "\\)$");
	/** Raio sempre em px/rem e fonte sempre uma familia unica: nada de valor CSS cru. */
	private static final java.util.regex.Pattern RADIUS_OK = java.util.regex.Pattern
		.compile("^\\d+(\\.\\d+)?(px|rem)$");
	private static final java.util.regex.Pattern FONT_OK = java.util.regex.Pattern
		.compile("^[A-Za-z][A-Za-z0-9 _-]{0,63}$");

	/** As chaves do palette que o front consome — qualquer outra e recusada na porta. */
	private static final Set<String> PALETTE_KEYS = Set.of("background", "surface", "muted", "border", "input",
		"primary", "onPrimary", "soft", "secondary", "onSecondary", "accent", "onAccent", "ring", "gradient",
		"radius", "fontSans", "surfaceAlpha", "text");
	/** Sem derivacao segura: o front chama hexToRgb() nelas sem guarda. */
	private static final Set<String> REQUIRED_COLORS = Set.of("background", "surface", "muted", "border", "primary",
		"onPrimary");
	private static final Set<String> OPTIONAL_COLORS = Set.of("input", "soft", "secondary", "onSecondary", "accent",
		"onAccent", "ring");
	/** Validas para os temas hardcoded da colecao, mas CSS cru — fora da galeria. */
	private static final Set<String> RAW_CSS_KEYS = Set.of("shadows", "extra");

	@Override
	@Transactional
	public ThemePackage publish(String userId, String name, String description, String packageJson, String modeDefault) {
		requireName(name);
		requireDescription(description);
		requirePackageSize(packageJson);
		requireMode(modeDefault);
		validatePackageJson(packageJson);
		ThemePackage saved = themes.save(ThemePackage.create(
			"theme_" + UUID.randomUUID().toString().replace("-", ""), userId, name.trim(),
			StringUtils.hasText(description) ? description.trim() : null, packageJson, modeDefault,
			clock.instant()));
		return saved;
	}

	/**
	 * Edicao do tema ja publicado: so o autor, mesmo id, installs e data de publicacao
	 * preservados. O que o corpo nao traz fica como esta, e o que traz passa exatamente pelas
	 * mesmas recusas da publicacao — ajustar uma cor nao pode abrir a porta para o pacote que
	 * nao publicaria.
	 */
	@Override
	@Transactional
	public ThemePackage update(String themeId, String userId, String name, String description, String packageJson,
			String modeDefault) {
		ThemePackage theme = themes.findById(themeId)
			.orElseThrow(() -> new ApiException(404, "THEME_NOT_FOUND", "Tema nao encontrado na galeria."));
		if (!theme.isOwnedBy(userId)) {
			throw new ApiException(403, "NOT_AUTHORIZED", "So o autor pode editar o tema publicado.");
		}
		if (name != null) {
			requireName(name);
		}
		requireDescription(description);
		if (packageJson != null) {
			requirePackageSize(packageJson);
			validatePackageJson(packageJson);
		}
		if (modeDefault != null) {
			requireMode(modeDefault);
		}
		return themes.save(theme.edit(name != null ? name.trim() : null,
			description != null ? description.trim() : null, packageJson, modeDefault));
	}

	private void requireName(String name) {
		if (!StringUtils.hasText(name) || name.trim().length() < 3 || name.trim().length() > 80) {
			throw new ApiException(400, "INVALID_THEME_NAME", "Nome do tema precisa de 3 a 80 caracteres.");
		}
	}

	/** A coluna e varchar(300): sem esta guarda, o corpo longo estoura no banco e vira 500. */
	private void requireDescription(String description) {
		if (description != null && description.trim().length() > MAX_DESCRIPTION_CHARS) {
			throw new ApiException(400, "INVALID_THEME_DESCRIPTION",
				"Descricao do tema passa de " + MAX_DESCRIPTION_CHARS + " caracteres.");
		}
	}

	private void requirePackageSize(String packageJson) {
		if (!StringUtils.hasText(packageJson) || packageJson.length() > MAX_PACKAGE_CHARS) {
			throw new ApiException(400, "INVALID_THEME_PACKAGE",
				"Pacote do tema invalido ou grande demais (teto de " + MAX_PACKAGE_CHARS + " caracteres).");
		}
	}

	private void requireMode(String modeDefault) {
		if (!MODES.contains(modeDefault)) {
			throw new ApiException(400, "INVALID_THEME_MODE", "Modo padrao deve ser light ou dark.");
		}
	}

	@Override
	@Transactional(readOnly = true)
	public List<ThemePackage> list(int limit) {
		return themes.findPublished(limit);
	}

	@Override
	@Transactional(readOnly = true)
	public List<ThemePackage> mine(String userId) {
		return themes.findByAuthor(userId, 50);
	}

	@Override
	@Transactional(readOnly = true)
	public ThemePackage get(String id) {
		return themes.findById(id).filter(t -> t.status() == ThemeGalleryStatus.PUBLISHED)
			.orElseThrow(() -> new ApiException(404, "THEME_NOT_FOUND", "Tema nao encontrado na galeria."));
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isPublished(String themeId) {
		return themes.findById(themeId).filter(t -> t.status() == ThemeGalleryStatus.PUBLISHED).isPresent();
	}

	@Override
	@Transactional
	public void report(String themeId) {
		ThemePackage theme = themes.findById(themeId)
			.orElseThrow(() -> new ApiException(404, "THEME_NOT_FOUND", "Tema nao encontrado."));
		themes.save(theme.report());
	}

	@Override
	@Transactional
	public void install(String themeId) {
		ThemePackage theme = themes.findById(themeId)
			.filter(t -> t.status() == ThemeGalleryStatus.PUBLISHED)
			.orElseThrow(() -> new ApiException(404, "THEME_NOT_FOUND", "Tema nao encontrado."));
		themes.save(theme.addInstall());
	}

	@Override
	@Transactional
	public void hide(String themeId, String userId) {
		ThemePackage theme = themes.findById(themeId)
			.orElseThrow(() -> new ApiException(404, "THEME_NOT_FOUND", "Tema nao encontrado."));
		if (!theme.isOwnedBy(userId)) {
			throw new ApiException(403, "NOT_AUTHORIZED", "So o autor pode remover o tema da galeria.");
		}
		themes.save(theme.hide());
	}

	/**
	 * Validacao do pacote: o palette traz exatamente as chaves conhecidas, cores em hex de 6
	 * digitos, gradiente na forma permitida, e o contraste do texto do tema contra cada
	 * superficie onde ele aparece >= 4.5:1 (o mesmo calculo do auditor de contraste, aqui na
	 * porta).
	 *
	 * O contrato das chaves vive no front, em
	 * {@code src/types/preferences/theme-collection-palette-spec.ts}. As obrigatorias nao tem
	 * derivacao segura e as opcionais entram em {@code hexToRgb()} sem guarda — cor fora do
	 * formato nao degrada o tema, quebra a aplicacao inteira.
	 */
	private void validatePackageJson(String packageJson) {
		Map<?, ?> pkg;
		try {
			pkg = new tools.jackson.databind.ObjectMapper().readValue(packageJson, Map.class);
		}
		catch (Exception invalid) {
			throw new ApiException(400, "INVALID_THEME_PACKAGE", "Pacote nao e um JSON valido.");
		}
		rejectUnknown(pkg.keySet(), "pacote", Set.of("palette"));
		Object paletteObj = pkg.get("palette");
		if (!(paletteObj instanceof Map)) {
			throw new ApiException(400, "INVALID_THEME_PACKAGE", "Pacote precisa do objeto palette.");
		}
		Map<?, ?> palette = (Map<?, ?>) paletteObj;
		rejectUnknown(palette.keySet(), "palette", PALETTE_KEYS);

		for (String field : REQUIRED_COLORS) {
			checkColor(palette.get(field), field);
		}
		for (String field : OPTIONAL_COLORS) {
			if (palette.get(field) != null) {
				checkColor(palette.get(field), field);
			}
		}

		Object gradient = palette.get("gradient");
		if (gradient != null && !GRADIENT_OK.matcher(String.valueOf(gradient)).matches()) {
			throw new ApiException(400, "INVALID_THEME_PACKAGE", "Gradiente fora do formato permitido.");
		}
		Object radius = palette.get("radius");
		if (radius != null && !RADIUS_OK.matcher(String.valueOf(radius)).matches()) {
			throw new ApiException(400, "INVALID_THEME_PACKAGE", "Raio deve vir em px ou rem, ex.: 0.875rem.");
		}
		Object font = palette.get("fontSans");
		if (font != null && !FONT_OK.matcher(String.valueOf(font)).matches()) {
			throw new ApiException(400, "INVALID_THEME_PACKAGE",
				"Fonte deve ser uma unica familia, sem virgula, aspas ou ponto e virgula.");
		}
		double surfaceAlpha = surfaceAlphaOf(palette.get("surfaceAlpha"));

		Object text = palette.get("text");
		if (!(text instanceof Map)) {
			throw new ApiException(400, "INVALID_THEME_PACKAGE", "Pacote precisa do objeto text.");
		}
		Map<?, ?> textMap = (Map<?, ?>) text;
		rejectUnknown(textMap.keySet(), "text", Set.of("strong", "subtle", "date"));
		checkColor(textMap.get("strong"), "text.strong");
		checkColor(textMap.get("subtle"), "text.subtle");
		checkColor(textMap.get("date"), "text.date");

		// O texto nasce sobre o fundo, sobre o cartao e sobre o bloco alternativo — que e
		// tambem o --surface-2 do item selecionado da navegacao. Medir so contra o fundo e o
		// que deixava publicar tema com data e menu ilegiveis. A data fica de fora: o front
		// a ajusta contra a superficie real (textSafeAccent), entao ela nao depende do gate.
		String background = String.valueOf(palette.get("background"));
		String surface = overSurface(String.valueOf(palette.get("surface")), background, surfaceAlpha);
		String muted = String.valueOf(palette.get("muted"));
		requireContrast(textMap.get("strong"), background, "texto forte com o fundo");
		requireContrast(textMap.get("strong"), surface, "texto forte com o cartao");
		requireContrast(textMap.get("strong"), muted, "texto forte com o bloco alternativo");
		requireContrast(textMap.get("subtle"), background, "texto secundario com o fundo");
		requireContrast(textMap.get("subtle"), muted, "texto secundario com o bloco alternativo");
	}

	private void checkColor(Object value, String field) {
		if (value == null || !HEX_OK.matcher(String.valueOf(value)).matches()) {
			throw new ApiException(400, "INVALID_THEME_PACKAGE", "Cor invalida em " + field + ".");
		}
	}

	private void rejectUnknown(Set<?> keys, String where, Set<String> allowed) {
		for (Object key : keys) {
			String name = String.valueOf(key);
			if (RAW_CSS_KEYS.contains(name)) {
				throw new ApiException(400, "INVALID_THEME_PACKAGE",
					where + "." + name + " nao e aceito na galeria: CSS cru. Publique so tokens.");
			}
			if (allowed.contains(name)) {
				continue;
			}
			// Chave de cor na raiz do pacote e o erro mais provavel de quem publica por API, e o
			// front so le o que esta dentro de palette — sem o aviso, o autor configurava e via
			// a cor nao aparecer sem nenhuma explicacao.
			String hint = "pacote".equals(where) && PALETTE_KEYS.contains(name)
				? " Mova para dentro de palette."
				: "";
			throw new ApiException(400, "INVALID_THEME_PACKAGE",
				"Chave desconhecida em " + where + ": " + name + "." + hint);
		}
	}

	private double surfaceAlphaOf(Object value) {
		if (value == null) {
			return 1;
		}
		if (!(value instanceof Number) || ((Number) value).doubleValue() < 0
				|| ((Number) value).doubleValue() > 100) {
			throw new ApiException(400, "INVALID_THEME_PACKAGE", "surfaceAlpha deve ser um numero de 0 a 100.");
		}
		return ((Number) value).doubleValue() / 100;
	}

	/** Compoe a superficie translucida sobre o fundo: e contra a cor resultante que o texto conta. */
	private String overSurface(String hex, String backdropHex, double alpha) {
		if (alpha >= 1) {
			return hex;
		}
		double[] f = rgb(hex), b = rgb(backdropHex);
		StringBuilder mixed = new StringBuilder("#");
		for (int channel = 0; channel < 3; channel++) {
			mixed.append(String.format("%02x", Math.round(f[channel] * alpha + b[channel] * (1 - alpha))));
		}
		return mixed.toString();
	}

	private void requireContrast(Object foreground, Object background, String pair) {
		double ratio = contrastRatio(String.valueOf(foreground), String.valueOf(background));
		if (ratio < 4.5) {
			throw new ApiException(400, "THEME_CONTRAST_LOW", "O contraste do " + pair
					+ " precisa ser de pelo menos 4.5:1 (medido: " + (Math.round(ratio * 100) / 100) + ":1).");
		}
	}

	private double contrastRatio(String fg, String bg) {
		try {
			double[] f = rgb(fg), b = rgb(bg);
			return (Math.max(lum(f), lum(b)) + 0.05) / (Math.min(lum(f), lum(b)) + 0.05);
		}
		catch (Exception invalid) {
			return 0;
		}
	}

	private double lum(double[] c) {
		java.util.function.DoubleUnaryOperator f = (v) -> {
			v /= 255;
			return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
		};
		return 0.2126 * f.applyAsDouble(c[0]) + 0.7152 * f.applyAsDouble(c[1]) + 0.0722 * f.applyAsDouble(c[2]);
	}

	private double[] rgb(String hex) {
		String h = hex.replace("#", "");
		return new double[] { Integer.parseInt(h.substring(0, 2), 16), Integer.parseInt(h.substring(2, 4), 16),
			Integer.parseInt(h.substring(4, 6), 16) };
	}
}
