package com.socialcoisa.rede.preferences.application;

import java.time.Clock;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.preferences.application.port.out.UserPreferenceRepository;
import com.socialcoisa.rede.preferences.domain.ThemeIds;
import com.socialcoisa.rede.preferences.domain.UserPreference;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.themes.application.port.in.ThemeGalleryUseCase;

/**
 * O tema da conta: escolha individual, igual para todo mundo.
 *
 * O backend valida o identificador antes de guardar — tema desconhecido é recusa (400),
 * não silêncio — e guarda a escolha para ela acompanhar a pessoa entre dispositivos.
 * Validos: os temas hardcoded da coleção ({@link ThemeIds}) e os publicados na galeria da
 * comunidade, cujo id nasce de UUID e por isso não cabe numa lista estática.
 * Limpar ({@code null}) volta ao padrão da marca.
 */
@Service
public class ThemeService {

	private final UserPreferenceRepository repository;
	private final ThemeGalleryUseCase gallery;
	private final Clock clock;

	public ThemeService(UserPreferenceRepository repository, ThemeGalleryUseCase gallery, Clock clock) {
		this.repository = repository;
		this.gallery = gallery;
		this.clock = clock;
	}

	public Optional<String> themeOf(String userId) {
		return repository.find(userId).map(UserPreference::themeId);
	}

	/**
	 * A preferencia COM a data em que foi gravada.
	 *
	 * A data e o que permite resolver conflito entre o que esta no dispositivo e o que esta na
	 * conta: sem ela, o valor do servidor sempre vencia — e uma escolha feita quando o envio
	 * falhou (API fora, sessao expirando) voltava ao tema antigo a cada recarga.
	 */
	public Optional<UserPreference> preferenceOf(String userId) {
		return repository.find(userId);
	}

	public void setTheme(String userId, String themeId) {
		if (themeId == null || themeId.isBlank()) {
			repository.delete(userId);
			return;
		}
		String normalized = themeId.trim();
		if (!ThemeIds.ALL.contains(normalized) && !gallery.isPublished(normalized)) {
			throw new ApiException(400, "INVALID_THEME", "Tema desconhecido: escolha um dos temas disponiveis.");
		}
		repository.save(new UserPreference(userId, normalized, clock.instant()));
	}
}
