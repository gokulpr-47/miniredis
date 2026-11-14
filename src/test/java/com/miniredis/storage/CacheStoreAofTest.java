package com.miniredis.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.miniredis.command.CommandProcessor;
import com.miniredis.persistence.AofManager;
import com.miniredis.persistence.AofRecord;
import java.nio.file.StandardOpenOption;


public class CacheStoreAofTest {
	
	@TempDir
	Path tempDir;
	
	@Test
	void shouldWriteAofBeforeUpdatingCache() throws Exception{
		Path aofPath = tempDir.resolve("miniredis.aof");
		
		AofManager manager = new AofManager(aofPath);
		
		CacheStore store = new CacheStore(
				Clock.systemUTC(),
				manager
		);
		
		store.set("name", "gokul");
		
		assertEquals("gokul", store.get("name"));
		
		assertTrue(Files.exists(aofPath));
		assertTrue(Files.size(aofPath) > 0);
	}
	
	@Test
	void shouldNotUpdateCacheWhenAofAppendFails() throws Exception {
		AofManager manager = new AofManager(tempDir);
		
		CacheStore store = new CacheStore(
				Clock.systemUTC(),
				manager
		);
		
		assertThrows(
				IOException.class,
				() -> store.set("name", "gokul")
		);
		
		assertNull(store.get("name"));
	}
	
	@Test
	void shouldReturnErrorWhenAofWriteFails() {
		AofManager manager = new AofManager(tempDir);
		
		CacheStore store = new CacheStore(
			Clock.systemUTC(),
			manager
		);
		
		CommandProcessor processor = new CommandProcessor(store);
		
		String response = processor.execute("SET name gokul");
		
		assertEquals("ERR persistence failure", response);
		assertNull(store.get("name"));
	}
	
	@Test
	void shouldAppendDeleteAndExpireRecords() throws Exception {
		Path path = tempDir.resolve("miniredis.aof");
		
		AofManager manager = new AofManager(path);
		
		manager.append(AofRecord.delete("name"));
		
		long expiresAt = 1793476800000L;
		
		manager.append(AofRecord.expire("session", expiresAt));
		
		try(DataInputStream input = new DataInputStream(Files.newInputStream(path))){
			int delLength = input.readInt();
			byte delOperation = input.readByte();
			String delKey = input.readUTF();
			
			assertEquals(7, delLength);
			assertEquals(AofRecord.DEL, delOperation);
			assertEquals("name", delKey);
			
			int expireLength = input.readInt();
			byte expireOperation = input.readByte();
			String expireKey = input.readUTF();
			long storedExpiry = input.readLong();
			
			assertEquals(18, expireLength);
			assertEquals(AofRecord.EXPIRE, expireOperation);
			assertEquals("session", expireKey);
			assertEquals(expiresAt, storedExpiry);
			
			assertEquals(-1, input.read());
		}
	}
	
	@Test
	void shouldPersistDelete() throws Exception{
		Path path = tempDir.resolve("miniredis.aof");
		
		AofManager manager = new AofManager(path);
		
		CacheStore store = new CacheStore(Clock.systemUTC(), manager);
		
		store.set("name", "gokul");
		
		assertTrue(store.delete("name"));
		assertNull(store.get("name"));
		
		try(DataInputStream input = new DataInputStream(Files.newInputStream(path))){
			//Read SET Record
			int setLength = input.readInt();
			byte setOperation = input.readByte();
			String setKey = input.readUTF();
			String setValue = input.readUTF();
			boolean hasExpiry = input.readBoolean();
			
			assertEquals(15, setLength);
			assertEquals(AofRecord.SET, setOperation);
			assertEquals("name", setKey);
			assertEquals("gokul", setValue);
			assertFalse(hasExpiry);
			
			//Read DEL record
			int delLength = input.readInt();
			byte delOperation = input.readByte();
			String delKey = input.readUTF();
			
			assertEquals(7, delLength);
			assertEquals(AofRecord.DEL, delOperation);
			assertEquals("name", delKey);
			
			//Verify EOF
			assertEquals(-1, input.read());
		}
	}
	
	@Test
	void shouldPersistIncrementedValue() throws Exception{
		Path path = tempDir.resolve("miniredis.aof");
		
		AofManager manager = new AofManager(path);
		
		CacheStore store = new CacheStore(Clock.systemUTC(), manager);
		
		store.set("counter", "10");
		
		long result = store.increment("counter");
		
		assertEquals(11, result);
		assertEquals("11", store.get("counter"));
	}
	
	@Test
	void shouldPersistExpiration() throws Exception{
		Path path = tempDir.resolve("miniredis.aof");
		
		AofManager manager = new AofManager(path);
		
		CacheStore store = new CacheStore(Clock.systemUTC(), manager);
		
		store.set("session", "abc");
		
		assertTrue(store.expire("session", 10));
		assertTrue(Files.size(path) > 0);
	}
	
	@Test
	void shouldReadAofRecords() throws Exception{
		Path path = tempDir.resolve("miniredis.aof");
		
		AofManager manager = new AofManager(path);
		
		manager.append(AofRecord.set("name", "gokul", null));
		manager.append(AofRecord.delete("city"));
		manager.append(AofRecord.expire("session", 1793476800000L));
		
		List<AofRecord> records = manager.readRecords();
		
		assertEquals(3, records.size());
		
		assertEquals(AofRecord.SET, records.get(0).operation());
		assertEquals("name", records.get(0).key());
		assertEquals("gokul", records.get(0).value());
		
		assertEquals(AofRecord.DEL, records.get(1).operation());
		assertEquals("city", records.get(1).key());
		
		assertEquals(AofRecord.EXPIRE, records.get(2).operation());
		assertEquals("session", records.get(2).key());
		assertEquals(1793476800000L, records.get(2).expiresAt());
	}
	
