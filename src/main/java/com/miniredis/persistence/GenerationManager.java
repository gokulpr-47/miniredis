package com.miniredis.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public class GenerationManager {
	private final Path dataDirectory;
	private final Path currentFile;
	
	private int activeGeneration;
	
	public GenerationManager(Path dataDirectory) {
		this.dataDirectory = dataDirectory;
		this.currentFile = dataDirectory.resolve("CURRENT");
	}
	
	public void initialize() throws IOException {
		Files.createDirectories(dataDirectory);
		
		if(!Files.exists(currentFile)) {
			Path generationOne = dataDirectory.resolve("generation-1");
			Files.createDirectories(generationOne);
			writeCurrentGeneration(1);
			activeGeneration = 1;
			return;
		}
		
		String value = Files.readString(currentFile).trim();
		
		try {
			activeGeneration = Integer.parseInt(value);
		} catch (NumberFormatException e) {
			throw new IOException("Invalid CURRENT generation number: "+value, e);
		}
		
		if(activeGeneration <= 0) {
			throw new IOException("Invalid generation number.");
		}
		
		Path activeDirectory = getGenerationDirectory();
		
		if(!Files.isDirectory(activeDirectory)) {
			throw new IOException("Active generation direcotry does not exist: " + activeDirectory);
		}
	}
	
	public Path getGenerationDirectory() {
		return dataDirectory.resolve("generation-" + activeGeneration);
	}
	
	public Path getSnapshotPath() {
		return getGenerationDirectory().resolve("snapshot.bin");
	}
	
	public Path getAofPath() {
		return getGenerationDirectory().resolve("aof.bin");
	}
	
	public int getActiveGeneration() {
		return activeGeneration;
	}
	
	private void writeCurrentGeneration(int generation) throws IOException{
		Path tempFile = dataDirectory.resolve("CURRENT.tmp");
		
		Files.writeString(
				tempFile, 
				String.valueOf(generation), 
				StandardCharsets.UTF_8, 
				StandardOpenOption.CREATE, 
				StandardOpenOption.TRUNCATE_EXISTING, 
				StandardOpenOption.WRITE
		);
		
		try {
			Files.move(
					tempFile, 
					currentFile, 
					StandardCopyOption.ATOMIC_MOVE, 
					StandardCopyOption.REPLACE_EXISTING
			);
		} catch (IOException e) {
			Files.move(tempFile, currentFile, StandardCopyOption.REPLACE_EXISTING);
		}
	}
	

	public int createNextGeneration() throws IOException {
	
	    int nextGeneration = activeGeneration + 1;
	
	    // Avoid reusing a generation directory left by an
	    // earlier interrupted compaction.
	    while (Files.exists(
	            dataDirectory.resolve("generation-" + nextGeneration))) {
	        nextGeneration++;
	    }
	
	    Path newDirectory =
	            dataDirectory.resolve("generation-" + nextGeneration);
	
	    Files.createDirectories(newDirectory);
	
	    return nextGeneration;
	}
	
	public Path getSnapshotPath(int generation) {
	    return dataDirectory
	            .resolve("generation-" + generation)
	            .resolve("snapshot.bin");
	}
	
	public Path getAofPath(int generation) {
	    return dataDirectory
	            .resolve("generation-" + generation)
	            .resolve("aof.bin");
	}
	
	public void switchToGeneration(int generation) throws IOException {
	
	    Path generationDirectory =
	            dataDirectory.resolve("generation-" + generation);
	
	    if (!Files.isDirectory(generationDirectory)) {
	        throw new IOException("Generation directory does not exist.");
	    }
	
	    Path snapshot = getSnapshotPath(generation);
	    Path aof = getAofPath(generation);
	
	    if (!Files.isRegularFile(snapshot)
	            || !Files.isRegularFile(aof)) {
	        throw new IOException(
	                "Cannot activate generation: snapshot or AOF is missing."
	        );
	    }
	
	    // Update CURRENT first.
	    writeCurrentGeneration(generation);
	
	    // Update in-memory active generation only after
	    // CURRENT has been successfully updated.
	    activeGeneration = generation;
	}
}
