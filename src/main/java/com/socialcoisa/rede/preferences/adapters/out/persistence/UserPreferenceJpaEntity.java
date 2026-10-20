package com.socialcoisa.rede.preferences.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.preferences.domain.UserPreference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_preferences")
class UserPreferenceJpaEntity {

	@Id
	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "theme_id", nullable = false, length = 64)
	private String themeId;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected UserPreferenceJpaEntity() {
	}

	UserPreferenceJpaEntity(String userId, String themeId, Instant updatedAt) {
		this.userId = userId;
		this.themeId = themeId;
		this.updatedAt = updatedAt;
	}

	static UserPreferenceJpaEntity fromDomain(UserPreference preference) {
		return new UserPreferenceJpaEntity(preference.userId(), preference.themeId(), preference.updatedAt());
	}

	UserPreference toDomain() {
		return new UserPreference(userId, themeId, updatedAt);
	}
}
