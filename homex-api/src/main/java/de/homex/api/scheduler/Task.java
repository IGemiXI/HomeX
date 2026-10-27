package de.homex.api.scheduler;

public interface Task {
    void cancel();
    boolean isCancelled();
}
