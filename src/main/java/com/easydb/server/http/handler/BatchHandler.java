package com.easydb.server.http.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.easydb.server.engine.DefaultStoreEngine;
import com.easydb.server.engine.StoreEngine;
import com.easydb.server.http.HttpRequest;
import com.easydb.server.http.HttpResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BatchHandler {

    private final StoreEngine storeEngine = DefaultStoreEngine.getInstance();

    public void handleMSet(HttpRequest request, HttpResponse response) throws Exception {
        String body = request.getBody();
        if (body == null || body.isEmpty()) {
            response.writeError(400, "Missing request body");
            return;
        }
        
        try {
            JSONObject json = JSON.parseObject(body);
            Map<String, String> entries = json.getInnerMap();
            storeEngine.batchSet(entries);
            response.writeSuccess("{\"status\":\"OK\"}");
        } catch (Exception e) {
            response.writeError(400, "Invalid JSON format");
        }
    }

    public void handleMGet(HttpRequest request, HttpResponse response) throws Exception {
        String body = request.getBody();
        if (body == null || body.isEmpty()) {
            response.writeError(400, "Missing request body");
            return;
        }
        
        try {
            JSONObject json = JSON.parseObject(body);
            List<String> keys = json.getJSONArray("keys").toJavaList(String.class);
            List<String> values = storeEngine.batchGet(keys);
            
            JSONObject result = new JSONObject();
            for (int i = 0; i < keys.size(); i++) {
                result.put(keys.get(i), values.get(i));
            }
            response.writeSuccess(result.toJSONString());
        } catch (Exception e) {
            response.writeError(400, "Invalid JSON format");
        }
    }

    public void handleMDel(HttpRequest request, HttpResponse response) throws Exception {
        String body = request.getBody();
        if (body == null || body.isEmpty()) {
            response.writeError(400, "Missing request body");
            return;
        }
        
        try {
            JSONObject json = JSON.parseObject(body);
            List<String> keys = json.getJSONArray("keys").toJavaList(String.class);
            storeEngine.batchDel(keys);
            response.writeSuccess("{\"status\":\"OK\"}");
        } catch (Exception e) {
            response.writeError(400, "Invalid JSON format");
        }
    }
}
