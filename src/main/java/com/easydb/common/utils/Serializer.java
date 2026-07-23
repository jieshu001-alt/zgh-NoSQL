package com.easydb.common.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

public class Serializer {

    public static String serialize(Object obj) {
        if (obj == null) {
            return "null";
        }
        if (obj instanceof String) {
            return (String) obj;
        }
        if (obj instanceof Number || obj instanceof Boolean) {
            return String.valueOf(obj);
        }
        return JSON.toJSONString(obj);
    }

    public static Object deserialize(String str) {
        if (str == null || str.isEmpty() || "null".equalsIgnoreCase(str)) {
            return null;
        }
        
        str = str.trim();
        
        try {
            if (str.startsWith("{") && str.endsWith("}")) {
                return JSON.parseObject(str, JSONObject.class);
            } else if (str.startsWith("[") && str.endsWith("]")) {
                return JSON.parseArray(str);
            }
        } catch (Exception e) {
        }
        
        try {
            return Integer.parseInt(str);
        } catch (NumberFormatException e) {
        }
        
        try {
            return Long.parseLong(str);
        } catch (NumberFormatException e) {
        }
        
        try {
            return Double.parseDouble(str);
        } catch (NumberFormatException e) {
        }
        
        if ("true".equalsIgnoreCase(str)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(str)) {
            return Boolean.FALSE;
        }
        
        return str;
    }

    public static byte[] serializeToBytes(Object obj) {
        return ByteUtils.toBytes(serialize(obj));
    }

    public static Object deserializeFromBytes(byte[] bytes) {
        return deserialize(ByteUtils.toString(bytes));
    }

    @SuppressWarnings("unchecked")
    public static <T> T deserialize(String str, Class<T> clazz) {
        if (str == null || str.isEmpty()) {
            return null;
        }
        
        Object obj = deserialize(str);
        
        if (clazz.isInstance(obj)) {
            return clazz.cast(obj);
        }
        
        try {
            if (clazz == Integer.class || clazz == int.class) {
                return clazz.cast(Integer.parseInt(str.trim()));
            }
            if (clazz == Long.class || clazz == long.class) {
                return clazz.cast(Long.parseLong(str.trim()));
            }
            if (clazz == Double.class || clazz == double.class) {
                return clazz.cast(Double.parseDouble(str.trim()));
            }
            if (clazz == Boolean.class || clazz == boolean.class) {
                return clazz.cast(Boolean.parseBoolean(str.trim()));
            }
            if (clazz == String.class) {
                return clazz.cast(str);
            }
            if (clazz == JSONObject.class) {
                return clazz.cast(JSON.parseObject(str));
            }
            if (clazz == JSONArray.class) {
                return clazz.cast(JSON.parseArray(str));
            }
        } catch (Exception e) {
        }
        
        return null;
    }
}
