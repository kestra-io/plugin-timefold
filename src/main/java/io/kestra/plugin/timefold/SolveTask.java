package io.kestra.plugin.timefold;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.http.client.configurations.TimeoutConfiguration;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Solve an optimization problem on the Timefold Platform",
    description = "Submits a `modelInput` dataset to a Timefold Platform model (Field Service Routing " +
        "or Employee Scheduling), polls the platform until solving completes (or the configured " +
        "`solveDuration` elapses), then returns the optimized `modelOutput`.\n\n" +
        "The task wraps three Timefold REST calls: a `POST` to submit the dataset (returning a job id), " +
        "repeated `GET .../{id}/metadata` calls to track the `solverStatus`, and a final " +
        "`GET .../{id}` to retrieve the full solution. See the " +
        "[Timefold API documentation](https://docs.timefold.ai/field-service-routing/latest/understanding-the-api)."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Solve a Field Service Routing problem and return the optimized routes.",
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
                """
        ),
        @Example(
            full = true,
            title = "Solve an Employee Scheduling problem, passing the input from a previous task as JSON.",
            code = """
                id: timefold_schedule
                namespace: company.team

                tasks:
                  - id: solve
                    type: io.kestra.plugin.timefold.Solve
                    apiKey: "{{ secret('TIMEFOLD_API_KEY') }}"
                    model: EMPLOYEE_SCHEDULING
                    solveDuration: PT1M
                    modelInput: "{{ outputs.build_dataset.modelInput }}"
                """
        )
    }
)
public class SolveTask extends AbstractTimefoldTask implements RunnableTask<SolveTask.Output> {
    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();
    private static final Set<String> RUNNING_STATUSES = Set.of(
        "SOLVING_SCHEDULED", "SOLVING_ACTIVE", "NOT_SOLVING"
    );

    @Schema(
        title = "The optimization input dataset (`modelInput`)",
        description = "The data to be optimized, following the selected model's schema. " +
            "For Field Service Routing this contains `vehicles` and `visits`; for Employee " +
            "Scheduling it contains employees, shifts, etc. Accepts a map, or a JSON string / " +
            "expression that resolves to the `modelInput` object. The value is wrapped into the " +
            "`{ \"modelInput\": ... }` request body sent to Timefold."
    )
    @NotNull
    private Property<Object> modelInput;

    @Schema(
        title = "Maximum time Timefold should spend solving before returning the best solution found",
        description = "Passed as the `config.run.termination.spentLimit` of the dataset. If solving has " +
            "not finished after this duration, the task asks the platform to terminate and returns the " +
            "best solution found so far. Defaults to `PT60S` (60 seconds)."
    )
    @NotNull
    @Builder.Default
    private Property<Duration> solveDuration = Property.ofValue(Duration.ofSeconds(60));

    @Schema(
        title = "Optional run name attached to the submitted dataset",
        description = "Stored as `config.run.name` and shown in the Timefold Platform UI."
    )
    private Property<String> runName;

    @Schema(
        title = "How often to poll the Timefold Platform for the solver status",
        description = "Defaults to `PT2S` (every 2 seconds)."
    )
    @NotNull
    @Builder.Default
    private Property<Duration> pollInterval = Property.ofValue(Duration.ofSeconds(2));

    @Schema(
        title = "Overall timeout for the whole operation, including queueing on the platform",
        description = "If the solver has not completed within this duration the task fails. " +
            "Should comfortably exceed `solveDuration`. Defaults to `PT10M` (10 minutes)."
    )
    @NotNull
    @Builder.Default
    private Property<Duration> requestTimeout = Property.ofValue(Duration.ofMinutes(10));

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String renderedApiKey = runContext.render(this.apiKey).as(String.class).orElseThrow();
        TimefoldModel renderedModel = runContext.render(this.model).as(TimefoldModel.class).orElseThrow();
        String renderedBaseUrl = runContext.render(this.baseUrl).as(String.class).orElseThrow();
        Duration renderedSolveDuration = runContext.render(this.solveDuration).as(Duration.class).orElseThrow();
        Duration renderedPollInterval = runContext.render(this.pollInterval).as(Duration.class).orElseThrow();
        Duration renderedRequestTimeout = runContext.render(this.requestTimeout).as(Duration.class).orElseThrow();
        String renderedRunName = runContext.render(this.runName).as(String.class).orElse(null);

        String collectionUrl = String.format(
            "%s/api/models/%s/v1/%s",
            stripTrailingSlash(renderedBaseUrl),
            renderedModel.modelId(),
            renderedModel.resource()
        );

        JsonNode datasetBody = buildDataset(runContext, renderedSolveDuration, renderedRunName);

        HttpConfiguration configuration = HttpConfiguration.builder()
            .timeout(TimeoutConfiguration.builder()
                .connectTimeout(Property.ofValue(Duration.ofSeconds(30)))
                .readIdleTimeout(Property.ofValue(Duration.ofSeconds(60)))
                .build())
            .build();

        try (HttpClient client = HttpClient.builder()
            .runContext(runContext)
            .configuration(configuration)
            .build()) {

            // 1. Submit the dataset.
            String jobId = submit(client, collectionUrl, renderedApiKey, datasetBody, logger);
            logger.info("Submitted dataset to Timefold model '{}', job id: {}", renderedModel.modelId(), jobId);

            // 2. Poll the metadata endpoint until the solver completes.
            JsonNode metadata = pollUntilComplete(
                client, collectionUrl, jobId, renderedApiKey,
                renderedPollInterval, renderedRequestTimeout, renderedSolveDuration,
                logger
            );

            String solverStatus = text(metadata, "solverStatus");
            String score = text(metadata, "score");
            logger.info("Solving finished with status '{}' and score '{}'", solverStatus, score);

            // 3. Fetch the full solution.
            JsonNode solution = httpGet(client, collectionUrl + "/" + jobId, renderedApiKey);
            JsonNode modelOutput = solution.get("modelOutput");

            runContext.metric(io.kestra.core.models.executions.metrics.Counter.of("solved", 1));

            return Output.builder()
                .jobId(jobId)
                .solverStatus(solverStatus)
                .score(score)
                .modelOutput(modelOutput == null || modelOutput.isNull()
                    ? null
                    : MAPPER.convertValue(modelOutput, Object.class))
                .build();
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

        // modelInput, rendered from whatever the user provided (map / json string / expression).
        Object renderedInput = runContext.render(this.modelInput).as(Object.class).orElseThrow();
        dataset.set("modelInput", toJsonNode(renderedInput));

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
        // Once the solver has been running longer than solveDuration plus a grace period,
        // proactively ask Timefold to terminate and return the best solution found.
        long terminateAfter = System.nanoTime() + solveDuration.plusSeconds(10).toNanos();
        boolean terminationRequested = false;

        while (true) {
            JsonNode metadata = httpGet(client, metadataUrl, apiKey);
            String status = text(metadata, "solverStatus");

            if (status == null || !RUNNING_STATUSES.contains(status)) {
                // SOLVING_COMPLETED, SOLVING_FAILED, TERMINATED, etc.
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
                terminate(client, collectionUrl, jobId, apiKey);
                terminationRequested = true;
            }

            Thread.sleep(pollInterval.toMillis());
        }
    }

    private void terminate(HttpClient client, String collectionUrl, String jobId, String apiKey) {
        try {
            HttpRequest request = baseRequest(collectionUrl + "/" + jobId, apiKey)
                .method("DELETE")
                .build();
            client.request(request, String.class);
        } catch (Exception e) {
            // Best-effort: solving will still end on its own spentLimit.
        }
    }

    private JsonNode httpGet(HttpClient client, String url, String apiKey) throws Exception {
        HttpRequest request = baseRequest(url, apiKey)
            .method("GET")
            .build();
        HttpResponse<String> response = client.request(request, String.class);
        return MAPPER.readTree(response.getBody());
    }

    private HttpRequest.HttpRequestBuilder baseRequest(String url, String apiKey) {
        return HttpRequest.builder()
            .uri(URI.create(url))
            .addHeader("X-API-KEY", apiKey)
            .addHeader("Accept", "application/json");
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

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Formats a {@link Duration} as an ISO-8601 string Timefold accepts, e.g. {@code PT30S}.
     */
    private static String formatDuration(Duration duration) {
        return duration.toString();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The optimized solution (`modelOutput`)",
            description = "The `modelOutput` object returned by the Timefold Platform, containing the " +
                "optimized assignments (routes, schedules, etc.) for the selected model."
        )
        private final Object modelOutput;

        @Schema(
            title = "The identifier of the solving job on the Timefold Platform",
            description = "Can be used to retrieve or terminate the job via the Timefold API."
        )
        private final String jobId;

        @Schema(
            title = "The final solver status, e.g. `SOLVING_COMPLETED` or `TERMINATED`"
        )
        private final String solverStatus;

        @Schema(
            title = "The score of the returned solution, e.g. `0hard/0medium/-3603soft`"
        )
        private final String score;
    }
}
