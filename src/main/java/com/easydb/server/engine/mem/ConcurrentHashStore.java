package com.easydb.server.engine.mem;

import com.easydb.common.utils.Serializer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class ConcurrentHashStore {

    private final ConcurrentHashMap<String, Object> data = new ConcurrentHashMap<>();

    public void put(String key, String value) {
        Object parsedValue = Serializer.deserialize(value);
        data.put(key, parsedValue);
    }

    public void putObject(String key, Object value) {
        data.put(key, value);
    }

    public Object get(String key) {
        return data.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> clazz) {
        Object value = data.get(key);
        if (value == null) {
            return null;
        }
        if (clazz.isInstance(value)) {
            return clazz.cast(value);
        }
        return Serializer.deserialize(Serializer.serialize(value), clazz);
    }

    public Object remove(String key) {
        return data.remove(key);
    }

    public boolean containsKey(String key) {
        return data.containsKey(key);
    }

    public List<String> keys(String pattern) {
        List<String> result = new ArrayList<>();
        String regex = pattern.replace("*", ".*");
        Pattern p = Pattern.compile(regex);
        
        for (String key : data.keySet()) {
            if (p.matcher(key).matches()) {
                result.add(key);
            }
        }
        return result;
    }

    public int size() {
        return data.size();
    }

    public void clear() {
        data.clear();
    }
}
