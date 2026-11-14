package com.miniredis.storage;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.miniredis.persistence.SnapshotManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CacheStoreTest {
	private CacheStore store;
	
	@BeforeEach
	void setUp() {
		store = new CacheStore();
	}
	
	@Test
	void shouldStoreAndRetrieveValue() {
		store.set("name", "gokul");
		assertEquals("gokul", store.get("name"));
	}
	
	@Test
	void shouldReturnNullForMissingKey() {
		String result = store.get("unknown");
		assertNull(result);
	}
	
	@Test
	void shouldDeleteExistingKey() {
		store.set("city", "Bangalore");
		boolean deleted = store.delete("city");
		
		assertTrue(deleted);
		assertNull(store.get("city"));
	}
	
	@Test
	void updateExistingKey() {
		store.set("name", "gokul");
		store.set("name", "rahul");
		
		assertEquals("rahul", store.get("name"));
	}
	
	@Test
	void deleteMissingKey() {
		boolean deleted = store.delete("unknown");
		assertFalse(deleted);
	}
	
	@Test
	void shouldHandleConcurrentWrites() throws InterruptedException{
		int numberOfThreads = 10;
		
		ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
		
		try {
			for(int i=0; i<100; i++) {
				final int keyNumber = i;
				
				executor.submit(() -> {
					store.set("key" + keyNumber, "value" + keyNumber);
				});
			}
		} finally {
			executor.shutdown();
		}
		
		boolean completed = executor.awaitTermination(5, TimeUnit.SECONDS);
		
		assertTrue(completed, "Tasks did not finish in time");
		
		for (int i = 0; i < 100; i++) {
			assertEquals("value" + i, store.get("key" + i));
		}
	}
	
	@Test
	void shouldHandleSimultaneousWrites() throws Exception {
		int threadCount = 10;
		
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		
		CountDownLatch ready = new CountDownLatch(threadCount);
		CountDownLatch start = new CountDownLatch(1);
		
		List<Future<?>> futures = new ArrayList<>();
		
		try {
			for(int i=0; i<threadCount; i++) {
				final int index = i;
				futures.add(executor.submit(()->{
					ready.countDown();
					start.await();
					store.set("user" + index, "value" + index);
					return null;
				}));
			}
			assertTrue(ready.await(5, TimeUnit.SECONDS), "Workers were not ready");
			
			start.countDown();
			
			for(Future<?> future : futures) {
				future.get(5, TimeUnit.SECONDS);
			}
			
			for (int i=0; i<threadCount; i++) {
				assertEquals("value" + i, store.get("user" + i));
			}
		} finally {
			start.countDown();
			executor.shutdownNow();
		}
	}
	
	@Test
	void shouldIncrementAtomically() throws Exception {
		store.set("counter", "0");
		
		int threads = 10;
		int incrementsPerThread = 100;
		
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		
		List<Future<?>> futures = new ArrayList<>();
		
		try {
			for(int i=0; i<threads; i++) {
				futures.add(executor.submit(() -> {
					
					ready.countDown();
					
					start.await();
					
					for(int j=0; j<incrementsPerThread; j++) {
						store.increment("counter");
					}
					
					return null;
				}));
			}
			
			assertTrue(ready.await(10, TimeUnit.SECONDS));
			
			start.countDown();
			
			for (Future<?> future: futures) {
				future.get(10, TimeUnit.SECONDS);
			}
			
			assertEquals("1000", store.get("counter"));		
		} finally {
			executor.shutdownNow();
		} 
	}
	
	@Test
	void shouldExpireKeyAFterTenSeconds() {
		MutableClock clock = new MutableClock(1000L);
		
		CacheStore testStore = new CacheStore(clock);
		
		testStore.set("session", "abc123");
		
		testStore.expire("session", 10);
		
		assertEquals("abc123", testStore.get("session"));
		
		clock.advanceMillis(9000);
		
		assertEquals("abc123", testStore.get("session"));
		
		clock.advanceMillis(1000);
		
		assertNull(testStore.get("session"));
		assertEquals(-2, testStore.ttl("session"));
	}
	
	@Test
	void shouldExpireAtExactBoundary() {
		MutableClock clock = new MutableClock(0);
		CacheStore testStore = new CacheStore(clock);
		
		testStore.set("token", "xyz");
		testStore.expire("token", 5);
		
		clock.advanceMillis(4999);
		
		assertEquals("xyz", testStore.get("token"));
		clock.advanceMillis(1);
		assertNull(testStore.get("token"));
	}
	
	@Test
	void shouldClearExpirationWhenKeyIsUpdated() {
		MutableClock clock = new MutableClock(0);
		
		CacheStore testStore = new CacheStore(clock);
		
		testStore.set("name", "gokul");
		testStore.expire("name", 5);
		
		clock.advanceMillis(3000);
		
		testStore.set("name", "rahul");
		
		clock.advanceMillis(5000);
		
		assertEquals("rahul", testStore.get("name"));
		assertEquals(-1, testStore.ttl("name"));
	}
	
	@Test
	void shouldRejectExpirationOverflow() {
		MutableClock clock = new MutableClock(1000L);
		CacheStore testStore = new CacheStore(clock);
		
		testStore.set("session", "abc123");
		
		assertThrows(ArithmeticException.class, ()-> testStore.expire("session", Long.MAX_VALUE));
		
		assertEquals("abc123", testStore.get("session"));
		assertEquals(-1, testStore.ttl("session"));
	}
	
	@Test
	void shouldRestoreCacheFromSnapshot() {
		MutableClock clock = new MutableClock(1000L);
		CacheStore original = new CacheStore(clock);
		
		original.set("name", "gokul");
		original.set("city", "Bangalore");
		
		Map<String, CacheEntry> snapshot = original.snapshot();
		
		CacheStore restored = new CacheStore(clock);
		
		restored.restore(snapshot);
		
		assertEquals("gokul", restored.get("name"));
		assertEquals("Bangalore", restored.get("city"));
	}
	
	@TempDir
	Path tempDir;
	
	@Test
	void shouldCreateSnapshotFile() throws Exception {
		CacheStore store = new CacheStore();
		
		store.set("name", "gokul");
		store.set("city", "Bangalore");
		
		Path snapshotPath = tempDir.resolve("miniredis.snapshot");
		
		SnapshotManager manager = new SnapshotManager(snapshotPath);
		
		manager.save(store);
		
		assertTrue(Files.exists(snapshotPath));
		assertTrue(Files.size(snapshotPath) > 0);
	}
	
	@Test
	void shouldRestoreDataFromSnapshot() throws Exception {
		CacheStore original = new CacheStore();
		
		original.set("name", "gokul");
		original.set("city", "Bangalore");
		original.set("counter", "100");
		
//		Path snapshotPath = tempDir.resolve("miniredis.snapshot");
		Path snapshotPath = Path.of("miniredis.snapshot");
		
		System.out.println("Snapshot location: "
		        + snapshotPath.toAbsolutePath());
		
		SnapshotManager manager = new SnapshotManager(snapshotPath);
		
		manager.save(original);
		
		CacheStore restored = new CacheStore();
		
		manager.load(restored);;
		
		assertEquals("gokul", restored.get("name"));
		assertEquals("Bangalore", restored.get("city"));
		assertEquals("100", restored.get("counter"));
	}
	
	@Test
	void shouldPreserveExpirationAfterLoading() throws Exception {
		MutableClock clock = new MutableClock(1000L);
		
		CacheStore original = new CacheStore(clock);
		
		original.set("session", "abc123");
		original.expire("session", 10);
		
		Path snapshotPath = tempDir.resolve("ttl.snapshot");
		
		SnapshotManager manager = new SnapshotManager(snapshotPath);
		
		manager.save(original);;
		
		clock.advanceMillis(5000);
		
		CacheStore restored = new CacheStore(clock);
		
		manager.load(restored);
		
		assertEquals("abc123", restored.get("session"));
		assertEquals(5, restored.ttl("session"));
		
		clock.advanceMillis(5000);
		
		assertNull(restored.get("session"));
	}
	
	@Test
	void shouldRejectInvalidSnapshot() throws Exception {

	    Path snapshotPath =
	            tempDir.resolve("invalid.snapshot");

	    Files.writeString(snapshotPath, "not a valid snapshot");

	    SnapshotManager manager =
	            new SnapshotManager(snapshotPath);

	    CacheStore store = new CacheStore();

	    assertThrows(
	            IOException.class,
	            () -> manager.load(store)
	    );
	}
	
	@Test
	void shouldReplaceExistingSnapshot() throws Exception {
		Path snapshotPath = tempDir.resolve("miniredis.snapshot");
		
		SnapshotManager manager = new SnapshotManager(snapshotPath);
		
		CacheStore store = new CacheStore();
		
		store.set("name", "gokul");
		manager.save(store);
		
		store.set("name", "rahul");
		store.set("city", "Bangalore");
		
		manager.save(store);
		
		CacheStore restored = new CacheStore();
		
		manager.load(restored);
		
		assertEquals("rahul", restored.get("name"));
		assertEquals("Bangalore", restored.get("city"));
	}
	
	@Test
	void shouldPreserveOldSnapshotWhenSaveFails() throws Exception{
		Path snapshotPath = tempDir.resolve("miniredis.snapshot");
		
		SnapshotManager manager = new SnapshotManager(snapshotPath);
		
		CacheStore store = new CacheStore();
		
		store.set("name", "gokul");
		manager.save(store);
		
		store.set("large", "a".repeat(70000));
		
		assertThrows(IOException.class, () -> manager.save(store));
		
		CacheStore restored = new CacheStore();
		
		manager.load(restored);;
		
		assertEquals("gokul", restored.get("name"));
		assertNull(restored.get("large"));
	}
	
	@Test
	void snapshotShouldNotChangeAFterCacheUpdate() {
		store.set("name", "gokul");
		store.set("counter", "10");
		
		Map<String, CacheEntry> snapshot = store.snapshot();
		
		store.set("name", "rahul");
		store.increment("counter");
		
		assertEquals("gokul", snapshot.get("name").getValue());
		assertEquals("10", snapshot.get("counter").getValue());
		
		assertEquals("rahul", store.get("name"));
		assertEquals("11", store.get("counter"));
	}
	
	@Test
	void snapshotShouldWorkDuringConcurrentIncrements() throws Exception {
		store.set("counter", "0");
		
		int threads = 10;
		int incrementsPerThread = 100;
		
		ExecutorService executor = Executors.newFixedThreadPool(threads + 1);
		
		CountDownLatch start = new CountDownLatch(1);
		
		List<Future<?>> futures = new ArrayList<>();
		
		try {
			for(int i=0; i<threads; i++) {
				futures.add(executor.submit(() -> {
					start.await();
					
					for(int j=0; j<incrementsPerThread; j++) {
						store.increment("counter");
					}
					
					return null;
				}));
			}
			Future<?> snapshotFuture = executor.submit(() -> {
				start.await();
				
				for(int i=0; i < 100; i++) {
					Map<String, CacheEntry> snapshot = store.snapshot();
					
					long value = Long.parseLong(snapshot.get("counter").getValue());
					assertTrue(value >= 0 && value <= 1000);
				}
				return null;
			});
			start.countDown();
			
			for(Future<?> future : futures) {
				future.get();
			}
			
			snapshotFuture.get();
			
			assertEquals("1000", store.get("counter"));
		} finally {
			executor.shutdownNow();
		}
	}
}
