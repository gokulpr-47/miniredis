package com.miniredis.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.miniredis.storage.CacheStore;

public class GenerationCompactionTest {
	@TempDir
	Path tempDir;
	
	@Test
	void compactionCreatesNewGenerationAndpPreservesData() throws IOException {
		Path dataDirectory = tempDir.resolve("miniredis-data");
		
		GenerationManager generationManager = new GenerationManager(dataDirectory);
		
		generationManager.initialize();
		
		AofManager aofManager = new AofManager(generationManager.getAofPath());
		
		CacheStore store = new CacheStore(Clock.systemUTC(), aofManager);
		
		store.set("name", "gokul");
		store.set("city", "Bangalore");
		store.set("language", "Java");
		
		store.compact(generationManager);
		
		assertEquals(2, generationManager.getActiveGeneration());
		
		Path newSnapshot = generationManager.getSnapshotPath();
		
		Path newAof = generationManager.getAofPath();
		
		assertTrue(Files.exists(newSnapshot));
		assertTrue(Files.exists(newAof));
		
		assertEquals(0L, Files.size(newAof));
		
		SnapshotManager newSnapshotManager = new SnapshotManager(newSnapshot);
		
		CacheStore recoveredStore = new CacheStore();
		
		long offset = newSnapshotManager.load(recoveredStore);
		
		assertEquals(0L, offset);
		
		assertEquals("gokul", recoveredStore.get("name"));
		assertEquals("Bangalore", recoveredStore.get("city"));
		assertEquals("Java", recoveredStore.get("language"));
		
		store.set("framework", "Spring Boot");
		
		assertTrue(Files.size(newAof) > 0);
		
		assertFalse(
		        Files.exists(dataDirectory.resolve("generation-1"))
		);
		
		assertTrue(
		        Files.exists(dataDirectory.resolve("generation-2"))
		);
	}
	

	@Test
	void recoveryWorksAfterCompactionAndNewMutations()
	        throws IOException {
	
	    // 1. Initialize generation manager.
	    Path dataDirectory = tempDir.resolve("recovery-data");
	
	    GenerationManager generationManager =
	            new GenerationManager(dataDirectory);
	
	    generationManager.initialize();
	
	    // 2. Initialize generation-1.
	    AofManager firstAof =
	            new AofManager(generationManager.getAofPath());
	
	    CacheStore originalStore =
	            new CacheStore(Clock.systemUTC(), firstAof);
	
	    // 3. Add initial data.
	    originalStore.set("name", "gokul");
	    originalStore.set("city", "Bengaluru");
	    originalStore.set("language", "Java");
	
	    // 4. Compact generation-1 into generation-2.
	    originalStore.compact(generationManager);
	
	    // 5. Perform mutations AFTER compaction.
	    originalStore.set("name", "rahul");
	    originalStore.set("framework", "Spring Boot");
	    originalStore.delete("city");
	
	    // 6. Simulate application restart.
	    // Create completely new persistence objects.
	    GenerationManager restartedManager =
	            new GenerationManager(dataDirectory);
	
	    restartedManager.initialize();

	    assertEquals(2, restartedManager.getActiveGeneration());
	
	    AofManager restartedAof =
	            new AofManager(restartedManager.getAofPath());
	
	    SnapshotManager restartedSnapshot =
	            new SnapshotManager(
	                    restartedManager.getSnapshotPath()
	            );
	
	    CacheStore recoveredStore =
	            new CacheStore(Clock.systemUTC(), restartedAof);
	
	    // 7. Recover snapshot.
	    long checkpointOffset =
	            restartedSnapshot.load(recoveredStore);
	
	    // 8. Replay records after the snapshot checkpoint.
	    List<AofRecord> records =
	            restartedAof.readRecordsFrom(checkpointOffset);
	
	    recoveredStore.replayAof(records);
	
	    // 9. Verify recovered data.
	    assertEquals("rahul", recoveredStore.get("name"));
	    assertEquals("Java", recoveredStore.get("language"));
	    assertEquals("Spring Boot", recoveredStore.get("framework"));
	
	    assertNull(recoveredStore.get("city"));
	}
	
}
