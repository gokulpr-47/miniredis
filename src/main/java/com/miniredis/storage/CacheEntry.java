package com.miniredis.storage;

public class CacheEntry {
	private final String value;
	private final Long expiresAt;
	
	public CacheEntry(String value, Long expiresAt) {
		this.value = value;
		this.expiresAt = expiresAt;
	}
	
	public String getValue() {
		return value;
	}
	
	public Long getExpiresAt() {
		return expiresAt;
	}
	
	public boolean isExpired() {
		return expiresAt != null && System.currentTimeMillis() >= expiresAt;
	}
}
