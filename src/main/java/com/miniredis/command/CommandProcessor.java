package com.miniredis.command;

import com.miniredis.storage.CacheStore;

public class CommandProcessor {
	private final CacheStore store;
	
	public CommandProcessor(CacheStore store) {
		this.store = store;
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
				
				store.set(parts[1], parts[2]);
				return "OK";
			
			case "GET":
				if(parts.length != 2) {
					return "ERR usage: GET key";
				}
				String value = store.get(parts[1]);
				return value != null ? value : "(nil)";
				
			case "DEL":
				if(parts.length != 2) {
					return "ERR usage: DEL key";
				}
				
				return store.delete(parts[1]) ? "1" : "0";
				
			case "EXPIRE":
				if(parts.length != 3){
					return "ERR usage: EXPIRE key seconds";
				}
				
				try {
					long seconds = Long.parseLong(parts[2]);
					
					return store.expire(parts[1], seconds)?"1": "0";
				} catch(NumberFormatException e) {
					return "ERR invalid expiration time";
				}
			
			case "TTL":
				if(parts.length != 2) {
					return "ERR usage: TTL key";
				}
				return String.valueOf(store.ttl(parts[1]));
				
			default:
				return "ERR unknown command";
		}
	}
	
}
