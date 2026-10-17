package com.gokulpr.mini_redis;

import java.io.*;
import java.net.*;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.miniredis.command.CommandProcessor;
import com.miniredis.storage.CacheStore;
import com.miniredis.server.ClientHandler;

public class App
{
    public static void main( String[] args ) throws IOException
    {
    	CacheStore store = new CacheStore();
    	CommandProcessor processor = new CommandProcessor(store);
    	
    	ExecutorService threadPool = Executors.newFixedThreadPool(5);
    	
    	try(ServerSocket server = new ServerSocket(6379)){
    		System.out.println("MiniRedis running on port 6379");
    		
    		while(true) {
    			Socket client = server.accept();
    			
    			threadPool.execute(new ClientHandler(client, processor));
    		}
    	} finally {
    		threadPool.shutdown();
    	}
    }
}
