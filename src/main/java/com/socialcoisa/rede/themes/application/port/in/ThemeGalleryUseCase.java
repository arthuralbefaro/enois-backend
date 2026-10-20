package com.socialcoisa.rede.themes.application.port.in;

import java.util.List;

import com.socialcoisa.rede.themes.domain.ThemePackage;

public interface ThemeGalleryUseCase {

	ThemePackage publish(String userId, String name, String description, String packageJson, String modeDefault);

	/**
	 * Edita o tema do proprio autor mantendo o id: corrigir uma cor nao deve publicar um tema
	 * novo nem esconder o anterior, que e o que polui a galeria e zera a contagem de installs.
	 */
	ThemePackage update(String themeId, String userId, String name, String description, String packageJson,
		String modeDefault);

	List<ThemePackage> list(int limit);

	List<ThemePackage> mine(String userId);

	ThemePackage get(String id);

	/**
	 * Se o identificador e de um tema publicado na galeria.
	 *
	 * E o que permite a conta guardar um tema da comunidade como preferencia: esses ids
	 * nascem de UUID, entao nao cabem na lista estatica dos temas hardcoded
	 * ({@code ThemeIds.ALL}) e precisam ser conferidos no repositorio.
	 */
	boolean isPublished(String themeId);

	void report(String themeId);

	void install(String themeId);

	void hide(String themeId, String userId);
}
