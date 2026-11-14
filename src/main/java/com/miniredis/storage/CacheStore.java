package com.miniredis.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.miniredis.persistence.AofManager;
import com.miniredis.persistence.AofRecord;
import com.miniredis.persistence.GenerationManager;
import com.miniredis.persistence.SnapshotData;
import com.miniredis.persistence.SnapshotManager;
import com.miniredis.storage.CacheEntry;

public class CacheStore {

	private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

	private final Clock clock;
	
	private final ReentrantReadWriteLock snapshotLock =
	        new ReentrantReadWriteLock();
	
	private AofManager aofManager;

	public CacheStore() {
		this(Clock.systemUTC());
	}

	public CacheStore(Clock clock) {
		this(clock, null);
	}
	
	public CacheStore(Clock clock, AofManager aofManager) {
		this.clock = clock;
		this.aofManager = aofManager;
	}
	
	// only for test
	void runWithOperationLock(Runnable action) {

	    snapshotLock.readLock().lock();

	    try {
	        action.run();
	    } finally {
	        snapshotLock.readLock().unlock();
	    }
	}

	public void set(String key, String value) throws IOException {

	    snapshotLock.writeLock().lock();

	    try {
	    	if(aofManager != null) {
	    		AofRecord record = new AofRecord(
	    				AofRecord.SET,
	    				key,
	    				value, null
	    			);
	    		aofManager.append(record);
	    	}
	    		cache.put(key, new CacheEntry(value, null));
	    		
	    } finally {
	        snapshotLock.writeLock().unlock();
	    }
	}

	public String get(String key) {
		snapshotLock.readLock().lock();
		
		try {
			CacheEntry entry = cache.get(key);			
			if (entry == null) {
				return null;
			}

			if (entry.isExpired(clock.millis())) {
				cache.remove(key, entry);
				return null;
			}

			return entry.getValue();
		} finally {
			snapshotLock.readLock().unlock();
		}
	}

	public boolean delete(String key) throws IOException {

	    snapshotLock.writeLock().lock();

	    try {
	    	CacheEntry entry = cache.get(key);
	    	
	    	if(entry == null || entry.isExpired(clock.millis())) {
	    		return false;
	    	}
	    	
	    	if(aofManager != null) {
	    		aofManager.append(AofRecord.delete(key));
	    	}
	    	
	    	cache.remove(key);
	    	return true;
	    } finally {
	    	snapshotLock.writeLock().unlock();
	    }
	}

	public boolean expire(String key, long seconds) throws IOException{

	    snapshotLock.writeLock().lock();

	    try {
	        if (seconds < 0) {
	            return false;
	        }

	        long milliseconds = Math.multiplyExact(seconds, 1000L);
	        long expiresAt = Math.addExact(clock.millis(), milliseconds);
	        
	        CacheEntry entry = cache.get(key);
	        
	        if(entry == null) {
	        	return false;
	        }
	        
	        if(entry.isExpired(clock.millis())) {
	        	cache.remove(key, entry);
	        	return false;
	        }
	        
	        if(aofManager != null) {
	        	aofManager.append(AofRecord.expire(key, expiresAt));
	        }
	        
	        cache.computeIfPresent(key, (k, currentEntry) -> new CacheEntry(currentEntry.getValue(), expiresAt));

	        return true;

	    } finally {
	        snapshotLock.writeLock().unlock();
	    }
	}

	public long ttl(String key) {

	    snapshotLock.readLock().lock();

	    try {
	        CacheEntry entry = cache.get(key);

	        if (entry == null) {
	            return -2;
	        }

	        if (entry.isExpired(clock.millis())) {
	            cache.remove(key, entry);
	            return -2;
	        }

	        if (entry.getExpiresAt() == null) {
	            return -1;
	        }

	        long remaining = entry.getExpiresAt() - clock.millis();

	        if (remaining <= 0) {
	            cache.remove(key, entry);
	            return -2;
	        }

	        return (long) Math.ceil(remaining / 1000.0);

	    } finally {
	        snapshotLock.readLock().unlock();
	    }
	}

