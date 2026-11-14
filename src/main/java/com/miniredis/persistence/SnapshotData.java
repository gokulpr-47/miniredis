package com.miniredis.persistence;

import java.util.Map;

import com.miniredis.storage.CacheEntry;

public record SnapshotData (
	Map<String, CacheEntry> entries, long offset
) {}
