package io.kestra.plugin.timefold;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Data;
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

import java.time.Duration;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Submit an optimization problem to the Timefold Platform",
    description = "Submits a `modelInput` dataset to a Timefold Platform model (Field Service Routing " +
        "or Employee Scheduling) and immediately returns the `jobId` assigned by the platform.\n\n" +
        "The task performs a single `POST` to submit the dataset and does not wait for solving to " +
        "finish. Use the returned `jobId` in a subsequent task (e.g. a polling or retrieval task) " +
        "to track progress and fetch the optimized solution. See the " +
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
            title = "Submit an Employee Scheduling problem with input from a previous task.",
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
public class Solve extends AbstractTimefoldTask implements RunnableTask<Solve.Output> {
    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();

    @Schema(
        title = "The optimization input dataset (`modelInput`)",
        description = "The data to be optimized, following the selected model's schema. " +
            "For Field Service Routing this contains `vehicles` and `visits`; for Employee " +
            "Scheduling it contains employees, shifts, etc. Accepts a map, or a JSON string / " +
            "expression that resolves to the `modelInput` object. The value is wrapped into the " +
            "`{ \"modelInput\": ... }` request body sent to Timefold."
    )
    @NotNull
    private Object modelInput;

    @Schema(
        title = "Maximum time Timefold should spend solving",
        description = "Passed as the `config.run.termination.spentLimit` of the submitted dataset. " +
            "Controls how long the platform solver runs; it does not affect when this task returns. " +
            "Defaults to `PT60S` (60 seconds)."
    )
    @NotNull
    @Builder.Default
    private Property<Duration> solveDuration = Property.ofValue(Duration.ofSeconds(60));

    @Schema(
        title = "Optional run name attached to the submitted dataset",
        description = "Stored as `config.run.name` and shown in the Timefold Platform UI."
    )
    private Property<String> runName;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        Connection conn = renderConnection(runContext);
        Duration rSolveDuration = runContext.render(this.solveDuration).as(Duration.class).orElseThrow();
        String rRunName = runContext.render(this.runName).as(String.class).orElse(null);

        String collectionUrl = collectionUrl(conn.baseUrl(), conn.model());
        JsonNode datasetBody = buildDataset(runContext, rSolveDuration, rRunName);

        try (HttpClient client = buildHttpClient(runContext)) {
            String jobId = submit(client, collectionUrl, conn.apiKey(), datasetBody, logger);
            logger.info("Submitted dataset to Timefold model '{}', job id: {}", conn.model().modelId(), jobId);

            return Output.builder()
                .jobId(jobId)
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
    }
}
