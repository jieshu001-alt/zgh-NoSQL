package com.easydb.client.cli;

import com.easydb.client.sdk.DatabaseClient;
import com.easydb.common.constants.Constants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class CliSession {

    private DatabaseClient client;
    private Scanner scanner;

    public void start() {
        start(Constants.DEFAULT_HOST, Constants.DEFAULT_SOCKET_PORT);
    }

    public void start(String host, int port) {
        scanner = new Scanner(System.in);
        client = new DatabaseClient(host, port);
        
        try {
            client.connect();
            System.out.println("Connected to Easy-DB server at " + host + ":" + port);
            System.out.println("Type 'help' for available commands.");
        } catch (DatabaseClient.DatabaseException e) {
            System.out.println("Failed to connect: " + e.getMessage());
            return;
        }

        String prompt = "easydb> ";
        while (true) {
            System.out.print(prompt);
            String input = scanner.nextLine();
            if (input == null || input.trim().isEmpty()) {
                continue;
            }
            
            String[] parts = input.trim().split("\\s+");
            String command = parts[0].toUpperCase();
            
            try {
                switch (command) {
                    case "SET":
                        handleSet(parts);
                        break;
                    case "GET":
                        handleGet(parts);
                        break;
                    case "DEL":
                        handleDel(parts);
                        break;
                    case "KEYS":
                        handleKeys(parts);
                        break;
                    case "EXISTS":
                        handleExists(parts);
                        break;
                    case "MSET":
                        handleMSet(parts);
                        break;
                    case "MGET":
                        handleMGet(parts);
                        break;
                    case "MDEL":
                        handleMDel(parts);
                        break;
                    case "HELP":
                        printHelp();
                        break;
                    case "QUIT":
                    case "EXIT":
                        System.out.println("Goodbye!");
                        client.close();
                        return;
                    default:
                        System.out.println("Unknown command. Type 'help' for available commands.");
                }
            } catch (DatabaseClient.DatabaseException e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
    }

    private void handleSet(String[] parts) throws DatabaseClient.DatabaseException {
        if (parts.length < 3) {
            System.out.println("Usage: SET key value");
            return;
        }
        String key = parts[1];
        StringBuilder value = new StringBuilder();
        for (int i = 2; i < parts.length; i++) {
            if (i > 2) {
                value.append(" ");
            }
            value.append(parts[i]);
        }
        String result = client.set(key, value.toString());
        System.out.println(result);
    }

    private void handleGet(String[] parts) throws DatabaseClient.DatabaseException {
        if (parts.length < 2) {
            System.out.println("Usage: GET key");
            return;
        }
        String value = client.get(parts[1]);
        if (value == null) {
            System.out.println("(nil)");
        } else {
            System.out.println("\"" + value + "\"");
        }
    }

    private void handleDel(String[] parts) throws DatabaseClient.DatabaseException {
        if (parts.length < 2) {
            System.out.println("Usage: DEL key");
            return;
        }
        String result = client.del(parts[1]);
        System.out.println(result);
    }

    private void handleKeys(String[] parts) throws DatabaseClient.DatabaseException {
        String pattern = parts.length >= 2 ? parts[1] : "*";
        List<String> keys = client.keys(pattern);
        if (keys.isEmpty()) {
            System.out.println("(empty list or set)");
        } else {
            int idx = 1;
            for (String key : keys) {
                System.out.println(idx + ") \"" + key + "\"");
                idx++;
            }
        }
    }

    private void handleExists(String[] parts) throws DatabaseClient.DatabaseException {
        if (parts.length < 2) {
            System.out.println("Usage: EXISTS key");
            return;
        }
        boolean exists = client.exists(parts[1]);
        System.out.println(exists ? "1" : "0");
    }

    private void handleMSet(String[] parts) throws DatabaseClient.DatabaseException {
        if (parts.length < 4 || (parts.length - 1) % 2 != 0) {
            System.out.println("Usage: MSET key1 value1 key2 value2 ...");
            return;
        }
        Map<String, String> entries = new HashMap<>();
        for (int i = 1; i < parts.length; i += 2) {
            entries.put(parts[i], parts[i + 1]);
        }
        client.mset(entries);
        System.out.println("OK");
    }

    private void handleMGet(String[] parts) throws DatabaseClient.DatabaseException {
        if (parts.length < 2) {
            System.out.println("Usage: MGET key1 key2 ...");
            return;
        }
        List<String> keys = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            keys.add(parts[i]);
        }
        List<String> values = client.mget(keys);
        int idx = 1;
        for (String value : values) {
            System.out.println(idx + ") " + (value != null ? "\"" + value + "\"" : "(nil)"));
            idx++;
        }
    }

    private void handleMDel(String[] parts) throws DatabaseClient.DatabaseException {
        if (parts.length < 2) {
            System.out.println("Usage: MDEL key1 key2 ...");
            return;
        }
        List<String> keys = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            keys.add(parts[i]);
        }
        client.mdel(keys);
        System.out.println("OK");
    }

    private void printHelp() {
        System.out.println("Available commands:");
        System.out.println("  SET key value          - Set a key-value pair");
        System.out.println("  GET key                - Get value by key");
        System.out.println("  DEL key                - Delete a key");
        System.out.println("  KEYS [pattern]         - List keys (default pattern: *)");
        System.out.println("  EXISTS key             - Check if key exists");
        System.out.println("  MSET key1 val1 ...     - Set multiple key-value pairs");
        System.out.println("  MGET key1 key2 ...     - Get multiple values");
        System.out.println("  MDEL key1 key2 ...     - Delete multiple keys");
        System.out.println("  HELP                   - Show this help");
        System.out.println("  QUIT/EXIT              - Exit CLI");
    }
}
