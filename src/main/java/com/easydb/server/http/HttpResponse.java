package com.easydb.server.http;

import com.alibaba.fastjson.JSON;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class HttpResponse {

    private final Socket socket;
    private final OutputStream outputStream;
    private int statusCode = 200;
    private Map<String, String> headers = new HashMap<>();

    public HttpResponse(Socket socket) throws Exception {
        this.socket = socket;
        this.outputStream = socket.getOutputStream();
        headers.put("Content-Type", "application/json; charset=UTF-8");
        headers.put("Server", "Easy-DB/1.0");
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public void setHeader(String key, String value) {
        headers.put(key, value);
    }

    public void writeJson(int code, String message, Object data) throws Exception {
        Map<String, Object> response = new HashMap<>();
        response.put("code", code);
        response.put("message", message);
        response.put("data", data);
        
        String json = JSON.toJSONString(response);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        headers.put("Content-Length", String.valueOf(jsonBytes.length));
        
        writeHeaders();
        outputStream.write(jsonBytes);
        outputStream.flush();
    }

    public void writeError(int code, String message) throws Exception {
        writeJson(code, message, null);
    }

    public void writeSuccess(String data) throws Exception {
        writeJson(200, "OK", JSON.parse(data));
    }

    private void writeHeaders() throws Exception {
        StringBuilder sb = new StringBuilder();
        String statusText = getStatusText(statusCode);
        sb.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusText).append("\r\n");
        
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        
        sb.append("\r\n");
        outputStream.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    private String getStatusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 500: return "Internal Server Error";
            default: return "Unknown";
        }
    }

    public void close() {
        try {
            outputStream.close();
            socket.close();
        } catch (Exception e) {
            // ignore
        }
    }
}
