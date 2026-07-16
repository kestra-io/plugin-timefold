package io.kestra.plugin.timefold;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.exceptions.KilledException;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
    void fieldServiceRoutingReturnsJobId(WireMockRuntimeInfo wm) throws Exception {
        String collectionPath = "/api/models/field-service-routing/v1/route-plans";

        stubFor(post(urlEqualTo(collectionPath))
            .willReturn(okJson("\"" + JOB_ID + "\"")));

        Solve task = testTask(wm).build();

        Solve.Output output = task.run(runContextFactory.of());

        assertThat(output.getJobId(), is(JOB_ID));
        verify(postRequestedFor(urlEqualTo(collectionPath))
            .withHeader("X-API-KEY", equalTo("test-api-key")));
    }

    @Test
    void employeeSchedulingReturnsJobId(WireMockRuntimeInfo wm) throws Exception {
        String collectionPath = "/api/models/employee-scheduling/v1/schedules";

        stubFor(post(urlEqualTo(collectionPath))
            .willReturn(okJson("\"" + JOB_ID + "\"")));

        Solve task = testTask(wm)
            .model(Property.ofValue(TimefoldModel.EMPLOYEE_SCHEDULING))
            .build();

        Solve.Output output = task.run(runContextFactory.of());

        assertThat(output.getJobId(), is(JOB_ID));
        verify(postRequestedFor(urlEqualTo(collectionPath)));
    }

    @Test
    void jobIdParsedFromJsonObject(WireMockRuntimeInfo wm) throws Exception {
        String collectionPath = "/api/models/field-service-routing/v1/route-plans";

        stubFor(post(urlEqualTo(collectionPath))
            .willReturn(okJson("{\"id\":\"" + JOB_ID + "\"}")));

        Solve task = testTask(wm).build();

        Solve.Output output = task.run(runContextFactory.of());

        assertThat(output.getJobId(), is(JOB_ID));
    }

    @Test
    void modelPropertyResolvesFromPlainEnumNameYaml() throws Exception {
        Property<TimefoldModel> property = JacksonMapper.ofJson()
            .readValue("\"EMPLOYEE_SCHEDULING\"", new TypeReference<Property<TimefoldModel>>() {});

        TimefoldModel resolved = runContextFactory.of().render(property).as(TimefoldModel.class).orElseThrow();

        assertThat(resolved, is(TimefoldModel.EMPLOYEE_SCHEDULING));
    }

    @Test
    void waitTrueReturnsFullSolution(WireMockRuntimeInfo wm) throws Exception {
        String collectionPath = "/api/models/field-service-routing/v1/route-plans";

        stubFor(post(urlEqualTo(collectionPath))
            .willReturn(okJson("\"" + JOB_ID + "\"")));

        stubFor(get(urlEqualTo(collectionPath + "/" + JOB_ID + "/metadata"))
            .willReturn(okJson("{\"solverStatus\":\"SOLVING_COMPLETED\",\"score\":\"0hard/-5soft\"}")));

        stubFor(get(urlEqualTo(collectionPath + "/" + JOB_ID))
            .willReturn(okJson("{\"solverStatus\":\"SOLVING_COMPLETED\",\"score\":\"0hard/-5soft\"," +
                "\"modelOutput\":{\"routes\":[]}}")));

        Solve task = testTask(wm)
            .wait(Property.ofValue(true))
            .pollInterval(Property.ofValue(java.time.Duration.ofMillis(50)))
            .build();

        Solve.Output output = task.run(runContextFactory.of());

        assertThat(output.getJobId(), is(JOB_ID));
        assertThat(output.getSolverStatus(), is("SOLVING_COMPLETED"));
        assertThat(output.getScore(), is("0hard/-5soft"));
        assertThat(output.getModelOutput(), notNullValue());
    }

    @Test
    void killCancelsRemoteJobAndThrows(WireMockRuntimeInfo wm) throws Exception {
        String collectionPath = "/api/models/field-service-routing/v1/route-plans";

        stubFor(post(urlEqualTo(collectionPath))
            .willReturn(okJson("\"" + JOB_ID + "\"")));

        // Metadata always returns SOLVING_ACTIVE so the poll loop never exits on its own.
        stubFor(get(urlEqualTo(collectionPath + "/" + JOB_ID + "/metadata"))
            .willReturn(okJson("{\"solverStatus\":\"SOLVING_ACTIVE\"}")));

        stubFor(delete(urlEqualTo(collectionPath + "/" + JOB_ID))
            .willReturn(aResponse().withStatus(200)));

        Solve task = testTask(wm)
            .wait(Property.ofValue(true))
            .pollInterval(Property.ofValue(Duration.ofMillis(50)))
            .requestTimeout(Property.ofValue(Duration.ofMinutes(10)))
            .build();

        CompletableFuture<Exception> result = CompletableFuture.supplyAsync(() -> {
            try {
                task.run(runContextFactory.of());
                return null;
            } catch (Exception e) {
                return e;
            }
        });

        // Wait until the job has been submitted and the poll loop is sleeping.
        Thread.sleep(200);
        task.kill();

        Exception thrown = result.get();
        assertThat(thrown, instanceOf(KilledException.class));
        verify(deleteRequestedFor(urlEqualTo(collectionPath + "/" + JOB_ID)));
    }

    private static Solve.SolveBuilder<?, ?> testTask(WireMockRuntimeInfo wm) {
        return Solve.builder()
            .apiKey(Property.ofValue("test-api-key"))
            .model(Property.ofValue(TimefoldModel.FIELD_SERVICE_ROUTING))
            .baseUrl(Property.ofValue("http://localhost:" + wm.getHttpPort()))
            .modelInput(Map.of(
                "vehicles", List.of(Map.of("id", "Ann")),
                "visits", List.of()
            ));
    }
}
