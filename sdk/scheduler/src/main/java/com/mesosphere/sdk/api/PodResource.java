package com.mesosphere.sdk.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;

import com.mesosphere.sdk.api.types.PrettyJsonResource;
import com.mesosphere.sdk.api.types.TaskInfoAndStatus;
import com.mesosphere.sdk.offer.TaskException;
import com.mesosphere.sdk.offer.taskdata.TaskLabelReader;
import com.mesosphere.sdk.scheduler.TaskKiller;
import com.mesosphere.sdk.scheduler.recovery.RecoveryType;
import com.mesosphere.sdk.specification.PodInstance;
import com.mesosphere.sdk.state.GoalStateOverride;
import com.mesosphere.sdk.state.StateStore;

import org.apache.commons.lang3.StringUtils;
import org.apache.mesos.Protos;
import org.apache.mesos.Protos.TaskID;
import org.apache.mesos.Protos.TaskInfo;
import org.apache.mesos.Protos.TaskStatus;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.mesosphere.sdk.api.ResponseUtils.jsonOkResponse;
import static com.mesosphere.sdk.api.ResponseUtils.jsonResponseBean;

/**
 * A read-only API for accessing information about how to connect to the service.
 */
@Singleton
@Path("/v1/pod")
public class PodResource extends PrettyJsonResource {
    private static final Logger LOGGER = LoggerFactory.getLogger(PodResource.class);

    /**
     * Pod 'name' to use in responses for tasks which have no pod information.
     */
    private static final String UNKNOWN_POD_LABEL = "UNKNOWN_POD";

    private final StateStore stateStore;

    private TaskKiller taskKiller;

    /**
     * Creates a new instance which retrieves task/pod state from the provided {@link StateStore}.
     */
    public PodResource(StateStore stateStore) {
        this.stateStore = stateStore;
    }

    /**
     * Configures the {@link TaskKiller} instance to be invoked when restart/replace operations are invoked.
     */
    public void setTaskKiller(TaskKiller taskKiller) {
        this.taskKiller = taskKiller;
    }

    /**
     * Produces a listing of all pod instance names.
     */
    @GET
    public Response getPods() {
        try {
            Set<String> podNames = new TreeSet<>();
            List<String> unknownTaskNames = new ArrayList<>();
            for (TaskInfo taskInfo : stateStore.fetchTasks()) {
                Optional<String> podNameOptional = getPodInstanceName(taskInfo);
                if (podNameOptional.isPresent()) {
                    podNames.add(podNameOptional.get());
                } else {
                    unknownTaskNames.add(taskInfo.getName());
                }
            }
            JSONArray jsonArray = new JSONArray(podNames);
            if (!unknownTaskNames.isEmpty()) {
                Collections.sort(unknownTaskNames);
                for (String unknownName : unknownTaskNames) {
                    jsonArray.put(String.format("%s_%s", UNKNOWN_POD_LABEL, unknownName));
                }
            }
            return jsonOkResponse(jsonArray);
        } catch (Exception e) {
            LOGGER.error("Failed to fetch list of pods", e);
            return Response.serverError().build();
        }
    }

    /**
     * Produces the summary statuses of all pod instances.
     */
    @Path("/status")
    @GET
    public Response getPodStatuses() {
        try {
            // Group the tasks by pod:
            GroupedTasks groupedTasks = GroupedTasks.create(stateStore);

            // Output statuses for all tasks in each pod:
            JSONObject json = new JSONObject();
            for (Map.Entry<String, List<TaskInfoAndStatus>> podTasks : groupedTasks.byPod.entrySet()) {
                json.put(podTasks.getKey(), getStatusesJson(stateStore, podTasks.getValue()));
            }

            // Output 'unknown pod' for any tasks which didn't have a resolvable pod:
            if (!groupedTasks.unknownPod.isEmpty()) {
                json.put(UNKNOWN_POD_LABEL, getStatusesJson(stateStore, groupedTasks.unknownPod));
            }

            return jsonOkResponse(json);
        } catch (Exception e) {
            LOGGER.error("Failed to fetch collated list of task statuses by pod", e);
            return Response.serverError().build();
        }
    }

