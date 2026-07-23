package com.easydb.server.engine.index;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Trie {

    private static class TrieNode {
        ConcurrentHashMap<Character, TrieNode> children = new ConcurrentHashMap<>();
        volatile boolean isEndOfKey = false;
        volatile String key = null;
    }

    private final TrieNode root = new TrieNode();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public void insert(String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        
        lock.writeLock().lock();
        try {
            TrieNode current = root;
            for (int i = 0; i < key.length(); i++) {
                char c = key.charAt(i);
                current.children.computeIfAbsent(c, k -> new TrieNode());
                current = current.children.get(c);
            }
            current.isEndOfKey = true;
            current.key = key;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void delete(String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        
        lock.writeLock().lock();
        try {
            delete(root, key, 0);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private boolean delete(TrieNode node, String key, int index) {
        if (index == key.length()) {
            if (!node.isEndOfKey) {
                return false;
            }
            node.isEndOfKey = false;
            node.key = null;
            return node.children.isEmpty();
        }
        
        char c = key.charAt(index);
        TrieNode child = node.children.get(c);
        if (child == null) {
            return false;
        }
        
        boolean shouldDeleteChild = delete(child, key, index + 1);
        
        if (shouldDeleteChild) {
            node.children.remove(c);
            return node.children.isEmpty() && !node.isEndOfKey;
        }
        
        return false;
    }

    public boolean contains(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        
        lock.readLock().lock();
        try {
            TrieNode current = root;
            for (int i = 0; i < key.length(); i++) {
                char c = key.charAt(i);
                current = current.children.get(c);
                if (current == null) {
                    return false;
                }
            }
            return current.isEndOfKey;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<String> searchByPrefix(String prefix) {
        lock.readLock().lock();
        try {
            List<String> result = new ArrayList<>();
            
            if (prefix == null || prefix.isEmpty()) {
                collectAllKeys(root, result);
                return result;
            }
            
            TrieNode current = root;
            for (int i = 0; i < prefix.length(); i++) {
                char c = prefix.charAt(i);
                current = current.children.get(c);
                if (current == null) {
                    return result;
                }
            }
            
            collectAllKeys(current, result);
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    private void collectAllKeys(TrieNode node, List<String> result) {
        if (node == null) {
            return;
        }
        
        if (node.isEndOfKey && node.key != null) {
            result.add(node.key);
        }
        
        for (TrieNode child : node.children.values()) {
            collectAllKeys(child, result);
        }
    }

    public List<String> searchByPattern(String pattern) {
        lock.readLock().lock();
        try {
            List<String> result = new ArrayList<>();
            
            if (pattern == null || pattern.isEmpty()) {
                return result;
            }
            
            if (!pattern.contains("*")) {
                if (containsInternal(pattern)) {
                    result.add(pattern);
                }
                return result;
            }
            
            if (pattern.startsWith("*") && pattern.endsWith("*")) {
                String substring = pattern.substring(1, pattern.length() - 1);
                return searchBySubstring(substring);
            }
            
            if (pattern.startsWith("*")) {
                String suffix = pattern.substring(1);
                return searchBySuffix(suffix);
            }
            
            if (pattern.endsWith("*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                return searchByPrefixInternal(prefix);
            }
            
            String[] parts = pattern.split("\\*", -1);
            if (parts.length == 2) {
                String prefix = parts[0];
                String suffix = parts[1];
                return searchByPrefixAndSuffix(prefix, suffix);
            }
            
            collectAllKeys(root, result);
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    private boolean containsInternal(String key) {
        TrieNode current = root;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            current = current.children.get(c);
            if (current == null) {
                return false;
            }
        }
        return current.isEndOfKey;
    }

    private List<String> searchByPrefixInternal(String prefix) {
        List<String> result = new ArrayList<>();
        
        TrieNode current = root;
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            current = current.children.get(c);
            if (current == null) {
                return result;
            }
        }
        
        collectAllKeys(current, result);
        return result;
    }

    private List<String> searchBySubstring(String substring) {
        List<String> result = new ArrayList<>();
        collectWithSubstring(root, "", substring, result);
        return result;
    }

    private void collectWithSubstring(TrieNode node, String current, String substring, List<String> result) {
        if (node == null) {
            return;
        }
        
        if (node.isEndOfKey && node.key != null && node.key.contains(substring)) {
            result.add(node.key);
        }
        
        for (char c : node.children.keySet()) {
            collectWithSubstring(node.children.get(c), current + c, substring, result);
        }
    }

    private List<String> searchBySuffix(String suffix) {
        List<String> result = new ArrayList<>();
        collectAllKeys(root, result);
        result.removeIf(key -> !key.endsWith(suffix));
        return result;
    }

    private List<String> searchByPrefixAndSuffix(String prefix, String suffix) {
        List<String> result = searchByPrefixInternal(prefix);
        result.removeIf(key -> !key.endsWith(suffix));
        return result;
    }

    public int size() {
        lock.readLock().lock();
        try {
            List<String> keys = new ArrayList<>();
            collectAllKeys(root, keys);
            return keys.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            root.children.clear();
            root.isEndOfKey = false;
            root.key = null;
        } finally {
            lock.writeLock().unlock();
        }
    }
}