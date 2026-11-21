package org.atpfivt.comparison.engine;

import static org.junit.platform.engine.TestExecutionResult.Status.SUCCESSFUL;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.atpfivt.comparison.engine.MirrorNode.ClassNode;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.reporting.FileEntry;
import org.junit.platform.engine.reporting.ReportEntry;

/**
 * Reports the execution of the mirror tree to the outer Launcher and keeps the platform's contract whatever order the
 * nested events come in: a node is registered before its first event, starts and finishes at most once, a parent
 * starts before and finishes after its children, and a skipped node gets no further events.

 */
final class MirrorReporter {

    static final String NOT_EXECUTED = "not executed by the nested Jupiter run";

    private enum Phase { PENDING, RUNNING, DONE }

    private static final class State {
        boolean registered;
        Phase phase = Phase.PENDING;
    }

    private final EngineExecutionListener listener;
    private final Map<TestDescriptor, State> states = new HashMap<>();

    /** {@code root}: the tree from discovery, already known to the outer Launcher. */
    MirrorReporter(EngineExecutionListener listener, TestDescriptor root) {
        this.listener = listener;
        root.accept(node -> state(node).registered = true);
    }

    /** Registers a dynamic node (and its unregistered ancestors) with the outer Launcher. */
    void registered(TestDescriptor node) {
        State state = state(node);
        if (!state.registered) {
            node.getParent().ifPresent(this::registered);
            state.registered = true;
            listener.dynamicTestRegistered(node);
        }
    }

    void started(TestDescriptor node) {
        State state = state(node);
        if (state.phase != Phase.PENDING) {
            return;
        }
        registered(node);
        state.phase = Phase.RUNNING;
        listener.executionStarted(node);
        if (node instanceof ClassNode classNode) {
            classNode.unsupportedMembers().forEach(unsupported -> skipped(unsupported, unsupported.reason()));
        }
    }

    void skipped(TestDescriptor node, String reason) {
        State state = state(node);
        if (state.phase == Phase.PENDING) {
            registered(node);
            state.phase = Phase.DONE;
            listener.executionSkipped(node, reason);
        }
    }

    /** Reports the node as failed now, unless it is already finished or skipped. */
    void failed(TestDescriptor node, Throwable cause) {
        finishNow(node, TestExecutionResult.failed(cause));
    }

    /** Reports the node as finished with {@code result} now (started first if needed), unless it is done already. */
    void finishNow(TestDescriptor node, TestExecutionResult result) {
        if (isDone(node)) {
            return;
        }
        started(node);
        finished(node, result);
    }

    boolean isRunning(TestDescriptor node) {
        return state(node).phase == Phase.RUNNING;
    }

    /** Finished or skipped: no more events for the node. */
    boolean isDone(TestDescriptor node) {
        return state(node).phase == Phase.DONE;
    }

    void reportingEntryPublished(TestDescriptor node, ReportEntry entry) {
        listener.reportingEntryPublished(node, entry);
    }

    void fileEntryPublished(TestDescriptor node, FileEntry file) {
        listener.fileEntryPublished(node, file);
    }

    /** Finishes a running node: finishes its running children first, reports the never reached ones as skipped. */
    void finished(TestDescriptor node, TestExecutionResult result) {
        State state = state(node);
        if (state.phase != Phase.RUNNING) {
            return;
        }
        for (TestDescriptor child : List.copyOf(node.getChildren())) {
            switch (state(child).phase) {
                case RUNNING -> finished(child, TestExecutionResult.successful());
                case PENDING -> {
                    if (result.getStatus() == SUCCESSFUL) {
                        skipped(child, NOT_EXECUTED);
                    }
                }
                case DONE -> {
                }
            }
        }
        state.phase = Phase.DONE;
        listener.executionFinished(node, result);
    }

    private State state(TestDescriptor node) {
        return states.computeIfAbsent(node, key -> new State());
    }
}