    /**
     * Produces the summary status of a single pod instance.
     */
    @Path("/{name}/status")
    @GET
    public Response getPodStatus(@PathParam("name") String name) {
        try {
            List<TaskInfoAndStatus> podTasks = GroupedTasks.create(stateStore).byPod.get(name);
            if (podTasks == null) {
                return ResponseUtils.elementNotFoundResponse();
            }
            return jsonOkResponse(getStatusesJson(stateStore, podTasks));
        } catch (Exception e) {
            LOGGER.error(String.format("Failed to fetch status for pod '%s'", name), e);
            return Response.serverError().build();
        }
    }

    /**
     * Produces the full information for a single pod instance.
     */
    @Path("/{name}/info")
    @GET
    public Response getPodInfo(@PathParam("name") String name) {
        try {
            List<TaskInfoAndStatus> podTasks = GroupedTasks.create(stateStore).byPod.get(name);
            if (podTasks == null) {
                return ResponseUtils.elementNotFoundResponse();
            }
            return jsonResponseBean(podTasks, Response.Status.OK);
        } catch (Exception e) {
            LOGGER.error(String.format("Failed to fetch info for pod '%s'", name), e);
            return Response.serverError().build();
        }
    }

    /**
     * Restarts a pod in a "stopped" debug mode.
     */
    @Path("/{name}/stop")
    @POST
    public Response stopPod(@PathParam("name") String name, String bodyPayload) {
        Set<String> taskFilter;
        try {
            taskFilter = parseJsonList(bodyPayload);
        } catch (JSONException e) {
            LOGGER.error(String.format("Failed to parse task filter '%s'", bodyPayload), e);
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        try {
            return overrideGoalState(name, taskFilter, GoalStateOverride.STOPPED);
        } catch (Exception e) {
            LOGGER.error(String.format("Failed to stop pod '%s' with task filter '%s'", name, taskFilter), e);
            return Response.serverError().build();
        }
    }

    /**
     * Restarts a pod in a normal state following a prior "stop" command.
     */
    @Path("/{name}/start")
    @POST
    public Response startPod(@PathParam("name") String name, String bodyPayload) {
        Set<String> taskFilter;
        try {
            taskFilter = parseJsonList(bodyPayload);
        } catch (JSONException e) {
            LOGGER.error(String.format("Failed to parse task filter '%s'", bodyPayload), e);
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        try {
            return overrideGoalState(name, taskFilter, GoalStateOverride.NONE);
        } catch (Exception e) {
            LOGGER.error(String.format("Failed to start pod '%s' with task filter '%s'", name, taskFilter), e);
            return Response.serverError().build();
        }
    }

    private static Set<String> parseJsonList(String payload) throws JSONException {
        if (StringUtils.isBlank(payload)) {
            return Collections.emptySet();
        }
        Set<String> strings = new HashSet<>();
        Iterator<Object> iter = new JSONArray(payload).iterator();
        while (iter.hasNext()) {
            strings.add(iter.next().toString());
        }
        return strings;
    }

    private Response overrideGoalState(String podName, Set<String> taskNameFilter, GoalStateOverride override) {
        // look up all tasks in the provided pod name:
        List<TaskInfoAndStatus> podTasks = GroupedTasks.create(stateStore).byPod.get(podName);
        if (podTasks == null || podTasks.isEmpty()) { // shouldn't ever be empty, but just in case
            return ResponseUtils.elementNotFoundResponse();
        }
        if (!taskNameFilter.isEmpty()) {
            // Convert filter to match task names: ["foo", "bar", ...] => ["pod-0-foo", "pod-0-bar", ...]
            final Set<String> updatedTaskNameFilter = taskNameFilter.stream()
                    .map(t -> String.format("%s-%s", podName, t))
                    .collect(Collectors.toSet());
            podTasks = podTasks.stream()
                    .filter(task -> updatedTaskNameFilter.contains(task.getInfo().getName()))
                    .collect(Collectors.toList());
            if (podTasks.size() < taskNameFilter.size()) {
                // one or more requested tasks were not found.
                LOGGER.error("Request had task filter: '{}', but pod '{}' tasks are: '{}'",
                        updatedTaskNameFilter,
                        podName,
                        podTasks.stream().map(t -> t.getInfo().getName()).collect(Collectors.toList()));
                return ResponseUtils.elementNotFoundResponse();
            }
        }

        // invoke the restart request itself against ALL tasks. this ensures that they're ALL flagged as failed via
        // FailureUtils, which is then checked by DefaultRecoveryPlanManager.
        LOGGER.info("Performing {} goal state override of {} tasks in pod {}:",
                override, podTasks.size(), podName);
        if (taskKiller == null) {
            LOGGER.error("Task killer wasn't initialized yet (scheduler started recently?), exiting early.");
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
        }

        // First pass: Store the desired override for each task
        GoalStateOverride.Status statusToSet =
                GoalStateOverride.newStatus(override, GoalStateOverride.Progress.PENDING);
        for (TaskInfoAndStatus taskToOverride : podTasks) {
            stateStore.storeGoalOverrideStatus(taskToOverride.getInfo().getName(), statusToSet);
        }

        // Second pass: Restart the tasks, updating the override state for each to PENDING as we go.
        statusToSet = GoalStateOverride.withProgress(statusToSet, GoalStateOverride.Progress.IN_PROGRESS);
        return killTasks(taskKiller, stateStore, podName, podTasks, RecoveryType.TRANSIENT, Optional.of(statusToSet));
    }

    /**
     * Restarts a pod instance in-place.
     */
    @Path("/{name}/restart")
    @POST
    public Response restartPod(@PathParam("name") String name) {
        try {
            return restartPod(name, RecoveryType.TRANSIENT);
        } catch (Exception e) {
            LOGGER.error(String.format("Failed to restart pod '%s'", name), e);
            return Response.serverError().build();
        }
    }

    /**
     * Replaces a pod instance with a new instance on a different agent.
     */
    @Path("/{name}/replace")
    @POST
    public Response replacePod(@PathParam("name") String name) {
        try {
            return restartPod(name, RecoveryType.PERMANENT);
        } catch (Exception e) {
            LOGGER.error(String.format("Failed to replace pod '%s'", name), e);
            return Response.serverError().build();
        }
    }

    private Response restartPod(String name, RecoveryType recoveryType) {
        // look up all tasks in the provided pod name:
        List<TaskInfoAndStatus> podTasks = GroupedTasks.create(stateStore).byPod.get(name);
        if (podTasks == null || podTasks.isEmpty()) { // shouldn't ever be empty, but just in case
            return ResponseUtils.elementNotFoundResponse();
        }

        // invoke the restart request itself against ALL tasks. this ensures that they're ALL flagged as failed via
        // FailureUtils, which is then checked by DefaultRecoveryPlanManager.
        LOGGER.info("Performing {} restart of pod {} by killing {} tasks:",
                recoveryType, name, podTasks.size());
        if (taskKiller == null) {
            LOGGER.error("Task killer wasn't initialized yet (scheduler started recently?), exiting early.");
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
        }
        return killTasks(taskKiller, stateStore, name, podTasks, recoveryType, Optional.empty());
    }

    private static Response killTasks(
            TaskKiller taskKiller,
            StateStore stateStore,
            String podName,
            Collection<TaskInfoAndStatus> tasksToKill,
            RecoveryType recoveryType,
            Optional<GoalStateOverride.Status> overrideToStoreAfterKills) {
        for (TaskInfoAndStatus taskToKill : tasksToKill) {
            final TaskInfo taskInfo = taskToKill.getInfo();
            if (taskToKill.hasStatus()) {
                LOGGER.info("  {} ({}): currently in state {}",
                        taskInfo.getName(),
                        taskInfo.getTaskId().getValue(),
                        taskToKill.getStatus().get().getState());
            } else {
                LOGGER.info("  {} ({}): no status available",
                        taskInfo.getName(),
                        taskInfo.getTaskId().getValue());
            }
            taskKiller.killTask(taskInfo.getTaskId(), recoveryType);
            if (overrideToStoreAfterKills.isPresent()) {
                stateStore.storeGoalOverrideStatus(taskInfo.getName(), overrideToStoreAfterKills.get());
            }
        }

        JSONObject json = new JSONObject();
        json.put("pod", podName);
        json.put("tasks", tasksToKill.stream().map(t -> t.getInfo().getName()).collect(Collectors.toList()));
        return jsonOkResponse(json);
    }

    /**
     * Utility class for sorting/grouping {@link TaskInfo}s and/or {@link TaskStatus}es into pods.
     */
    private static class GroupedTasks {
        /** pod instance => tasks in pod. */
        private final Map<String, List<TaskInfoAndStatus>> byPod = new TreeMap<>();
        /** tasks for which the pod instance couldn't be determined. */
        private final List<TaskInfoAndStatus> unknownPod = new ArrayList<>();

        private static GroupedTasks create(StateStore stateStore) {
            return new GroupedTasks(stateStore.fetchTasks(), stateStore.fetchStatuses());
        }

        private GroupedTasks(Collection<TaskInfo> taskInfos, Collection<TaskStatus> taskStatuses) {
            Map<TaskID, TaskStatus> taskStatusesById = taskStatuses.stream()
                    .collect(Collectors.toMap(status -> status.getTaskId(), Function.identity()));

            // map TaskInfos (and TaskStatuses if available) into pod instances:
            for (TaskInfo taskInfo : taskInfos) {
                TaskInfoAndStatus taskInfoAndStatus = TaskInfoAndStatus.create(
                        taskInfo,
                        Optional.ofNullable(taskStatusesById.get(taskInfo.getTaskId())));
                Optional<String> podNameOptional = getPodInstanceName(taskInfo);
                if (podNameOptional.isPresent()) {
                    List<TaskInfoAndStatus> tasksAndStatuses = byPod.get(podNameOptional.get());
                    if (tasksAndStatuses == null) {
                        tasksAndStatuses = new ArrayList<>();
                        byPod.put(podNameOptional.get(), tasksAndStatuses);
                    }
                    tasksAndStatuses.add(taskInfoAndStatus);
                } else {
                    unknownPod.add(taskInfoAndStatus);
                }
            }

            // sort the tasks within each pod by the task names (for user convenience):
            for (List<TaskInfoAndStatus> podTasks : byPod.values()) {
                podTasks.sort(new Comparator<TaskInfoAndStatus>() {
                    @Override
                    public int compare(TaskInfoAndStatus a, TaskInfoAndStatus b) {
                        return a.getInfo().getName().compareTo(b.getInfo().getName());
                    }
                });
            }
        }
    }

    private static JSONArray getStatusesJson(StateStore stateStore, List<TaskInfoAndStatus> tasks) {
        JSONArray jsonPod = new JSONArray();
        for (TaskInfoAndStatus task : tasks) {
            JSONObject jsonTask = new JSONObject();
            jsonTask.put("id", task.getInfo().getTaskId().getValue());
            jsonTask.put("name", task.getInfo().getName());
            if (task.hasStatus()) {
                Protos.TaskState state = task.getStatus().get().getState();
                jsonTask.put("state", state.toString()); // raw mesos status
                jsonTask.put("status", getSDKTaskState(stateStore, task.getInfo().getName(), state)); // sdk context
            }
            try {
                jsonTask.put("type", new TaskLabelReader(task.getInfo()).getType());
            } catch (TaskException e) {
                // no type found, omit from response
            }
            jsonPod.put(jsonTask);
        }
        return jsonPod;
    }

    private static String getSDKTaskState(StateStore stateStore, String taskName, Protos.TaskState mesosState) {
        GoalStateOverride.Status overrideStatus = stateStore.fetchGoalOverrideStatus(taskName);
        if (GoalStateOverride.Status.INACTIVE.equals(overrideStatus)) {
            // This task is affected by an override. Use the override status as applicable.
            switch (mesosState) {
            case TASK_KILLING:
            case TASK_KILLED:
            case TASK_STAGING:
            case TASK_STARTING:
                // Task appears to be entering the desired goal state
                return overrideStatus.target.getTransitioningName();
            case TASK_RUNNING:
                // Task appears to be in the desired goal state
                return overrideStatus.target.getSerializedName();
            default:
                break;
            }
        }
        String stateString = mesosState.toString();
        if (stateString.startsWith("TASK_")) {
            // Trim "TASK_" prefix ("TASK_RUNNING" => "RUNNING"):
            stateString = stateString.substring("TASK_".length(), stateString.length());
        }
        return stateString;
    }

    private static Optional<String> getPodInstanceName(TaskInfo taskInfo) {
        try {
            TaskLabelReader labels = new TaskLabelReader(taskInfo);
            return Optional.of(PodInstance.getName(labels.getType(), labels.getIndex()));
        } catch (Exception e) {
            LOGGER.warn(String.format("Failed to extract pod information from task %s", taskInfo.getName()), e);
            return Optional.empty();
        }
    }
}
