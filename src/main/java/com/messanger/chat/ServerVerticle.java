package com.messanger.chat;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.vertx.java.core.Handler;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.eventbus.EventBus;
import org.vertx.java.core.http.HttpServerRequest;
import org.vertx.java.core.http.RouteMatcher;
import org.vertx.java.core.http.ServerWebSocket;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.platform.Verticle;

public class ServerVerticle extends Verticle {
	
	Cache cache = new Cache();
	
	@Override
	public void start(){
		//HTTP Server to serve the HTML,JS and CSS resources
		RouteMatcher routeMatcher = getRoute();
		vertx.createHttpServer().requestHandler(routeMatcher).listen(8080,"localhost");

		//Websocket server listens on port 8090 for connections.
		vertx.createHttpServer().websocketHandler(generateSocketHandler()).listen(8090); 
	}
	
//to handle different URLs	
	private RouteMatcher getRoute() {	
		return new RouteMatcher().get("/", new Handler<HttpServerRequest>() {
			public void handle(HttpServerRequest event) {
				event.response().sendFile("web/index.html");
			}
		}).get(".*\\.(css|js)$",new Handler<HttpServerRequest>() {

			public void handle(HttpServerRequest event) {
				event.response().sendFile("web/"+new File(event.path()));
			}
		});
	}

	private Handler<ServerWebSocket> generateSocketHandler() {
		final Logger logger = container.logger();
		final Pattern pattern = Pattern.compile("/chat/(\\w+)"); //identifies individual users
		final EventBus eventBus = vertx.eventBus();
		return new Handler<ServerWebSocket>() {

			public void handle(final ServerWebSocket event) {

			  // reject every request that does not match our specified “path”
				final Matcher m = pattern.matcher(event.path());
				if (!m.matches()) {
					event.reject();
					return;
				}

				final String chatRoom = m.group(1);
				
		/* When a Websocket is created it automatically registers an event handler with the eventbus, the ID of that handler is 
		 * given by textHandlerID.
		 */
				final String id = event.textHandlerID();
				/*Given this ID, a different event loop can send a text frame to that event handler using the event bus and that 
				 * buffer will be received by this instance in its own event loop and written to the underlying connection. 
				 * This allows you to write data to other websockets which are owned by different event loops.		
				 */
				
				logger.info("Registering new connection");
				vertx.sharedData().getSet(chatRoom).add(id);

				event.closeHandler(new Handler<Void>() {
					public void handle(final Void event) {
						logger.info("Unregistering connection id: " + id);
						vertx.sharedData().getSet(chatRoom).remove(id);
					}
				});
				
			 // Set a data handler. As data is read, the handler will be called with the data.
				event.dataHandler(new Handler<Buffer>() {

					public void handle(Buffer buffer) {
					/* This mapper (or, data binder, or codec) provides functionality for converting between Java objects (instances of 
					 * JDK provided core classes, beans), and matching JSON constructs.
					 */
            
						ObjectMapper mapper = new ObjectMapper(); 
						try{
						  /*
						   * Method to deserialize JSON content as tree expressed using set of JsonNode instances. Returns root of the resulting tree
						   *  (where root can consist of just a single node if the current event is a value event, not container).
						   */
							JsonNode rootNode = mapper.readTree(buffer.toString());
							
							//Cache Behavior
							Date date =new Date();
							long storedTime=cache.getTime();
							String storedMessage=cache.getMessage();
							long currentTime=date.getTime();
							String currentMessage=rootNode.get("message").toString();
							cache.setTime(currentTime);
							cache.setMessage(currentMessage);
							
							// check for duplicate messages
							if(checkCondition(storedTime,storedMessage,currentTime,currentMessage)){
								((ObjectNode) rootNode).put("received", date.toString());
								String jsonOutput = mapper.writeValueAsString(rootNode); // serialize Java object as a string
								logger.info("JSON: " + jsonOutput);
								for (Object chatter : vertx.sharedData().getSet(chatRoom)) {
									eventBus.send((String) chatter, jsonOutput);
								}
							}
							
						}catch(IOException e){
							 event.reject();
						}	
					}
					private boolean checkCondition(long storedTime, String storedMessage, long currentTime,
							String currentMessage) {
						if(storedMessage.equals(currentMessage) && (currentTime-storedTime)<= 5000){
							logger.info("Duplicate Rejected");
							return false;
						}
						return true;
					}
				});
				
				
			}
		};
	}
}
