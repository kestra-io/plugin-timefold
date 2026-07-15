package io.kestra.plugin.timefold;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
class SolveTest {

    @Inject
    private RunContextFactory runContextFactory;

    private static final String JOB_ID = "job-abc-123";

    @Test
    void fieldServiceRoutingCompletesOnFirstPoll(WireMockRuntimeInfo wm) throws Exception {
        String collectionPath = "/api/models/field-service-routing/v1/route-plans";

        stubFor(post(urlEqualTo(collectionPath))
            .willReturn(okJson("\"" + JOB_ID + "\"")));

        stubFor(get(urlEqualTo(collectionPath + "/" + JOB_ID + "/metadata"))
            .willReturn(okJson("""
                {"solverStatus":"SOLVING_COMPLETED","score":"0hard/-42soft"}
                """)));

        stubFor(get(urlEqualTo(collectionPath + "/" + JOB_ID))
            .willReturn(okJson("""
                {"modelOutput":{"routes":[{"vehicleId":"Ann","visits":[]}]}}
                """)));

        Solve task = testTask(wm).build();

        Solve.Output output = task.run(runContextFactory.of());

        assertThat(output.getJobId(), is(JOB_ID));
        assertThat(output.getSolverStatus(), is("SOLVING_COMPLETED"));
        assertThat(output.getScore(), is("0hard/-42soft"));
        assertThat(output.getModelOutput(), notNullValue());

        verify(postRequestedFor(urlEqualTo(collectionPath))
            .withHeader("X-API-KEY", equalTo("test-api-key")));
    }

    private static Solve.SolveBuilder<?, ?> testTask(WireMockRuntimeInfo wm) {
        return Solve.builder()
            .apiKey(Property.ofValue("test-api-key"))
            .model(Property.ofValue(TimefoldModel.FIELD_SERVICE_ROUTING))
            .baseUrl(Property.ofValue("http://localhost:" + wm.getHttpPort()))
            .modelInput(Map.of(
                "vehicles", List.of(Map.of("id", "Ann")),
                "visits", List.of()
            ))
            .pollInterval(Property.ofValue(Duration.ofMillis(10)));
    }

    @Test
    void employeeSchedulingPollsUntilComplete(WireMockRuntimeInfo wm) throws Exception {
        String collectionPath = "/api/models/employee-scheduling/v1/schedules";

        stubFor(post(urlEqualTo(collectionPath))
            .willReturn(okJson("\"" + JOB_ID + "\"")));

        // First poll returns SOLVING_ACTIVE, second returns SOLVING_COMPLETED.
        stubFor(get(urlEqualTo(collectionPath + "/" + JOB_ID + "/metadata"))
            .inScenario("polling")
            .whenScenarioStateIs("Started")
            .willReturn(okJson("""
                {"solverStatus":"SOLVING_ACTIVE","score":null}
                """))
            .willSetStateTo("done"));

        stubFor(get(urlEqualTo(collectionPath + "/" + JOB_ID + "/metadata"))
            .inScenario("polling")
            .whenScenarioStateIs("done")
            .willReturn(okJson("""
                {"solverStatus":"SOLVING_COMPLETED","score":"0hard/0soft"}
                """)));

        stubFor(get(urlEqualTo(collectionPath + "/" + JOB_ID))
            .willReturn(okJson("""
                {"modelOutput":{"shifts":[]}}
                """)));

        Solve task = testTask(wm)
            .model(Property.ofValue(TimefoldModel.EMPLOYEE_SCHEDULING))
            .build();

        Solve.Output output = task.run(runContextFactory.of());

        assertThat(output.getSolverStatus(), is("SOLVING_COMPLETED"));
        assertThat(output.getScore(), is("0hard/0soft"));

        // Should have polled twice before completing.
        verify(exactly(2), getRequestedFor(urlEqualTo(collectionPath + "/" + JOB_ID + "/metadata")));
    }

    @Test
    void solvingFailedThrowsException(WireMockRuntimeInfo wm) {
        String collectionPath = "/api/models/field-service-routing/v1/route-plans";

        stubFor(post(urlEqualTo(collectionPath))
            .willReturn(okJson("\"" + JOB_ID + "\"")));

        stubFor(get(urlEqualTo(collectionPath + "/" + JOB_ID + "/metadata"))
            .willReturn(okJson("""
                {"solverStatus":"SOLVING_FAILED","score":null}
                """)));

        Solve task = testTask(wm).build();

        assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of()));
    }

    @Test
    void modelPropertyResolvesFromPlainEnumNameYaml() throws Exception {
        // Reproduces the YAML case `model: EMPLOYEE_SCHEDULING`: the property holds a
        // raw, un-rendered expression (unlike Property.ofValue(...) used above, which
        // caches the enum instance directly and never exercises this code path).
        Property<TimefoldModel> property = JacksonMapper.ofJson()
            .readValue("\"EMPLOYEE_SCHEDULING\"", new TypeReference<Property<TimefoldModel>>() {});

        TimefoldModel resolved = runContextFactory.of().render(property).as(TimefoldModel.class).orElseThrow();

        assertThat(resolved, is(TimefoldModel.EMPLOYEE_SCHEDULING));
    }
}
