package com.miniredis.command;

import java.io.IOException;

import com.miniredis.persistence.GenerationManager;
import com.miniredis.storage.CacheStore;

public class CommandProcessor {
	private final CacheStore store;
	private final GenerationManager generationManager;
	
	public CommandProcessor(CacheStore store, GenerationManager generationManager) {
		this.store = store;
		this.generationManager = generationManager;
	}
	
	public String execute(String input) {
		if(input == null || input.isBlank()) {
			return "ERR empty command";
		}
		String[] parts = input.trim().split("\\s+");
		
		String command = parts[0].toUpperCase();
		
		switch(command) {
			case "PING":
				return "PONG";
				
			case "SET":
				if(parts.length != 3) {
					return "ERR usage: SET key value";
				}
				try {
					store.set(parts[1], parts[2]);
					return "OK";					
				} catch (IOException e) {
					System.err.println("AOF persistence failed: " + e.getMessage());
					return "ERR persistence failure";
				}
			
			case "GET":
				if(parts.length != 2) {
					return "ERR usage: GET key";
				} 
				String value = store.get(parts[1]);
				return value != null ? value : "(nil)";
				
			case "DEL":
				try {
					return store.delete(parts[1]) ? "1" : "0";
				} catch (IOException e){
					e.printStackTrace();
					return "ERR persistence failure";
				}
				
			case "EXPIRE":
				if(parts.length != 3){
					return "ERR usage: EXPIRE key seconds";
				}
				
				try {
					long seconds = Long.parseLong(parts[2]);
					return store.expire(parts[1], seconds)?"1": "0";
				} catch (IOException e) {
					return "ERR invalid expiration time";
				}
			
			case "TTL":
				if(parts.length != 2) {
					return "ERR usage: TTL key";
				}
				return String.valueOf(store.ttl(parts[1]));
				
			case "INCR":
				if (parts.length != 2) {
					return "ERR usage: INCR key";
				}
				
				try {
					return String.valueOf(store.increment(parts[1]));
				} catch (IOException e) {
					return "ERR value is not an integer";
				}
				
			case "COMPACT":
				if(parts.length != 1) {
					return "ERR usage: COMPACT";
				}
				try {
					store.compact(generationManager);
					return "OK";
				} catch (IOException e) {
					System.err.println("Compaction failed: " + e.getMessage());
					return "ERR compaction failure";
				}
			default:
				return "ERR unknown command";
		}
	}
}
