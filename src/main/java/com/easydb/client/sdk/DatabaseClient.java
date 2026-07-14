package com.easydb.client.sdk;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONArray;
import com.easydb.common.constants.Constants;
import com.easydb.common.utils.Serializer;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DatabaseClient {

    private String host;
    private int port;
    private Socket socket;
    private PrintWriter writer;
    private BufferedReader reader;

    public DatabaseClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void connect() throws DatabaseException {
        try {
            socket = new Socket(host, port);
            writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), Constants.ENCODING), true);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), Constants.ENCODING));
        } catch (IOException e) {
            throw new DatabaseException("Failed to connect to server", e);
        }
    }

    public String set(String key, String value) throws DatabaseException {
        ensureConnected();
        writer.println("SET " + key + " " + value);
        return readResponse();
    }

    public String get(String key) throws DatabaseException {
        ensureConnected();
        writer.println("GET " + key);
        String response = readResponse();
        if ("(nil)".equals(response)) {
            return null;
        }
        if (response.startsWith("\"") && response.endsWith("\"")) {
            return response.substring(1, response.length() - 1);
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> clazz) throws DatabaseException {
        String value = get(key);
        if (value == null) {
            return null;
        }
        return Serializer.deserialize(value, clazz);
    }

    public JSONObject getJSONObject(String key) throws DatabaseException {
        return get(key, JSONObject.class);
    }

    public JSONArray getJSONArray(String key) throws DatabaseException {
        return get(key, JSONArray.class);
    }

    public Integer getInt(String key) throws DatabaseException {
        return get(key, Integer.class);
    }

    public Long getLong(String key) throws DatabaseException {
        return get(key, Long.class);
    }

    public Double getDouble(String key) throws DatabaseException {
        return get(key, Double.class);
    }

    public Boolean getBoolean(String key) throws DatabaseException {
        return get(key, Boolean.class);
    }

    public List<String> keys(String pattern) throws DatabaseException {
        ensureConnected();
        writer.println("KEYS " + pattern);
        return readKeysResponse();
    }

    public boolean exists(String key) throws DatabaseException {
        ensureConnected();
        writer.println("EXISTS " + key);
        String response = readResponse();
        return "1".equals(response);
    }

    public String del(String key) throws DatabaseException {
        ensureConnected();
        writer.println("DEL " + key);
        return readResponse();
    }

    public void mset(Map<String, String> entries) throws DatabaseException {
        ensureConnected();
        StringBuilder sb = new StringBuilder("MSET");
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            sb.append(" ").append(entry.getKey()).append(" ").append(entry.getValue());
        }
        writer.println(sb.toString());
        readResponse();
    }

    public List<String> mget(List<String> keys) throws DatabaseException {
        ensureConnected();
        StringBuilder sb = new StringBuilder("MGET");
        for (String key : keys) {
            sb.append(" ").append(key);
        }
        writer.println(sb.toString());
        return readMultiResponse();
    }

    public void mdel(List<String> keys) throws DatabaseException {
        ensureConnected();
        StringBuilder sb = new StringBuilder("MDEL");
        for (String key : keys) {
            sb.append(" ").append(key);
        }
        writer.println(sb.toString());
        readResponse();
    }

    public void createCollection(String name) throws DatabaseException {
        ensureConnected();
        writer.println("CREATE " + name);
        readResponse();
    }

    public String dropCollection(String name) throws DatabaseException {
        ensureConnected();
        writer.println("DROP " + name);
        return readResponse();
    }

    public List<String> listCollections() throws DatabaseException {
        ensureConnected();
        writer.println("COLLECTIONS");
        return readKeysResponse();
    }

    public List<String> keysInCollection(String collectionName) throws DatabaseException {
        ensureConnected();
        writer.println("KEYS " + collectionName + ":*");
        List<String> fullKeys = readKeysResponse();
        List<String> result = new ArrayList<>();
        String prefix = collectionName + ":";
        for (String key : fullKeys) {
            if (key.startsWith(prefix)) {
                result.add(key.substring(prefix.length()));
            }
        }
        return result;
    }

    private String readResponse() throws DatabaseException {
        try {
            String line = reader.readLine();
            if (line == null) {
                throw new DatabaseException("Connection closed");
            }
            return line.trim();
        } catch (IOException e) {
            throw new DatabaseException("Failed to read response", e);
        }
    }

    private List<String> readKeysResponse() throws DatabaseException {
        List<String> keys = new ArrayList<>();
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    break;
                }
                int idx = line.indexOf(") \"");
                if (idx > 0) {
                    String key = line.substring(idx + 3);
                    if (key.endsWith("\"")) {
                        key = key.substring(0, key.length() - 1);
                    }
                    keys.add(key);
                }
            }
        } catch (IOException e) {
            throw new DatabaseException("Failed to read keys response", e);
        }
        return keys;
    }

    private List<String> readMultiResponse() throws DatabaseException {
        List<String> values = new ArrayList<>();
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    break;
                }
                int idx = line.indexOf(") ");
                if (idx > 0) {
                    String value = line.substring(idx + 2);
                    if ("(nil)".equals(value)) {
                        values.add(null);
                    } else if (value.startsWith("\"") && value.endsWith("\"")) {
                        values.add(value.substring(1, value.length() - 1));
                    } else {
                        values.add(value);
                    }
                }
            }
        } catch (IOException e) {
            throw new DatabaseException("Failed to read multi response", e);
        }
        return values;
    }

    private void ensureConnected() throws DatabaseException {
        if (socket == null || socket.isClosed()) {
            connect();
        }
    }

    public void close() {
        try {
            if (reader != null) {
                reader.close();
            }
            if (writer != null) {
                writer.close();
            }
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            // ignore
        }
    }

    public static class DatabaseException extends Exception {
        public DatabaseException(String message) {
            super(message);
        }
        public DatabaseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
