package com.miniredis.server;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import com.miniredis.command.CommandProcessor;

public class ClientHandler implements Runnable{
	private final Socket client;
	private final CommandProcessor processor;
	private final Set<Socket> clients;
	
	public ClientHandler(Socket client, CommandProcessor processor, Set<Socket> clients) {
		this.client = client;
		this.processor = processor;
		this.clients = clients;
	}

	@Override
	public void run() {
		try(Socket socket = client;
				BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
				PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
				){
			System.out.println("Client connected on thread: " + Thread.currentThread().getName());
			
			String input;
			
			while((input = reader.readLine()) != null) {
				String response = processor.execute(input);
				writer.println(response);
			}
		} catch(IOException e) {
			System.err.println("Client connection error: " + e.getMessage());
		} finally {
			clients.remove(client);
		}
	}	
}
