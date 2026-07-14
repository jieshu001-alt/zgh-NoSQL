package com.easydb.server.http.handler;

import com.alibaba.fastjson.JSONObject;
import com.easydb.server.http.HttpRequest;
import com.easydb.server.http.HttpResponse;
import com.easydb.server.engine.DefaultStoreEngine;
import com.easydb.server.engine.StoreEngine;

public class KeyHandler {

    private final StoreEngine storeEngine = DefaultStoreEngine.getInstance();

    public void handlePost(HttpRequest request, HttpResponse response, String key) throws Exception {
        String body = request.getBody();
        if (body == null || body.isEmpty()) {
            response.writeError(400, "Missing request body");
            return;
        }
        
        JSONObject json = JSONObject.parseObject(body);
        if (!json.containsKey("value")) {
            response.writeError(400, "Missing 'value' field");
            return;
        }
        
        String value = json.getString("value");
        storeEngine.set(key, value);
        response.writeJson(200, "OK", value);
    }

    public void handleGet(HttpRequest request, HttpResponse response, String key) throws Exception {
        String value = storeEngine.get(key);
        if (value == null) {
            response.writeError(404, "Key not found");
        } else {
            response.writeJson(200, "OK", value);
        }
    }

    public void handleDelete(HttpRequest request, HttpResponse response, String key) throws Exception {
        String result = storeEngine.del(key);
        if ("(nil)".equals(result)) {
            response.writeError(404, "Key not found");
        } else {
            response.writeJson(200, "OK", null);
        }
    }
}
