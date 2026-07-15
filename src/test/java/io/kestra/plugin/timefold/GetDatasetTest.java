package io.kestra.plugin.timefold;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
@WireMockTest
class GetDatasetTest {

    @Inject
    private RunContextFactory runContextFactory;

    private static final String JOB_ID = "job-abc-123";

    @Test
    void returnsModelOutputAndMetadata(WireMockRuntimeInfo wm) throws Exception {
        String datasetPath = "/api/models/field-service-routing/v1/route-plans/" + JOB_ID;

        stubFor(get(urlEqualTo(datasetPath))
            .willReturn(okJson("""
                {
                  "solverStatus": "SOLVING_COMPLETED",
                  "score": "0hard/-42soft",
                  "modelOutput": {"routes": [{"vehicleId": "Ann", "visits": []}]}
                }
                """)));

        GetDataset task = testTask(wm).build();

        GetDataset.Output output = task.run(runContextFactory.of());

        assertThat(output.getJobId(), is(JOB_ID));
        assertThat(output.getSolverStatus(), is("SOLVING_COMPLETED"));
        assertThat(output.getScore(), is("0hard/-42soft"));
        assertThat(output.getModelOutput(), notNullValue());

        verify(getRequestedFor(urlEqualTo(datasetPath))
            .withHeader("X-API-KEY", equalTo("test-api-key")));
    }

    @Test
    void handlesAbsentModelOutput(WireMockRuntimeInfo wm) throws Exception {
        String datasetPath = "/api/models/field-service-routing/v1/route-plans/" + JOB_ID;

        stubFor(get(urlEqualTo(datasetPath))
            .willReturn(okJson("""
                {"solverStatus": "SOLVING_ACTIVE", "score": null, "modelOutput": null}
                """)));

        GetDataset task = testTask(wm).build();

        GetDataset.Output output = task.run(runContextFactory.of());

        assertThat(output.getSolverStatus(), is("SOLVING_ACTIVE"));
        assertThat(output.getScore(), nullValue());
        assertThat(output.getModelOutput(), nullValue());
    }

    @Test
    void employeeSchedulingUsesCorrectEndpoint(WireMockRuntimeInfo wm) throws Exception {
        String datasetPath = "/api/models/employee-scheduling/v1/schedules/" + JOB_ID;

        stubFor(get(urlEqualTo(datasetPath))
            .willReturn(okJson("""
                {"solverStatus": "SOLVING_COMPLETED", "score": "0hard/0soft", "modelOutput": {"shifts": []}}
                """)));

        GetDataset task = testTask(wm)
            .model(Property.ofValue(TimefoldModel.EMPLOYEE_SCHEDULING))
            .build();

        GetDataset.Output output = task.run(runContextFactory.of());

        assertThat(output.getSolverStatus(), is("SOLVING_COMPLETED"));
        verify(getRequestedFor(urlEqualTo(datasetPath)));
    }

    private static GetDataset.GetDatasetBuilder<?, ?> testTask(WireMockRuntimeInfo wm) {
        return GetDataset.builder()
            .apiKey(Property.ofValue("test-api-key"))
            .model(Property.ofValue(TimefoldModel.FIELD_SERVICE_ROUTING))
            .baseUrl(Property.ofValue("http://localhost:" + wm.getHttpPort()))
            .jobId(Property.ofValue(JOB_ID));
    }
}
