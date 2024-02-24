package app_kvServer;

import shared.messages.SimpleKVMessage;
import shared.messages.KVMessage.StatusType;

import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.IOException;
import java.net.SocketException;

import org.apache.log4j.Logger;
import org.apache.log4j.Level;

import java.io.InputStream;
import java.io.OutputStream;

import shared.messages.KVMessage;
import shared.messages.SimpleKVCommunication;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigInteger;

public class ClientHandler implements Runnable {
    private Socket clientSocket;
    private KVServer server; 
    private boolean isOpen;
    private InputStream input;
    private OutputStream output;

    private static final Logger LOGGER = Logger.getRootLogger();

    private String[] nodeHashRange; 


    public ClientHandler(Socket socket, KVServer server, String[] keyRange) {
        this.clientSocket = socket;
        this.server = server; 
        this.isOpen = true;

        // Assuming server has a method to get nodeHashRange as String[]
        this.nodeHashRange = keyRange; 
    }

    private String hashKey(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] messageDigest = md.digest(key.getBytes());
            BigInteger no = new BigInteger(1, messageDigest);
            while (no.toString(16).length() < 32) {
                no = new BigInteger("0" + no.toString(16), 16);
            }
            return no.toString(16);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isKeyInRange(String keyHash) {
        BigInteger hash = new BigInteger(keyHash, 16);
        BigInteger lowEnd = new BigInteger(nodeHashRange[0], 16);
        BigInteger highEnd = new BigInteger(nodeHashRange[1], 16);
    
        if (lowEnd.compareTo(highEnd) < 0) {
            // Normal range, not wrapping the hash ring
            return hash.compareTo(lowEnd) >= 0 && hash.compareTo(highEnd) < 0;
        } else {
            // The range wraps around the hash ring
            return hash.compareTo(lowEnd) >= 0 || hash.compareTo(highEnd) < 0;
        }
    }
    

    @Override
    public void run() {
        try {
            output = clientSocket.getOutputStream();
            input = clientSocket.getInputStream();

            while (isOpen) {
                try {
                    SimpleKVMessage responseMessage = null;

                    String msg = SimpleKVCommunication.receiveMessage(input, LOGGER);
                    SimpleKVMessage requestMessage = SimpleKVCommunication.parseMessage(msg, LOGGER);
                    String keyHash = hashKey(requestMessage.getKey());

                    if (server.isKeyInRange(keyHash)) {
                        switch (requestMessage.getStatus()) {
                            case PUT:
                                try {
                                    StatusType responseType;
                                    if (requestMessage.getValue() == null) { // DELETE operation
                                        LOGGER.info("\n ...DELETE IN PROGRESS... \n");
                                        if (server.inStorage(requestMessage.getKey()) || server.inCache(requestMessage.getKey())) {
                                            server.putKV(requestMessage.getKey(), null);
                                            responseType = StatusType.DELETE_SUCCESS;
                                            LOGGER.info("Processed DELETE for key: " + requestMessage.getKey());
                                        } else {
                                            responseType = StatusType.DELETE_ERROR; // Key not found for deletion
                                            LOGGER.info("DELETE request failed for key: " + requestMessage.getKey() + ": key not found");
                                        }
                                    } else { // PUT operation
                                        server.putKV(requestMessage.getKey(), requestMessage.getValue());
                                        responseType = server.inStorage(requestMessage.getKey()) ? StatusType.PUT_UPDATE : StatusType.PUT_SUCCESS;
                                    }
                                    responseMessage = new SimpleKVMessage(responseType, requestMessage.getKey(), requestMessage.getValue());
                                } catch (Exception e) {
                                    LOGGER.log(Level.ERROR, "Error processing put request", e);
                                    responseMessage = new SimpleKVMessage(StatusType.PUT_ERROR, null, null);
                                }
                                break;
                            case GET:
                                try {
                                    String response = server.getKV(requestMessage.getKey());
                                    StatusType responseType = (response != null) ? StatusType.GET_SUCCESS : StatusType.GET_ERROR;
                                    responseMessage = new SimpleKVMessage(responseType, requestMessage.getKey(), response);
                                    LOGGER.info("Processed GET request for key: " + requestMessage.getKey() + " with value: " + response);
                                } catch (Exception e) {
                                    LOGGER.log(Level.ERROR, "Error processing get request", e);
                                    responseMessage = new SimpleKVMessage(StatusType.GET_ERROR, null, null);
                                }
                                break;

                            default:
                                LOGGER.info("Received neither PUT or GET.");
                                break;
                        }
                        if (responseMessage != null) { // Only send a response if responseMessage was set
                            SimpleKVCommunication.sendMessage(responseMessage, output, LOGGER);
                            LOGGER.info("responseString: " + responseMessage.getMsg());
                        }
                    } else {
                        // Server not responsible, respond with error and metadata
                        responseMessage = new SimpleKVMessage(StatusType.SERVER_NOT_RESPONSIBLE, null, null);
                        SimpleKVCommunication.sendMessage(responseMessage, output, LOGGER);
                    }

                } catch (SocketException se) {
                    LOGGER.info("Client disconnected.");
                    isOpen = false;
                    break; // Break out of the loop
                } catch (IOException ioe) {
                    LOGGER.log(Level.ERROR, "Error! Connection Lost!", ioe);
                    isOpen = false;
                    break; // Break out of the loop
                }
            }
            LOGGER.info("Client has closed the connection. Close listening client socket.");
        } catch (IOException e) {
            LOGGER.log(Level.ERROR, "Error in ClientHandler", e);
        } finally {
            try {
                if (!clientSocket.isClosed()) {
                    clientSocket.close();
                }
            } catch (IOException e) {
                LOGGER.log(Level.ERROR, "Error closing client socket", e);
            }
        }
    }

    
}