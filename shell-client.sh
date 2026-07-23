#!/bin/bash

EASY_DB_JAR="target/easy-db-1.0.0-jar-with-dependencies.jar"

if [ ! -f "$EASY_DB_JAR" ]; then
    echo "Error: $EASY_DB_JAR not found. Please run 'mvn package' first."
    exit 1
fi

if [ $# -lt 1 ]; then
    echo "Usage: $0 <command> [args...]"
    echo "Commands: SET, GET, DEL, KEYS, EXISTS"
    exit 1
fi

java -jar "$EASY_DB_JAR" --shell "$@"