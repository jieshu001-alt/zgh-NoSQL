package com.easydb.server.net;

import com.easydb.common.constants.Constants;

public class RequestDecoder {

    public static String[] decode(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        
        line = line.trim();
        if (line.isEmpty()) {
            return null;
        }
        
        String[] parts = line.split("\\s+");
        if (parts.length == 0) {
            return null;
        }
        
        return parts;
    }

    public static String buildResponse(String data) {
        if (data == null) {
            return Constants.NULL_VALUE + Constants.LINE_SEPARATOR;
        }
        return "\"" + data + "\"" + Constants.LINE_SEPARATOR;
    }

    public static String buildKeysResponse(java.util.List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Constants.LINE_SEPARATOR;
        }
        
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (String key : keys) {
            sb.append(index).append(") \"").append(key).append("\"").append(Constants.LINE_SEPARATOR);
            index++;
        }
        sb.append(Constants.LINE_SEPARATOR);
        return sb.toString();
    }

    public static String buildErrorResponse(String message) {
        return Constants.ERROR_PREFIX + message + Constants.LINE_SEPARATOR;
    }

    public static String buildMultiResponse(java.util.List<String> values) {
        if (values == null || values.isEmpty()) {
            return Constants.LINE_SEPARATOR;
        }
        
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (String value : values) {
            if (value == null) {
                sb.append(index).append(") ").append(Constants.NULL_VALUE).append(Constants.LINE_SEPARATOR);
            } else {
                sb.append(index).append(") \"").append(value).append("\"").append(Constants.LINE_SEPARATOR);
            }
            index++;
        }
        sb.append(Constants.LINE_SEPARATOR);
        return sb.toString();
    }
}