	@Test
	void shouldReplayAofRecordsIntoCacheStore() throws Exception {

	    Path path = tempDir.resolve("miniredis.aof");

	    AofManager manager = new AofManager(path);

	    // Simulate the original server writing records.
	    manager.append(AofRecord.set("name", "gokul", null));
	    manager.append(AofRecord.set("city", "Bengaluru", null));
	    manager.append(AofRecord.delete("name"));

	    long futureExpiry = 1794000000000L;

	    manager.append(AofRecord.set("session", "token", null));
	    manager.append(AofRecord.expire("session", futureExpiry));

	    long pastExpiry = 1700000000000L;

	    manager.append(
	        AofRecord.set("oldSession", "expired", pastExpiry)
	    );

	    // Simulate server restart.
	    CacheStore recoveredStore = new CacheStore();

	    // Read records from disk and reconstruct memory.
	    List<AofRecord> records = manager.readRecords();

	    recoveredStore.replayAof(records);

	    // Verify recovered state.
	    assertNull(recoveredStore.get("name"));
	    assertEquals("Bengaluru", recoveredStore.get("city"));
	    assertEquals("token", recoveredStore.get("session"));
	    assertNull(recoveredStore.get("oldSession"));
	}
	
	@Test
	void shouldNotAppendRecordsDuringReplay() throws Exception {
		Path path = tempDir.resolve("miniredis.aof");
		
		AofManager manager = new AofManager(path);
		
		manager.append(AofRecord.set("name", "gokul", null));
		manager.append(AofRecord.set("city", "Bengaluru", null));
		manager.append(AofRecord.delete("name"));
		
		long sizeBeforeReplay = Files.size(path);
		
		CacheStore recoveredStore = new CacheStore(Clock.systemUTC(), manager);
		
		List<AofRecord> records = manager.readRecords();
		
		recoveredStore.replayAof(records);
		
		long sizeAfterReplay = Files.size(path);
		
		assertEquals(sizeBeforeReplay, sizeAfterReplay);
		
		assertNull(recoveredStore.get("name"));
		assertEquals("Bengaluru", recoveredStore.get("city"));
		
		assertEquals(3, manager.readRecords().size());
		
	}
	
	@Test
	void shouldReturnEmptyRecordsWhenAofFileDoesNotExist() throws Exception{
		Path path = tempDir.resolve("miniredis.aof");
		
		AofManager manager = new AofManager(path);
		
		List<AofRecord> records = manager.readRecords();
		
		assertTrue(records.isEmpty());
	}
	
	@Test 
	void shouldReturnEmptyRecordsWehnAofFileIsEmpty() throws Exception {
		Path path = tempDir.resolve("empty.aof");
		Files.createFile(path);
		AofManager manager = new AofManager(path);
		List<AofRecord> records = manager.readRecords();
		assertTrue(records.isEmpty());
	}
	
	@Test
	void shouldRecoverCompleteRecordsAndTruncateIncompleteFinalPayload() throws IOException {

	    Path aofPath = tempDir.resolve("miniredis.aof");
	    AofManager manager = new AofManager(aofPath);

	    // Write two valid records
	    manager.append(AofRecord.set("name", "gokul", null));
	    manager.append(AofRecord.set("city", "Bengaluru", null));

	    // Remember the file size after valid records
	    long validFileSize = Files.size(aofPath);

	    // Simulate a crash while writing the third record
	    try (DataOutputStream output = new DataOutputStream(
	            Files.newOutputStream(aofPath, StandardOpenOption.APPEND))) {

	        output.writeInt(10);             // Payload claims to be 10 bytes
	        output.write(new byte[] {1, 2, 3}); // But only 3 bytes were written
	    }

	    // Recover
	    List<AofRecord> records = manager.readRecords();

	    // Only the two complete records should be recovered
	    assertEquals(2, records.size());

	    assertEquals("name", records.get(0).key());
	    assertEquals("gokul", records.get(0).value());

	    assertEquals("city", records.get(1).key());
	    assertEquals("Bengaluru", records.get(1).value());

	    // Incomplete bytes should have been removed
	    assertEquals(validFileSize, Files.size(aofPath));

	    // A second recovery should still work
	    List<AofRecord> secondRead = manager.readRecords();
	    assertEquals(2, secondRead.size());
	}
	
	@Test
	void shouldTruncateIncompleteFinalHeader() throws IOException {

	    Path aofPath = tempDir.resolve("miniredis.aof");
	    AofManager manager = new AofManager(aofPath);

	    manager.append(AofRecord.set("name", "gokul", null));

	    long validFileSize = Files.size(aofPath);

	    // Simulate writing only 2 bytes of the next 4-byte header
	    try (DataOutputStream output = new DataOutputStream(
	            Files.newOutputStream(aofPath, StandardOpenOption.APPEND))) {

	        output.write(new byte[] {0, 0});
	    }

	    List<AofRecord> records = manager.readRecords();

	    assertEquals(1, records.size());
	    assertEquals(validFileSize, Files.size(aofPath));
	}
	
	@Test
	void shouldRejectFurtherWritesAfterAppendFailure() throws Exception {
		Path invalidAofPath = tempDir.resolve("aof-directory");
		Files.createDirectory(invalidAofPath);
		AofManager manager = new AofManager(invalidAofPath);
		
		assertThrows(IOException.class, () -> manager.append(AofRecord.set("name", "gokul", null)));
		
		IOException exception = assertThrows(IOException.class, () -> manager.append(AofRecord.set("city", "Bengaluru", null)));
		
		assertTrue(exception.getMessage().contains("failed state"));
	}
}
