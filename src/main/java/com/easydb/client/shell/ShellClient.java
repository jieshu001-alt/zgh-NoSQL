package com.easydb.client.shell;

import com.easydb.client.sdk.DatabaseClient;
import com.easydb.common.constants.Constants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ShellClient {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: shell-client <command> [args...]");
            System.exit(1);
        }

        String host = System.getenv("EASY_DB_HOST");
        if (host == null || host.isEmpty()) {
            host = Constants.DEFAULT_HOST;
        }

        String portStr = System.getenv("EASY_DB_PORT");
        int port = Constants.DEFAULT_SOCKET_PORT;
        if (portStr != null && !portStr.isEmpty()) {
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                // use default
            }
        }

        DatabaseClient client = new DatabaseClient(host, port);
        try {
            client.connect();
            
            String command = args[0].toUpperCase();
            switch (command) {
                case "SET":
                    if (args.length < 3) {
                        System.err.println("Usage: SET key value");
                        System.exit(1);
                    }
                    String key = args[1];
                    StringBuilder valueBuilder = new StringBuilder();
                    for (int i = 2; i < args.length; i++) {
                        if (i > 2) {
                            valueBuilder.append(" ");
                        }
                        valueBuilder.append(args[i]);
                    }
                    System.out.println(client.set(key, valueBuilder.toString()));
                    break;
                case "GET":
                    if (args.length < 2) {
                        System.err.println("Usage: GET key");
                        System.exit(1);
                    }
                    String getValue = client.get(args[1]);
                    if (getValue == null) {
                        System.out.println("(nil)");
                    } else {
                        System.out.println(getValue);
                    }
                    break;
                case "DEL":
                    if (args.length < 2) {
                        System.err.println("Usage: DEL key");
                        System.exit(1);
                    }
                    System.out.println(client.del(args[1]));
                    break;
                case "KEYS":
                    String pattern = args.length >= 2 ? args[1] : "*";
                    List<String> keys = client.keys(pattern);
                    for (String k : keys) {
                        System.out.println(k);
                    }
                    break;
                case "EXISTS":
                    if (args.length < 2) {
                        System.err.println("Usage: EXISTS key");
                        System.exit(1);
                    }
                    System.out.println(client.exists(args[1]) ? "1" : "0");
                    break;
                case "MSET":
                    if (args.length < 4 || (args.length - 1) % 2 != 0) {
                        System.err.println("Usage: MSET key1 value1 key2 value2 ...");
                        System.exit(1);
                    }
                    Map<String, String> entries = new HashMap<>();
                    for (int i = 1; i < args.length; i += 2) {
                        entries.put(args[i], args[i + 1]);
                    }
                    client.mset(entries);
                    System.out.println("OK");
                    break;
                case "MGET":
                    if (args.length < 2) {
                        System.err.println("Usage: MGET key1 key2 ...");
                        System.exit(1);
                    }
                    List<String> mgetKeys = new ArrayList<>();
                    for (int i = 1; i < args.length; i++) {
                        mgetKeys.add(args[i]);
                    }
                    List<String> values = client.mget(mgetKeys);
                    for (String value : values) {
                        System.out.println(value != null ? value : "(nil)");
                    }
                    break;
                case "MDEL":
                    if (args.length < 2) {
                        System.err.println("Usage: MDEL key1 key2 ...");
                        System.exit(1);
                    }
                    List<String> mdelKeys = new ArrayList<>();
                    for (int i = 1; i < args.length; i++) {
                        mdelKeys.add(args[i]);
                    }
                    client.mdel(mdelKeys);
                    System.out.println("OK");
                    break;
                case "CREATE":
                    if (args.length < 2) {
                        System.err.println("Usage: CREATE collection");
                        System.exit(1);
                    }
                    client.createCollection(args[1]);
                    System.out.println("OK");
                    break;
                case "DROP":
                    if (args.length < 2) {
                        System.err.println("Usage: DROP collection");
                        System.exit(1);
                    }
                    System.out.println(client.dropCollection(args[1]));
                    break;
                case "COLLECTIONS":
                    List<String> collections = client.listCollections();
                    for (String coll : collections) {
                        System.out.println(coll);
                    }
                    break;
                default:
                    System.err.println("Unknown command: " + command);
                    System.exit(1);
            }
            
            System.exit(0);
        } catch (DatabaseClient.DatabaseException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        } finally {
            client.close();
        }
    }
}
