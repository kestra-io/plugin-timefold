package io.kestra.plugin.timefold;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.exceptions.KilledException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Data;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Submit an optimization problem to the Timefold Platform",
    description = "Submits a `modelInput` dataset to a Timefold Platform model (Field Service Routing " +
        "or Employee Scheduling).\n\n" +
        "When `wait` is `false` (the default) the task performs a single `POST` and immediately " +
        "returns the `jobId`. Use the job id in a subsequent `GetDataset` task to poll for status " +
        "or retrieve the solution.\n\n" +
        "When `wait` is `true` the task submits the dataset and polls the platform until solving " +
        "completes (or `requestTimeout` elapses), then returns the `jobId`, `solverStatus`, `score`, " +
        "and the full `modelOutput`. See the " +
        "[Timefold API documentation](https://docs.timefold.ai/field-service-routing/latest/understanding-the-api)."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Submit a Field Service Routing problem and capture the job ID for downstream tasks.",
            code = """
                id: timefold_route
                namespace: company.team

                tasks:
                  - id: solve
                    type: io.kestra.plugin.timefold.Solve
                    apiKey: "{{ secret('TIMEFOLD_API_KEY') }}"
                    model: FIELD_SERVICE_ROUTING
                    solveDuration: PT30S
                    modelInput:
                      vehicles:
                        - id: Ann
                          shifts:
                            - id: Ann-2027-02-01
                              startLocation: [33.68786, -84.18487]
                              minStartTime: "2027-02-01T09:00:00Z"
                      visits:
                        - id: Visit A
                          location: [33.77301, -84.43838]
                          serviceDuration: PT1H30M
                  - id: log_job_id
                    type: io.kestra.plugin.core.log.Log
                    message: "Submitted job: {{ outputs.solve.jobId }}"
                """
        ),
        @Example(
            full = true,
            title = "Build an Employee Scheduling dataset from CSV inputs and solve.",
            code = """
                id: timefold_schedule
                namespace: company.team

                # employees.csv  (name becomes the employee id):
                #   name,skills
                #   Alice,nursing|doctor
                #   Bob,nursing
                #
                # shifts.csv  (required_skill becomes a requiredSkills object array):
                #   id,start,end,required_skill
                #   SHIFT-001,2027-02-01T08:00:00Z,2027-02-01T16:00:00Z,nursing
                #   SHIFT-002,2027-02-01T16:00:00Z,2027-02-02T00:00:00Z,nursing

                inputs:
                  - id: employees_csv
                    type: FILE
                  - id: shifts_csv
                    type: FILE

                tasks:
                  - id: build_dataset
                    type: io.kestra.plugin.scripts.python.Script
                    inputFiles:
                      employees.csv: "{{ inputs.employees_csv }}"
                      shifts.csv: "{{ inputs.shifts_csv }}"
                    script: |
                      import csv
                      from kestra import Kestra

                      employees = []
                      with open("employees.csv") as f:
                          for row in csv.DictReader(f):
                              employees.append({
                                  "id": row["name"],
                                  "skills": [{"id": s} for s in row["skills"].split("|")],
                              })

                      shifts = []
                      with open("shifts.csv") as f:
                          for row in csv.DictReader(f):
                              shifts.append({
                                  "id": row["id"],
                                  "start": row["start"],
                                  "end": row["end"],
                                  "requiredSkills": [row["required_skill"]],
                              })

                      Kestra.outputs({"modelInput": {"employees": employees, "shifts": shifts}})

                  - id: solve
                    type: io.kestra.plugin.timefold.Solve
                    apiKey: "{{ secret('TIMEFOLD_API_KEY') }}"
                    model: EMPLOYEE_SCHEDULING
                    solveDuration: PT1M
                    modelInput: "{{ outputs.build_dataset.vars.modelInput }}"
                """
        ),
        @Example(
            full = true,
            title = "Submit a Field Service Routing problem and wait for the optimized solution.",
            code = """
                id: timefold_route_wait
                namespace: company.team

                tasks:
                  - id: solve
                    type: io.kestra.plugin.timefold.Solve
                    apiKey: "{{ secret('TIMEFOLD_API_KEY') }}"
                    model: FIELD_SERVICE_ROUTING
                    solveDuration: PT30S
                    wait: true
                    modelInput:
                      vehicles:
                        - id: Ann
                          shifts:
                            - id: Ann-2027-02-01
                              startLocation: [33.68786, -84.18487]
                              minStartTime: "2027-02-01T09:00:00Z"
                      visits:
                        - id: Visit A
                          location: [33.77301, -84.43838]
                          serviceDuration: PT1H30M
                  - id: log_result
                    type: io.kestra.plugin.core.log.Log
                    message: "Solved {{ outputs.solve.jobId }} — status: {{ outputs.solve.solverStatus }}, score: {{ outputs.solve.score }}"
                """
        )
    },
    metrics = {
        @Metric(name = "solved", type = Counter.TYPE)
    }
)
public class Solve extends AbstractTimefoldTask implements RunnableTask<Solve.Output> {
    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();
    private static final Set<String> RUNNING_STATUSES = Set.of(
        "SOLVING_SCHEDULED", "SOLVING_ACTIVE", "NOT_SOLVING"
    );

