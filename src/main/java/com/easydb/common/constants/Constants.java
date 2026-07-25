package com.easydb.common.constants;

public class Constants {

    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_SOCKET_PORT = 8092;
    public static final int DEFAULT_HTTP_PORT = 8093;
    
    public static final String ENCODING = "UTF-8";
    public static final String LINE_SEPARATOR = "\r\n";
    public static final String NULL_VALUE = "(nil)";
    public static final String OK_RESPONSE = "OK";
    public static final String ERROR_PREFIX = "(error) ERR ";
    
    public static final String DATA_DIR = "./data";
    public static final String WAL_FILE_SUFFIX = ".wal";
    public static final String DATA_FILE_SUFFIX = ".data";
    public static final String GZ_FILE_SUFFIX = ".gz";
    
    public static final long DATA_FILE_MAX_SIZE = 64 * 1024 * 1024;
    
    public static final String COMMAND_SET = "SET";
    public static final String COMMAND_GET = "GET";
    public static final String COMMAND_DEL = "DEL";
    public static final String COMMAND_KEYS = "KEYS";
    public static final String COMMAND_EXISTS = "EXISTS";
    public static final String COMMAND_MSET = "MSET";
    public static final String COMMAND_MGET = "MGET";
    public static final String COMMAND_MDEL = "MDEL";
    public static final String COMMAND_CREATE = "CREATE";
    public static final String COMMAND_DROP = "DROP";
    public static final String COMMAND_COLLECTIONS = "COLLECTIONS";
    public static final String COMMAND_COLL_SET = "C_SET";
    public static final String COMMAND_COLL_GET = "C_GET";
    public static final String COMMAND_COLL_DEL = "C_DEL";
    public static final String COMMAND_COLL_KEYS = "C_KEYS";
    
    // List commands
    public static final String COMMAND_LPUSH = "LPUSH";
    public static final String COMMAND_RPUSH = "RPUSH";
    public static final String COMMAND_LPOP = "LPOP";
    public static final String COMMAND_RPOP = "RPOP";
    public static final String COMMAND_LLEN = "LLEN";
    
    // Set commands
    public static final String COMMAND_SADD = "SADD";
    public static final String COMMAND_SMEMBERS = "SMEMBERS";
    public static final String COMMAND_SREM = "SREM";
    
    // Hash commands
    public static final String COMMAND_HSET = "HSET";
    public static final String COMMAND_HGET = "HGET";
    public static final String COMMAND_HDEL = "HDEL";
    public static final String COMMAND_HGETALL = "HGETALL";
    
    public static final String COLLECTION_SEPARATOR = ":";
    public static final int COLLECTION_NAME_MAX_LENGTH = 32;
    
    public static final int COMPACTOR_CORE_THREADS = 2;
    
    public static final int DEFAULT_CLUSTER_PORT = 8094;
    public static final int DEFAULT_NODE_ID = 1;
}
