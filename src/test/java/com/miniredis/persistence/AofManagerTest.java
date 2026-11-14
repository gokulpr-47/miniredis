package com.miniredis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class AofManagerTest {
	@TempDir
	Path tempDir;
	
	@Test
	void shouldAppendSetRecord() throws Exception {
		Path aofPath = tempDir.resolve("miniredis.aof");
		
		AofManager manager = new AofManager(aofPath);
		
		manager.append(AofRecord.set("name", "gokul", null));
		
		assertTrue(Files.exists(aofPath));
		assertTrue(Files.size(aofPath) > 0);
				
	}
	
	@Test
	void shouldReadWrittenSetRecord() throws Exception {
		Path aofPath = tempDir.resolve("miniredis.aof");
		AofManager manager = new AofManager(aofPath);
		
		manager.append(AofRecord.set("name", "gokul", null));
		
		try (DataInputStream input = new DataInputStream(Files.newInputStream(aofPath))){
			int payloadLength = input.readInt();
			
			byte operation = input.readByte();
			String key = input.readUTF();
			String value = input.readUTF();
			
			assertEquals(14, payloadLength);
			assertEquals(AofRecord.SET, operation);
			assertEquals("name", key);
			assertEquals("gokul", value);
			
			assertEquals(-1, input.read());
		}
	}
}
