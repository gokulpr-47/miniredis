package com.miniredis.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.miniredis.storage.CacheStore;

public class SnapshotCheckpointTest {
	@TempDir
	Path tempDir;
	
	@Test
	void shouldReplayOnlyRecordsAfterSnapshotCheckpoint() throws Exception {
		Path aofPath = tempDir.resolve("miniredis.aof");
		Path snapshotPath = tempDir.resolve("miniredis.snapshot");
		
		AofManager aofManager = new AofManager(aofPath);
		
		CacheStore store = new CacheStore(Clock.systemUTC(), aofManager);
		
		store.set("name", "Gokul");
		
		SnapshotManager snapshotManager = new SnapshotManager(snapshotPath);
		
		snapshotManager.save(store);
		
		store.set("city", "Bengaluru");
		
		AofManager restartedAofManager = new AofManager(aofPath);
		
		CacheStore restartedStore = new CacheStore(Clock.systemUTC(), restartedAofManager);
		
		SnapshotManager restartedSnapshotManager = new SnapshotManager(snapshotPath);
		
		long checkpointOffset = restartedSnapshotManager.load(restartedStore);
		
		List<AofRecord> records = restartedAofManager.readRecordsFrom(checkpointOffset);
		
		restartedStore.replayAof(records);
		
		assertEquals("Gokul", restartedStore.get("name"));
		assertEquals("Bengaluru", restartedStore.get("city"));
	}
	
	@Test
	void shouldReplayNoRecordsWhenSnapshotIsAtendOfAof() throws Exception {
		Path aofPath = tempDir.resolve("miniredis.aof");
		Path snapshotPath = tempDir.resolve("miniredis.snapshot");
		
		AofManager aofManager = new AofManager(aofPath);
		
		CacheStore store = new CacheStore(Clock.systemUTC(), aofManager);
		
		store.set("name", "Gokul");
		store.set("city", "Bengaluru");
		
		SnapshotManager snapshotManager = new SnapshotManager(snapshotPath);
		
		snapshotManager.save(store);;
		
		AofManager restartedAofManager = new AofManager(aofPath);
		
		CacheStore restartedStore = new CacheStore(Clock.systemUTC(), restartedAofManager);
		
		SnapshotManager restartedSnapshotManager = new SnapshotManager(snapshotPath);
		
		long checkpointOffset = restartedSnapshotManager.load(restartedStore);
		
		List<AofRecord> records = restartedAofManager.readRecordsFrom(checkpointOffset);
		
		assertTrue(records.isEmpty());
		
		assertEquals("Gokul", restartedStore.get("name"));
		assertEquals("Bengaluru", restartedStore.get("city"));
	}
	
	@Test
	void shouldRecoverSnapshotAndTruncateIncompleteAofTail()
	        throws Exception {

	    Path aofPath = tempDir.resolve("miniredis.aof");
	    Path snapshotPath = tempDir.resolve("miniredis.snapshot");

	    // -------- First application run --------

	    AofManager aofManager = new AofManager(aofPath);

	    CacheStore store = new CacheStore(
	        Clock.systemUTC(),
	        aofManager
	    );

	    store.set("name", "Gokul");

	    // Create snapshot after first mutation.
	    SnapshotManager snapshotManager =
	        new SnapshotManager(snapshotPath);

	    snapshotManager.save(store);

	    // Append a valid record after snapshot.
	    store.set("city", "Bengaluru");

	    long validAofSize = Files.size(aofPath);

	    // Simulate an incomplete final record.
	    try (DataOutputStream out = new DataOutputStream(
	            Files.newOutputStream(
	                aofPath,
	                StandardOpenOption.APPEND))) {

	        out.writeInt(10); // Claims payload is 10 bytes.
	        out.write(new byte[] { 1, 2, 3 }); // Only 3 bytes written.
	    }

	    // Confirm the incomplete tail exists.
	    assertTrue(Files.size(aofPath) > validAofSize);

	    // -------- Simulate restart --------

	    AofManager restartedAofManager =
	        new AofManager(aofPath);

	    CacheStore restartedStore = new CacheStore(
	        Clock.systemUTC(),
	        restartedAofManager
	    );

	    SnapshotManager restartedSnapshotManager =
	        new SnapshotManager(snapshotPath);

	    long checkpointOffset =
	        restartedSnapshotManager.load(restartedStore);

	    List<AofRecord> records =
	        restartedAofManager.readRecordsFrom(checkpointOffset);

	    restartedStore.replayAof(records);

	    // -------- Verify recovered state --------

	    assertEquals(1, records.size());
	    assertEquals("city", records.get(0).key());

	    assertEquals("Gokul", restartedStore.get("name"));
	    assertEquals("Bengaluru", restartedStore.get("city"));

	    // Incomplete tail should be removed.
	    assertEquals(validAofSize, Files.size(aofPath));
	}
	
	@Test
	void shouldLoadVersion1SnapshotAndReplayEntireAof() throws Exception {

	    Path aofPath = tempDir.resolve("miniredis.aof");
	    Path snapshotPath = tempDir.resolve("miniredis.snapshot");

	    // Create a version 1 snapshot manually.
	    try (DataOutputStream out = new DataOutputStream(
	            Files.newOutputStream(snapshotPath))) {

	        out.writeInt(0x4D524453); // MAGIC
	        out.writeInt(1);          // VERSION 1
	        out.writeInt(1);          // ENTRY COUNT

	        out.writeUTF("name");
	        out.writeUTF("Gokul");

	        out.writeBoolean(false);  // No expiration
	    }

	    // Create an AOF containing a mutation.
	    AofManager aofManager = new AofManager(aofPath);

	    aofManager.append(AofRecord.set("city", "Bengaluru", null));

	    // Simulate restart.
	    CacheStore restartedStore = new CacheStore(
	        Clock.systemUTC(),
	        new AofManager(aofPath)
	    );

	    SnapshotManager snapshotManager =
	        new SnapshotManager(snapshotPath);

	    long checkpointOffset = snapshotManager.load(restartedStore);

	    List<AofRecord> records =
	        new AofManager(aofPath).readRecordsFrom(checkpointOffset);

	    restartedStore.replayAof(records);

	    assertEquals(0L, checkpointOffset);
	    assertEquals(1, records.size());

	    assertEquals("Gokul", restartedStore.get("name"));
	    assertEquals("Bengaluru", restartedStore.get("city"));
	}
}
