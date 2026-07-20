package com.glivt.command;

public enum CommandStatus {
    REQUESTED,
    SENT,
    DELIVERED,
    ACKNOWLEDGED,
    FAILED,
    TIMED_OUT
}
