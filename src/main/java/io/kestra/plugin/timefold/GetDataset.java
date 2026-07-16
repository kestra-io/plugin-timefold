package io.kestra.plugin.timefold;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
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

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Retrieve a dataset from the Timefold Platform by job ID",
    description = "Fetches the current state of a previously submitted solving job from the " +
        "Timefold Platform. Returns the `modelOutput` together with the `solverStatus` and `score` " +
        "as reported by the platform at the time of the call.\n\n" +
        "Use this task after `io.kestra.plugin.timefold.Solve` to retrieve the solution once " +
        "solving has completed. See the " +
        "[Timefold API documentation](https://docs.timefold.ai/field-service-routing/latest/understanding-the-api)."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Submit a problem and retrieve the solution in a two-task flow.",
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
                  - id: get_result
                    type: io.kestra.plugin.timefold.GetDataset
                    apiKey: "{{ secret('TIMEFOLD_API_KEY') }}"
                    model: FIELD_SERVICE_ROUTING
                    jobId: "{{ outputs.solve.jobId }}"
                """
        )
    }
)
public class GetDataset extends AbstractTimefoldTask implements RunnableTask<GetDataset.Output> {
    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();

    @Schema(
        title = "The job ID of the solving run to retrieve",
        description = "Returned as `jobId` by `io.kestra.plugin.timefold.Solve`."
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<String> jobId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        Connection conn = renderConnection(runContext);
        String rJobId = runContext.render(this.jobId).as(String.class).orElseThrow();

        String datasetUrl = collectionUrl(conn.baseUrl(), conn.model()) + "/" + rJobId;

        try (HttpClient client = buildHttpClient(runContext)) {
            logger.info("Fetching dataset from Timefold model '{}', job id: {}", conn.model().modelId(), rJobId);

            HttpRequest request = baseRequest(datasetUrl, conn.apiKey()).method("GET").build();
            HttpResponse<String> response = client.request(request, String.class);
            JsonNode dataset = MAPPER.readTree(response.getBody());

            JsonNode modelOutput = dataset.get("modelOutput");
            String solverStatus = text(dataset, "solverStatus");
            String score = text(dataset, "score");

            logger.info("Retrieved dataset with status '{}' and score '{}'", solverStatus, score);

            return Output.builder()
                .jobId(rJobId)
                .solverStatus(solverStatus)
                .score(score)
                .modelOutput(modelOutput == null || modelOutput.isNull()
                    ? null
                    : MAPPER.convertValue(modelOutput, Object.class))
                .build();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
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
            title = "The identifier of the solving job on the Timefold Platform"
        )
        private final String jobId;

        @Schema(
            title = "The solver status at the time of retrieval, e.g. `SOLVING_COMPLETED` or `SOLVING_ACTIVE`"
        )
        private final String solverStatus;

        @Schema(
            title = "The score of the solution at the time of retrieval, e.g. `0hard/0medium/-3603soft`"
        )
        private final String score;
    }
}
