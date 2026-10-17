package com.miniredis.storage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CacheStore {
	
	private final Map<String, String> cache = new ConcurrentHashMap<>();
	
	public void set(String key, String value) {
		cache.put(key, value);
	}
	
	public String get(String key) {
		return cache.get(key);
	}
	
	public boolean delete(String key) {
		return cache.remove(key) != null;
	}

}