    // Runtime state for kill() — transient so Lombok/Jackson ignore them for serialization,
    // equals, hashCode, and toString. NOT initialized inline: @SuperBuilder replaces field
    // initializers with a generated $default$ method that @NoArgsConstructor never calls,
    // leaving them null. They are initialized at the top of run() instead.
    @Getter(AccessLevel.NONE)
    private transient AtomicReference<String> activeJobId;
    @Getter(AccessLevel.NONE)
    private transient AtomicBoolean killRequested;
    @Getter(AccessLevel.NONE)
    private transient volatile Thread runThread;
    @Getter(AccessLevel.NONE)
    private transient volatile Logger killLogger;
    @Getter(AccessLevel.NONE)
    private transient volatile String killCollectionUrl;
    @Getter(AccessLevel.NONE)
    private transient volatile String killApiKey;

    @Schema(
        title = "The optimization input dataset (`modelInput`)",
        description = "The data to be optimized, following the selected model's schema. " +
            "For Field Service Routing this contains `vehicles` and `visits`; for Employee " +
            "Scheduling it contains employees, shifts, etc. Accepts a map, or a JSON string / " +
            "expression that resolves to the `modelInput` object. The value is wrapped into the " +
            "`{ \"modelInput\": ... }` request body sent to Timefold."
    )
    @PluginProperty(group = "main")
    @NotNull
    private Object modelInput;

    @Schema(
        title = "Maximum time Timefold should spend solving",
        description = "Passed as the `config.run.termination.spentLimit` of the submitted dataset. " +
            "Controls how long the platform solver runs; it does not affect when this task returns. " +
            "Defaults to `PT60S` (60 seconds)."
    )
    @PluginProperty(group = "execution")
    @NotNull
    @Builder.Default
    private Property<Duration> solveDuration = Property.ofValue(Duration.ofSeconds(60));

    @Schema(
        title = "Optional run name attached to the submitted dataset",
        description = "Stored as `config.run.name` and shown in the Timefold Platform UI."
    )
    @PluginProperty(group = "advanced")
    private Property<String> runName;

    @Schema(
        title = "Whether to poll for the solution before returning",
        description = "When `true` the task polls the platform until solving completes (or " +
            "`requestTimeout` elapses) and returns the full `modelOutput`, `solverStatus`, and `score`. " +
            "When `false` (the default) the task submits the dataset and returns the `jobId` immediately."
    )
    @PluginProperty(group = "execution")
    @NotNull
    @Builder.Default
    private Property<Boolean> wait = Property.ofValue(false);

    @Schema(
        title = "How often to poll the Timefold Platform for the solver status",
        description = "Only applies when `wait` is `true`. Minimum is `PT0.5S` (500 ms). Defaults to `PT2S` (every 2 seconds)."
    )
    @PluginProperty(group = "execution")
    @NotNull
    @Builder.Default
    private Property<Duration> pollInterval = Property.ofValue(Duration.ofSeconds(2));

    @Schema(
        title = "Overall timeout for the whole operation, including queueing on the platform",
        description = "Only applies when `wait` is `true`. If the solver has not completed within " +
            "this duration the task fails. Should comfortably exceed `solveDuration`. " +
            "Defaults to `PT10M` (10 minutes)."
    )
    @PluginProperty(group = "execution")
    @NotNull
    @Builder.Default
    private Property<Duration> requestTimeout = Property.ofValue(Duration.ofMinutes(10));