	public long increment(String key) throws IOException {

	    snapshotLock.writeLock().lock();

	    try {
	        CacheEntry entry = cache.get(key);
	        long currentValue = 0;
	        Long expiresAt = null;
	        
	        if(entry != null && !entry.isExpired(clock.millis())) {
	        	currentValue = Long.parseLong(entry.getValue());
	        	expiresAt = entry.getExpiresAt();
	        }
	        
	        long newValue = Math.addExact(currentValue, 1);
	        
	        if(aofManager != null) {
	        	aofManager.append(AofRecord.set(key, String.valueOf(newValue), expiresAt));
	        }
	        
	        cache.put(key, new CacheEntry(String.valueOf(newValue), expiresAt));
	        
	        return newValue;

	    } finally {
	        snapshotLock.writeLock().unlock();
	    }
	}

	public Map<String, CacheEntry> snapshot() {

	    snapshotLock.writeLock().lock();

	    try {
	        Map<String, CacheEntry> copy = new HashMap<>();

	        long now = clock.millis();

	        cache.forEach((key, entry) -> {

	            if (!entry.isExpired(now)) {
	                copy.put(key, entry);
	            }
	        });

	        return copy;

	    } finally {
	        snapshotLock.writeLock().unlock();
	    }
	}

	public void restore(Map<String, CacheEntry> entries) {

	    snapshotLock.writeLock().lock();

	    try {
	        long now = clock.millis();
	        
	        cache.clear();

	        entries.forEach((key, entry) -> {

	            if (!entry.isExpired(now)) {
	                cache.put(key, entry);
	            }
	        });

	    } finally {
	        snapshotLock.writeLock().unlock();
	    }
	}
	
	public void replayAof(List<AofRecord> records) {
		snapshotLock.writeLock().lock();
		
		try {
			for(AofRecord record : records) {
				long now = clock.millis();
				switch(record.operation()) {
				case AofRecord.SET -> {
					Long expiresAt = record.expiresAt();
					
					if(expiresAt != null && expiresAt <= now) {
						cache.remove(record.key());
						break;
					}
					
					cache.put(record.key(), new CacheEntry(record.value(), expiresAt));
				}
				
				case AofRecord.DEL -> {
					cache.remove(record.key());
				}
				
				case AofRecord.EXPIRE -> {
					CacheEntry entry = cache.get(record.key());
					
					if(entry == null) {
						break;
					}
					
					long expiresAt = record.expiresAt();
					
					if(expiresAt <= now) {
						cache.remove(record.key());
					} else {
						cache.put(record.key(), new CacheEntry(entry.getValue(), expiresAt));
					}
				}
				
				default -> throw new IllegalArgumentException("Unknown AOF operation: " + record.operation());
				}
			}
		}finally {
			snapshotLock.writeLock().unlock();
		}
	}
	
	public SnapshotData createCheckpoint() throws IOException {
		snapshotLock.writeLock().lock();
		
		try {
			Map<String, CacheEntry> copy = new HashMap<>();
			
			long now = clock.millis();
			
			cache.forEach((key, entry) -> {
				if(!entry.isExpired(now)) {
					copy.put(key, entry);
				}
			});
			
			long offset = 0L;
			
			if(aofManager != null){
				offset = aofManager.getCurrentOffset();
			}
			
			return new SnapshotData(copy, offset);
			
		} finally {
			snapshotLock.writeLock().unlock();
		}
	}
	
	public void compact (GenerationManager generationManager) throws IOException {
		snapshotLock.writeLock().lock();
		try {
			Map<String, CacheEntry> entries = new HashMap<>();
			
			long now = clock.millis();
			
			cache.forEach((key, entry) -> {
				if(!entry.isExpired(now)) {
					entries.put(key, entry);
				}
			});
			
			int nextGeneration = generationManager.createNextGeneration();
			
			Path newAofPath = generationManager.getAofPath(nextGeneration);
			
			Path newSnapshotPath = generationManager.getSnapshotPath(nextGeneration);
			
			Files.createFile(newAofPath);
			
			SnapshotData snapshotData = new SnapshotData(entries, 0L);
			
			SnapshotManager newSnapshotManager = new SnapshotManager(newSnapshotPath);
			
			newSnapshotManager.save(snapshotData);
			
			generationManager.switchToGeneration(nextGeneration);
			
			this.aofManager = new AofManager(newAofPath);
			
			System.out.println("Compaction completed. ACtive generation: " + nextGeneration);
		} finally {
			snapshotLock.writeLock().unlock();
		}
	}
}
