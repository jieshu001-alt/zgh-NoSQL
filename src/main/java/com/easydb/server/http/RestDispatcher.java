package com.easydb.server.http;

import com.easydb.server.http.handler.BatchHandler;
import com.easydb.server.http.handler.KeyHandler;
import com.easydb.server.http.handler.KeysListHandler;

import java.net.Socket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RestDispatcher {

    private static final Pattern KEY_PATTERN = Pattern.compile("^/api/v1/keys/([^/]+)$");
    private static final Pattern KEYS_LIST_PATTERN = Pattern.compile("^/api/v1/keys$");
    private static final Pattern BATCH_PATTERN = Pattern.compile("^/api/v1/batch$");
    
    private final KeyHandler keyHandler = new KeyHandler();
    private final KeysListHandler keysListHandler = new KeysListHandler();
    private final BatchHandler batchHandler = new BatchHandler();

    public void dispatch(Socket socket) {
        HttpResponse response = null;
        try {
            HttpRequest request = HttpRequest.parse(socket);
            response = new HttpResponse(socket);
            
            if (request == null || request.getMethod() == null) {
                response.writeError(400, "Invalid request");
                response.close();
                return;
            }
            
            String method = request.getMethod().toUpperCase();
            String path = request.getPath();
            
            Matcher keyMatcher = KEY_PATTERN.matcher(path);
            Matcher keysListMatcher = KEYS_LIST_PATTERN.matcher(path);
            Matcher batchMatcher = BATCH_PATTERN.matcher(path);
            
            if (keyMatcher.matches()) {
                String key = keyMatcher.group(1);
                handleKeyRequest(method, request, response, key);
            } else if (keysListMatcher.matches()) {
                handleKeysListRequest(method, request, response);
            } else if (batchMatcher.matches()) {
                handleBatchRequest(method, request, response);
            } else {
                response.writeError(404, "Not found");
            }
            
            response.close();
        } catch (Exception e) {
            try {
                if (response != null) {
                    response.writeError(500, "Internal server error");
                    response.close();
                } else {
                    socket.close();
                }
            } catch (Exception ex) {
                // ignore
            }
        }
    }

    private void handleKeyRequest(String method, HttpRequest request, HttpResponse response, String key) throws Exception {
        switch (method) {
            case "POST":
                keyHandler.handlePost(request, response, key);
                break;
            case "GET":
                keyHandler.handleGet(request, response, key);
                break;
            case "DELETE":
                keyHandler.handleDelete(request, response, key);
                break;
            default:
                response.writeError(400, "Method not allowed");
        }
    }

    private void handleKeysListRequest(String method, HttpRequest request, HttpResponse response) throws Exception {
        if ("GET".equals(method)) {
            keysListHandler.handleGet(request, response);
        } else {
            response.writeError(400, "Method not allowed");
        }
    }

    private void handleBatchRequest(String method, HttpRequest request, HttpResponse response) throws Exception {
        switch (method) {
            case "POST":
                batchHandler.handleMSet(request, response);
                break;
            case "GET":
                batchHandler.handleMGet(request, response);
                break;
            case "DELETE":
                batchHandler.handleMDel(request, response);
                break;
            default:
                response.writeError(400, "Method not allowed");
        }
    }
}