    @Schema(
        title = "How to return the `modelOutput`",
        description = "Only applies when `wait` is `true`.\n\n" +
            "- `STORE` (default): writes `modelOutput` as a JSON file to Kestra's internal storage " +
            "and returns its URI in `uri`. Recommended for large solutions.\n" +
            "- `FETCH` / `FETCH_ONE`: returns `modelOutput` inline in the `modelOutput` output field.\n" +
            "- `NONE`: discards `modelOutput` entirely (useful when only `solverStatus` and `score` are needed)."
    )
    @PluginProperty(group = "execution")
    @NotNull
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.STORE);

    @Override
    public Output run(RunContext runContext) throws Exception {
        activeJobId = new AtomicReference<>();
        killRequested = new AtomicBoolean(false);
        runThread = Thread.currentThread();
        Logger logger = runContext.logger();
        killLogger = logger;

        Connection conn = renderConnection(runContext);
        Duration rSolveDuration = runContext.render(this.solveDuration).as(Duration.class).orElseThrow();
        String rRunName = runContext.render(this.runName).as(String.class).orElse(null);
        boolean rWait = runContext.render(this.wait).as(Boolean.class).orElse(false);

        String collectionUrl = collectionUrl(conn.baseUrl(), conn.model());
        killCollectionUrl = collectionUrl;
        killApiKey = conn.apiKey();
        JsonNode datasetBody = buildDataset(runContext, rSolveDuration, rRunName);

        try (HttpClient client = buildHttpClient(runContext)) {
            String jobId = submit(client, collectionUrl, conn.apiKey(), datasetBody, logger);
            activeJobId.set(jobId);
            logger.info("Submitted dataset to Timefold model '{}', job id: {}", conn.model().modelId(), jobId);

            if (!rWait) {
                return Output.builder()
                    .jobId(jobId)
                    .build();
            }

            Duration rPollInterval = runContext.render(this.pollInterval).as(Duration.class).orElseThrow();
            if (rPollInterval.compareTo(Duration.ofMillis(500)) < 0) {
                throw new IllegalArgumentException("pollInterval must be at least PT0.5S (500 ms), got: " + rPollInterval);
            }
            Duration rRequestTimeout = runContext.render(this.requestTimeout).as(Duration.class).orElseThrow();

            JsonNode metadata = pollUntilComplete(
                client, collectionUrl, jobId, conn.apiKey(),
                rPollInterval, rRequestTimeout, rSolveDuration,
                logger
            );

            String solverStatus = text(metadata, "solverStatus");
            String score = text(metadata, "score");
            logger.info("Solving finished with status '{}' and score '{}'", solverStatus, score);

            JsonNode solution = httpGet(client, collectionUrl + "/" + jobId, conn.apiKey());
            JsonNode modelOutput = solution.get("modelOutput");
            FetchType rFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.STORE);

            runContext.metric(Counter.of("solved", 1));

            Output.OutputBuilder outputBuilder = Output.builder()
                .jobId(jobId)
                .solverStatus(solverStatus)
                .score(score);

            if (modelOutput != null && !modelOutput.isNull()) {
                switch (rFetchType) {
                    case STORE -> outputBuilder.uri(storeModelOutput(runContext, modelOutput));
                    case FETCH, FETCH_ONE -> outputBuilder.modelOutput(MAPPER.convertValue(modelOutput, Object.class));
                    case NONE -> { /* discard */ }
                }
            }

            return outputBuilder.build();
        }
    }

    private JsonNode buildDataset(RunContext runContext, Duration solveDuration, String runName) throws Exception {
        ObjectNode dataset = MAPPER.createObjectNode();

        // config.run.name + config.run.termination.spentLimit
        ObjectNode config = dataset.putObject("config");
        ObjectNode run = config.putObject("run");
        if (runName != null) {
            run.put("name", runName);
        }
        run.putObject("termination").put("spentLimit", formatDuration(solveDuration));

        // modelInput, r from whatever the user provided (map / json string / expression).
        var rInput = Data.from(this.modelInput).read(runContext).blockFirst();
        dataset.set("modelInput", toJsonNode(rInput));

        return dataset;
    }

    private String submit(HttpClient client, String url, String apiKey, JsonNode body, Logger logger) throws Exception {
        HttpRequest request = baseRequest(url, apiKey)
            .method("POST")
            .body(HttpRequest.JsonRequestBody.of(body))
            .build();

        HttpResponse<String> response = client.request(request, String.class);

        String responseBody = response.getBody();
        // The POST returns the job id, either as a bare quoted string or inside a JSON object.
        JsonNode node = tryParse(responseBody);
        if (node != null && node.isTextual()) {
            return node.asText();
        }
        if (node != null && node.has("id")) {
            return node.get("id").asText();
        }
        // Fall back to the raw body with surrounding quotes/whitespace removed.
        return responseBody.trim().replaceAll("^\"|\"$", "");
    }

    private JsonNode pollUntilComplete(HttpClient client,
                                       String collectionUrl,
                                       String jobId,
                                       String apiKey,
                                       Duration pollInterval,
                                       Duration requestTimeout,
                                       Duration solveDuration,
                                       Logger logger) throws Exception {
        String metadataUrl = collectionUrl + "/" + jobId + "/metadata";
        long deadline = System.nanoTime() + requestTimeout.toNanos();
        long terminateAfter = System.nanoTime() + solveDuration.plusSeconds(10).toNanos();
        boolean terminationRequested = false;

        while (true) {
            if (killRequested.get()) {
                throw new KilledException();
            }

            JsonNode metadata = httpGet(client, metadataUrl, apiKey);
            String status = text(metadata, "solverStatus");

            if (status == null || !RUNNING_STATUSES.contains(status)) {
                if ("SOLVING_FAILED".equals(status)) {
                    throw new IllegalStateException(
                        "Timefold solving failed for job " + jobId + ": " + metadata.toString()
                    );
                }
                return metadata;
            }

            if (System.nanoTime() > deadline) {
                throw new IllegalStateException(
                    "Timed out after " + requestTimeout + " waiting for Timefold job " + jobId +
                        " to complete (last status: " + status + ")"
                );
            }

            if (!terminationRequested && System.nanoTime() > terminateAfter) {
                logger.info("solveDuration elapsed; requesting early termination for job {}", jobId);
                terminate(client, collectionUrl, jobId, apiKey, logger);
                terminationRequested = true;
            }

            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new KilledException();
            }
        }
    }

    private void terminate(HttpClient client, String collectionUrl, String jobId, String apiKey, Logger logger) {
        try {
            HttpRequest request = baseRequest(collectionUrl + "/" + jobId, apiKey)
                .method("DELETE")
                .build();
            client.request(request, String.class);
        } catch (Exception e) {
            logger.warn("Failed to request early termination for job {} — solving will end on its own spentLimit: {}", jobId, e.getMessage());
        }
    }

    @Override
    public void kill() {
        AtomicBoolean killed = killRequested;
        if (killed == null || !killed.compareAndSet(false, true)) {
            return; // duplicate signal or run() not started yet — ignore
        }
        Thread thread = runThread;
        if (thread != null) {
            thread.interrupt(); // wake the poll loop immediately
        }
        String jobId = activeJobId != null ? activeJobId.get() : null;
        String collectionUrl = killCollectionUrl;
        String apiKey = killApiKey;
        if (jobId == null || collectionUrl == null || apiKey == null) {
            return; // job was never submitted; nothing to cancel on the platform
        }
        // Use JDK's HttpClient rather than the Kestra one: thread.interrupt() above may have
        // already caused run() to exit its try-with-resources block and close the Kestra client,
        // creating a race. A fresh JDK client is independent of that lifecycle.
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(collectionUrl + "/" + jobId))
                .header("X-API-KEY", apiKey)
                .header("Accept", "application/json")
                .DELETE()
                .build();
            client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            Logger logger = killLogger;
            if (logger != null) {
                logger.warn("Failed to cancel job {} on the Timefold Platform: {}", jobId, e.getMessage());
            }
        }
    }

    private URI storeModelOutput(RunContext runContext, JsonNode node) throws Exception {
        byte[] json = MAPPER.writeValueAsBytes(node);
        try (var is = new ByteArrayInputStream(json)) {
            return runContext.storage().putFile(is, "modelOutput.json");
        }
    }

    private JsonNode httpGet(HttpClient client, String url, String apiKey) throws Exception {
        HttpRequest request = baseRequest(url, apiKey)
            .method("GET")
            .build();
        HttpResponse<String> response = client.request(request, String.class);
        return MAPPER.readTree(response.getBody());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private JsonNode toJsonNode(Object value) {
        if (value instanceof String str) {
            JsonNode parsed = tryParse(str);
            if (parsed != null) {
                return parsed;
            }
        }
        return MAPPER.valueToTree(value);
    }

    private JsonNode tryParse(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatDuration(Duration duration) {
        return duration.toString();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The identifier of the solving job on the Timefold Platform",
            description = "Pass this to a subsequent task to poll for status or retrieve the solution " +
                "via the Timefold API."
        )
        private final String jobId;

        @Schema(
            title = "URI to the stored `modelOutput` (populated when `fetchType` is `STORE`)",
            description = "Populated only when `wait` is `true` and `fetchType` is `STORE` (the default). " +
                "Points to the `modelOutput` JSON file written to Kestra's internal storage."
        )
        private final URI uri;

        @Schema(
            title = "The optimized solution (`modelOutput`) returned inline (populated when `fetchType` is `FETCH` or `FETCH_ONE`)",
            description = "Populated only when `wait` is `true` and `fetchType` is `FETCH` or `FETCH_ONE`. " +
                "The `modelOutput` object returned by the Timefold Platform containing the optimized " +
                "assignments (routes, schedules, etc.)."
        )
        private final Object modelOutput;

        @Schema(
            title = "The final solver status, e.g. `SOLVING_COMPLETED` or `TERMINATED`",
            description = "Populated only when `wait` is `true`."
        )
        private final String solverStatus;

        @Schema(
            title = "The score of the returned solution, e.g. `0hard/0medium/-3603soft`",
            description = "Populated only when `wait` is `true`."
        )
        private final String score;
    }
}
