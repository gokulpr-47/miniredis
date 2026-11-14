package com.miniredis.persistence;

public record AofRecord(
		byte operation,
		String key,
		String value,
		Long expiresAt) {
	
	public static final byte SET = 1;
	public static final byte DEL = 2;
	public static final byte EXPIRE = 3;
	
	public static AofRecord set (String key, String value, Long expiresAt) {
		return new AofRecord(SET, key, value, expiresAt);
	}
	
	public static AofRecord delete(String key) {
		return new AofRecord(DEL,key, null, null);
	}
	
	public static AofRecord expire(String key, long expiresAt) {
		return new AofRecord(EXPIRE, key, null, expiresAt);
	}
}
