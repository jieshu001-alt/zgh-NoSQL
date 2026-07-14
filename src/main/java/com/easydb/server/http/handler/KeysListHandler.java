package com.easydb.server.http.handler;

import com.easydb.server.http.HttpRequest;
import com.easydb.server.http.HttpResponse;
import com.easydb.server.engine.DefaultStoreEngine;
import com.easydb.server.engine.StoreEngine;

import java.util.List;

public class KeysListHandler {

    private final StoreEngine storeEngine = DefaultStoreEngine.getInstance();

    public void handleGet(HttpRequest request, HttpResponse response) throws Exception {
        String pattern = request.getQueryParams().getOrDefault("pattern", "*");
        List<String> keys = storeEngine.keys(pattern);
        response.writeJson(200, "OK", keys);
    }
}
