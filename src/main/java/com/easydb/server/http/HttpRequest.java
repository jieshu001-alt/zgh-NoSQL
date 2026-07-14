package com.easydb.server.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

public class HttpRequest {

    private String method;
    private String path;
    private Map<String, String> headers = new HashMap<>();
    private Map<String, String> queryParams = new HashMap<>();
    private String body;

    public static HttpRequest parse(Socket socket) throws IOException {
        HttpRequest request = new HttpRequest();
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        
        String requestLine = reader.readLine();
        if (requestLine == null) {
            return null;
        }
        
        String[] parts = requestLine.split("\\s+");
        if (parts.length >= 2) {
            request.method = parts[0];
            String fullPath = parts[1];
            
            int queryIndex = fullPath.indexOf('?');
            if (queryIndex > 0) {
                request.path = fullPath.substring(0, queryIndex);
                request.parseQueryParams(fullPath.substring(queryIndex + 1));
            } else {
                request.path = fullPath;
            }
        }
        
        String headerLine;
        while ((headerLine = reader.readLine()) != null) {
            if (headerLine.isEmpty()) {
                break;
            }
            int colonIndex = headerLine.indexOf(':');
            if (colonIndex > 0) {
                String key = headerLine.substring(0, colonIndex).trim().toLowerCase();
                String value = headerLine.substring(colonIndex + 1).trim();
                request.headers.put(key, value);
            }
        }
        
        if (request.headers.containsKey("content-length")) {
            int contentLength = Integer.parseInt(request.headers.get("content-length"));
            char[] bodyBuffer = new char[contentLength];
            reader.read(bodyBuffer);
            request.body = new String(bodyBuffer);
        }
        
        return request;
    }

    private void parseQueryParams(String queryString) {
        String[] params = queryString.split("&");
        for (String param : params) {
            int equalsIndex = param.indexOf('=');
            if (equalsIndex > 0) {
                String key = param.substring(0, equalsIndex);
                String value = param.substring(equalsIndex + 1);
                queryParams.put(key, value);
            }
        }
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public Map<String, String> getQueryParams() {
        return queryParams;
    }

    public String getBody() {
        return body;
    }
}
