package com.miniredis.persistence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.io.RandomAccessFile;

public class AofManager {
	private final Path path;
	
	private boolean failed = false;
	
	public AofManager(Path path) {
		this.path = path;
	}
	
	public synchronized void append(AofRecord record) throws IOException{	
			if(failed) {
				throw new IOException("AOF is in failed state. Restart and recover before writing");
			}
			
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			
			try(DataOutputStream payload = new DataOutputStream(buffer)){
				payload.writeByte(record.operation());
				payload.writeUTF(record.key());

				switch(record.operation()) {
				case AofRecord.SET -> {
					payload.writeUTF(record.value());
					
					boolean hasExpiry = record.expiresAt() != null;
					
					payload.writeBoolean(hasExpiry);;
					
					if(hasExpiry) {
						payload.writeLong(record.expiresAt());
					}
				}
				
				case AofRecord.DEL -> {
					
				}
				
				case AofRecord.EXPIRE -> {
					payload.writeLong(record.expiresAt());
				}
				
				default -> throw new IllegalArgumentException("Unsuppoerted AOF operation");
				
				}
			}
			
			byte[] data = buffer.toByteArray();
			
			Path parent = path.getParent();
			
			if(parent != null) {
				Files.createDirectories(parent);
			}
			
			try (FileChannel channel = FileChannel.open(
			        path,
			        StandardOpenOption.CREATE,
			        StandardOpenOption.WRITE,
			        StandardOpenOption.APPEND)) {

			    ByteBuffer recordBuffer = ByteBuffer.allocate(
			            Integer.BYTES + data.length
			    );

			    recordBuffer.putInt(data.length);
			    recordBuffer.put(data);

			    recordBuffer.flip();

			    while (recordBuffer.hasRemaining()) {
			        channel.write(recordBuffer);
			    }

			    channel.force(true);
			} catch (IOException e) {
				failed = true;
				
				throw new IOException ("Failed to append to AOF. Persistence is now disabled", e);
			}
	}
	
	public List<AofRecord> readRecords() throws IOException {
	    return readRecordsFrom(0L);
	}

	public List<AofRecord> readRecordsFrom(long offset) throws IOException {

	    List<AofRecord> records = new ArrayList<>();

	    if (offset < 0) {
	        throw new IOException("Invalid AOF offset: " + offset);
	    }

	    if (!Files.exists(path)) {
	        if (offset != 0) {
	            throw new IOException(
	                "Snapshot references an AOF offset, but AOF file is missing"
	            );
	        }
	        return records;
	    }

	    long lastGoodOffset = offset;

	    try (RandomAccessFile input =
	            new RandomAccessFile(path.toFile(), "rw")) {

	        long fileLength = input.length();

	        if (offset > fileLength) {
	            throw new IOException(
	                "Snapshot AOF offset exceeds AOF file length"
	            );
	        }

	        // Start reading from the checkpoint.
	        input.seek(offset);

	        while (input.getFilePointer() < fileLength) {

	            // Check whether a complete 4-byte header exists.
	            if (fileLength - input.getFilePointer() < Integer.BYTES) {
	                break;
	            }

	            int recordLength = input.readInt();

	            if (recordLength < 1 || recordLength > 1_048_576) {
	                throw new IOException(
	                    "Invalid AOF record length: " + recordLength
	                );
	            }

	            // Check whether the complete payload exists.
	            if (fileLength - input.getFilePointer() < recordLength) {
	                break;
	            }

	            byte[] payloadBytes = new byte[recordLength];
	            input.readFully(payloadBytes);

	            try (DataInputStream payload =
	                    new DataInputStream(
	                        new ByteArrayInputStream(payloadBytes))) {

	                byte operation = payload.readByte();
	                String key = payload.readUTF();

	                AofRecord record;

	                switch (operation) {

	                    case AofRecord.SET -> {
	                        String value = payload.readUTF();

	                        boolean hasExpiry = payload.readBoolean();

	                        Long expiresAt =
	                            hasExpiry ? payload.readLong() : null;

	                        record = AofRecord.set(key, value, expiresAt);
	                    }

	                    case AofRecord.DEL -> {
	                        record = AofRecord.delete(key);
	                    }

	                    case AofRecord.EXPIRE -> {
	                        long expiresAt = payload.readLong();

	                        record = AofRecord.expire(key, expiresAt);
	                    }

	                    default -> throw new IOException(
	                        "Unknown AOF operation: " + operation
	                    );
	                }

	                if (payload.available() != 0) {
	                    throw new IOException(
	                        "Unexpected data in AOF record"
	                    );
	                }

	                records.add(record);

	            } catch (EOFException e) {
	                throw new IOException("Corrupted AOF record", e);
	            }

	            // Update only after successfully decoding the full record.
	            lastGoodOffset = input.getFilePointer();
	        }

	        // Remove an incomplete final record, if one exists.
	        if (lastGoodOffset < fileLength) {

	            input.setLength(lastGoodOffset);

	            System.out.println(
	                "Incomplete AOF tail removed. Truncated at byte: "
	                + lastGoodOffset
	            );
	        }
	    }

	    return records;
	}
	
	public synchronized long getCurrentOffset() throws IOException {
	    if (!Files.exists(path)) {
	        return 0L;
	    }

	    return Files.size(path);
	}
}
