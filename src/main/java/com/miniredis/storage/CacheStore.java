package com.miniredis.storage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.miniredis.storage.CacheEntry;

public class CacheStore {
	
	private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
	
	public void set(String key, String value) {
		cache.put(key, new CacheEntry(value, null));
	}
	
	public String get(String key) {
		CacheEntry entry = cache.get(key);
		
		if(entry == null) {
			return null;
		}
		
		if(entry.isExpired()) {
			cache.remove(key,entry);
			return null;
		}
		
		return entry.getValue();
	}
	
	public boolean delete(String key) {
		CacheEntry entry = cache.remove(key);
		return entry != null && !entry.isExpired();
	}
	
	public boolean expire(String key, long seconds) {
		if(seconds < 0) {
			return false;
		}
		
		long expiresAt = System.currentTimeMillis() + seconds * 1000;
		
		final boolean[] updated = {false};
		
		cache.computeIfPresent(key, (k, entry) -> {
			if(entry.isExpired()) {
				return null;
			}
			updated[0] = true;
			
			return new CacheEntry(entry.getValue(), expiresAt);
		});
		
		return updated[0];
	}
	
	public long ttl(String key) {
		CacheEntry entry = cache.get(key);
		
		if(entry == null) {
			return -2;
		}
		
		if(entry.isExpired()) {
			cache.remove(key, entry);
			return -2;
		}
		
		if(entry.getExpiresAt() == null) {
			return -1;
		}
		
		long remaining = entry.getExpiresAt() - System.currentTimeMillis();
		
		if(remaining <= 0) {
			cache.remove(key, entry);
			return -2;
		}
		
		return (long) Math.ceil(remaining / 1000.0);
	}
}
