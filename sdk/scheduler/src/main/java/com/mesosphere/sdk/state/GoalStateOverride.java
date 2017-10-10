package com.mesosphere.sdk.state;

import java.util.Arrays;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.slf4j.LoggerFactory;

import com.mesosphere.sdk.specification.GoalState;

/**
 * The definition of a goal state override for a task. These are custom states, other than {@link GoalState}s, which a
 * task may enter. The main difference between this and {@link GoalState} is that {@link GoalState}s are configured by
 * the service developer, whereas {@link GoalStateOverride}s are applied by the operator.
 */
public enum GoalStateOverride {

    /** The definition of the default no-override state. Refer to the task's {@link GoalState} setting. */
    NONE(null, "STARTING"),
    /** The definition of the "STOPPED" override state, where commands are replaced with sleep()s.*/
    STOPPED("STOPPED", "STOPPING");

    /**
     * The state of the override itself.
     */
    public enum Progress {
        /**
         * The desired override (or lack of override) has ben set, but relaunching the task hasn't occurred yet.
         * In practice this state should only appear very briefly.
         */
        PENDING("PENDING"),

        /**
         * The override (or lack of override) has started processing but hasn't finished taking effect.
         */
        IN_PROGRESS("IN_PROGRESS"),

        /**
         * The override (or lack of override) has been committed.
         */
        COMPLETE("COMPLETE");

        private final String serializedName;

        private Progress(String serializedName) {
            this.serializedName = serializedName;
        }

        /**
         * The label which overrides in this state are given. For example "RUNNING" or "STOPPED". This is stored in task
         * state storage.
         *
         * <p>WARNING: THIS IS STORED IN ZOOKEEPER TASK METADATA AND THEREFORE CANNOT EASILY BE CHANGED
         */
        public String getSerializedName() {
            return serializedName;
        }
    }

    /**
     * Describes the current state of an override.
     *
     * The state of the override itself. Sample flow for enabling and disabling an override:
     *
     * <ol><li>NONE + COMPLETE (!isActive())</li>
     * <li>STOPPED + PENDING</li>
     * <li>STOPPED + IN_PROGRESS</li>
     * <li>STOPPED + COMPLETE</li>
     * <li>NONE + PENDING</li>
     * <li>NONE + IN_PROGRESS</li>
     * <li>NONE + COMPLETE (!isActive())</li></ol>
     */
    public static class Status {

        /**
         * The status of a task for which no overrides are applicable.
         * The task is not entering, exiting, or currently in an override state.
         */
        public static final Status INACTIVE = new Status(GoalStateOverride.NONE, Progress.COMPLETE);

        /**
         * The target override state for this task. May be {@link GoalStateOverride#NONE} in the case of no override.
         */
        public final GoalStateOverride target;

        /**
         * The current state for transitioning to the {@link #target} in question. May be {@link Progress#NONE} in the
         * case of no override being applicable.
         */
        public final Progress progress;

        private Status(GoalStateOverride target, Progress progress) {
            this.target = target;
            this.progress = progress;
        }

        @Override
        public boolean equals(Object o) {
            LoggerFactory.getLogger(GoalStateOverride.class).error("{} vs {}", this, o); // TODO TEMP
            return EqualsBuilder.reflectionEquals(this, o);
        }

        @Override
        public int hashCode() {
            return HashCodeBuilder.reflectionHashCode(this);
        }
    }

    public Status newStatus(Progress progress) {
        return new Status(this, progress);
    }

    private final String serializedName;
    private final String pendingName;

    private GoalStateOverride(String serializedName, String pendingName) {
        // Validation: Overrides may not overlap with existing GoalStates
        for (GoalState goalState : GoalState.values()) {
            if (goalState.toString().equals(serializedName)) {
                throw new IllegalArgumentException(String.format(
                        "Provided GoalStateOverride serialized name '%s' collides with an existing GoalState=%s",
                        serializedName, Arrays.asList(GoalState.values())));
            }
        }
        this.serializedName = serializedName;
        this.pendingName = pendingName;
    }

    /**
     * The label which tasks in this state are given. For example "RUNNING" or "STOPPED". This is shown to users and
     * stored in task state storage.
     *
     * <p>WARNING: THIS IS STORED IN ZOOKEEPER TASK METADATA AND THEREFORE CANNOT EASILY BE CHANGED
     */
    public String getSerializedName() {
        return serializedName;
    }

    /**
     * The state which tasks which are in the process of entering this state are given. For example "STARTING" or
     * "STOPPING". This is shown to users but not stored anywhere.
     */
    public String getTransitioningName() {
        return pendingName;
    }
}
