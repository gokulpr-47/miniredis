package com.miniredis.persistence;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

import com.miniredis.storage.CacheEntry;
import com.miniredis.storage.CacheStore;

public class SnapshotManager {
	private static final int MAGIC = 0x4D524453;
	private static final int VERSION = 2;
	
	private final Path snapshotPath;
	
	public SnapshotManager(Path snapshotPath) {
		this.snapshotPath = snapshotPath;
	}
	
	public void save(CacheStore store) throws IOException {
	    SnapshotData checkpoint = store.createCheckpoint();
	    save(checkpoint);
	}
	
	public void save(SnapshotData checkpoint) throws IOException {
//		SnapshotData checkpoint = store.createCheckpoint();
		
		Map<String, CacheEntry> entries = checkpoint.entries();
		long aofOffset = checkpoint.offset();
		
		Path absolutePath = snapshotPath.toAbsolutePath();
		Path directory = absolutePath.getParent();
		
		Files.createDirectories(directory);
		
		Path tempFile = Files.createTempFile(directory, "miniredis-", ".tmp");
		
		try {
            // Write the complete snapshot to the temporary file.
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(
                        Files.newOutputStream(tempFile)))) {

                out.writeInt(MAGIC);
                out.writeInt(VERSION);

                // New field in version 2.
                out.writeLong(aofOffset);

                out.writeInt(entries.size());

                for (Map.Entry<String, CacheEntry> item
                        : entries.entrySet()) {

                    String key = item.getKey();
                    CacheEntry entry = item.getValue();

                    out.writeUTF(key);
                    out.writeUTF(entry.getValue());

                    boolean hasExpiration =
                        entry.getExpiresAt() != null;

                    out.writeBoolean(hasExpiration);

                    if (hasExpiration) {
                        out.writeLong(entry.getExpiresAt());
                    }
                }
            }

            // Move only after the output stream has closed.
            Files.move(
                tempFile,
                absolutePath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );

        } finally {
            Files.deleteIfExists(tempFile);
        }
	}
	
	public long load(CacheStore store) throws IOException {
		if(Files.notExists(snapshotPath)) {
			return 0L;
		}
			
		Map<String, CacheEntry> entries = new HashMap<>();
		long aofOffset = 0L;
		
		try (DataInputStream in = new DataInputStream(
				new BufferedInputStream(
						Files.newInputStream(snapshotPath)))) {
			int magic =  in.readInt();
			
			if (magic != MAGIC) {
				throw new IOException("Invalid snapshot file");
			}
			
			int version = in.readInt();
			if (version != 1 && version != VERSION) {
				throw new IOException("Unsupported snapshot version");
			}
			
			if(version >= 2) {
				aofOffset = in.readLong();
				
				if(aofOffset < 0) {
					throw new IOException("Invalid AOF checkpoint offset");
				}
			}
			
			int entryCount = in.readInt();
			if(entryCount < 0) {
				throw new IOException("Invalid entry count");
			}
			
			for (int i = 0; i < entryCount; i++) {
				String key = in.readUTF();
				String value = in.readUTF();
				
				boolean hasExpiration = in.readBoolean();
				
				Long expiresAt = null;
				
				if(hasExpiration) {
					expiresAt = in.readLong();
				}
				
				entries.put(key, new CacheEntry(value, expiresAt));
			}
			
			if(in.read() != -1) {
				throw new IOException("Unexpected data aftr snapshot");
			}
		}
		store.restore(entries);
		return aofOffset;
	}
}
