package com.gokulpr.mini_redis;

import java.io.*;
import java.net.*;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.miniredis.command.CommandProcessor;
import com.miniredis.persistence.AofManager;
import com.miniredis.persistence.AofRecord;
import com.miniredis.persistence.GenerationManager;
import com.miniredis.persistence.SnapshotManager;
import com.miniredis.storage.CacheStore;
import com.miniredis.server.ClientHandler;

public class App
{
    public static void main( String[] args ) throws IOException
    {
    	
    	Set<Socket> clients = ConcurrentHashMap.newKeySet();
    	
    	GenerationManager generationManager = new GenerationManager(Path.of("miniredis-data"));
    	
    	generationManager.initialize();
    	
    	System.out.println("Active generation: " + generationManager.getActiveGeneration());
    	
    	AofManager aofManager = new AofManager(Path.of("miniredis.aof"));
    	
    	CacheStore store = new CacheStore(Clock.systemUTC(), aofManager); //contains operations logic
    	
    	SnapshotManager snapshotManager = new SnapshotManager(generationManager.getSnapshotPath());
    	
    	long checkpointOffset;
    	
    	try {
    		checkpointOffset = snapshotManager.load(store);
    		System.out.println("Snapshot recovery completed.");
    	} catch(IOException e) {
    		System.err.println("Failed to load snapshot: "+ e.getMessage());
    		return;
    	}
    	//end: load snapshot before server start
    	
    	try {
    	    List<AofRecord> records = aofManager.readRecordsFrom(checkpointOffset);

    	    store.replayAof(records);

    	    System.out.println(
    	        "AOF recovery completed. Records replayed: "
    	        + records.size()
    	    );

    	} catch (IOException e) {
    	    System.err.println(
    	        "Failed to recover from AOF: " + e.getMessage()
    	    );

    	    e.printStackTrace();
    	    return;
    	}
    	
    	CommandProcessor processor = new CommandProcessor(store, generationManager); //handles validation and maps the commands from the client
    	
    	ExecutorService threadPool = Executors.newFixedThreadPool(5);
    	
    	Object lifecycleLock = new Object();
    	
    	AtomicBoolean shuttingDown = new AtomicBoolean(false);
    	
    	
    	try(ServerSocket server = new ServerSocket(6379)){
    		System.out.println("MiniRedis running on port 6379");
    		
    		Runtime.getRuntime().addShutdownHook(
    				new Thread(() -> {
    					System.out.println("Shutting down MiniRedis");
    					
    					synchronized (lifecycleLock) {
    						
    						shuttingDown.set(true);    						
    						//stops server from accepting any more connections
    						try {
    							server.close();
    						} catch(IOException e) {
    							System.err.println(e.getMessage());
    						}
    					}
    					
    					// threadPool.shutdown() doesn't forcibly terminate running tasks. Solution: close the socket.
    					for(Socket client : clients) {
    						try {
    							client.close();
    						} catch(IOException e) {
    							System.err.println(e.getMessage());
    						}
    					}
    					//don't accept new tasks but let already submitted tasks finish.
    					threadPool.shutdown();
    					
    					try {
    						if (!threadPool.awaitTermination(10, TimeUnit.SECONDS)) {
    							System.err.println("Worker threads did not terminate.");
    							return;
    						}
    						SnapshotManager activeSnapshotManager =
    						        new SnapshotManager(generationManager.getSnapshotPath());

    						activeSnapshotManager.save(store);

    						System.out.println(
    						        "Final snapshot saved successfully in generation "
    						        + generationManager.getActiveGeneration()
    						);
    					} catch (InterruptedException e) {
    						Thread.currentThread().interrupt();
    					} catch (IOException e) {
							System.err.println("Snapshot save failed: " + e.getMessage());
						} 
    				}, "miniredis-shutdown"));
    		
    		try {
    			while(!server.isClosed()) {
        			Socket client = server.accept();
        			
        			synchronized (lifecycleLock) {
        				if(shuttingDown.get()) {
        					client.close();
        					break;
        				}
        				clients.add(client);
        				try {
        					threadPool.execute(new ClientHandler(client, processor, clients));        					
        				} catch(RejectedExecutionException e) {
        					clients.remove(client);
        					client.close();
        					
        					if(!shuttingDown.get()) {
        						throw e;
        					}
        				}
        			}
    			}
    		} catch (SocketException e) {
    			if(!server.isClosed()) {
    				throw e;
    			}
    		}
    	} 
    	finally {
    		threadPool.shutdown();
    	}
    }
}
