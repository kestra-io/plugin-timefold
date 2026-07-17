package io.kestra.plugin.timefold;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.http.client.configurations.TimeoutConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.time.Duration;

/**
 * Base class holding the connection and model-selection properties common to
 * all Timefold Platform tasks.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
abstract class AbstractTimefoldTask extends Task {
    @Schema(
        title = "The Timefold Platform API key",
        description = "Sent as the `X-API-KEY` header on every request. Provide it via a secret, " +
            "for example `apiKey: \"{{ secret('TIMEFOLD_API_KEY') }}\"`. The key must have access " +
            "to the selected `model`."
    )
    @PluginProperty(secret = true, group = "connection")
    @NotNull
    protected Property<String> apiKey;

    @Schema(
        title = "The Timefold model to solve with",
        description = "Determines the REST resource the dataset is submitted to. " +
            "`FIELD_SERVICE_ROUTING` uses the `route-plans` endpoint and `EMPLOYEE_SCHEDULING` " +
            "uses the `schedules` endpoint."
    )
    @PluginProperty(group = "connection")
    @NotNull
    protected Property<TimefoldModel> model;

    @Schema(
        title = "Base URL of the Timefold Platform API",
        description = "Defaults to the Timefold managed cloud. Override this when running a " +
            "self-hosted Timefold deployment. The model and resource path segments are appended " +
            "automatically, so provide only the host, e.g. `https://app.timefold.ai`."
    )
    @PluginProperty(group = "connection")
    @NotNull
    @lombok.Builder.Default
    protected Property<String> baseUrl = Property.ofValue("https://app.timefold.ai");

    /**
     * Renders the three connection properties shared by all tasks.
     */
    protected Connection renderConnection(RunContext runContext) throws Exception {
        String rApiKey = runContext.render(this.apiKey).as(String.class).orElseThrow();
        TimefoldModel rModel = runContext.render(this.model).as(TimefoldModel.class).orElseThrow();
        String rBaseUrl = runContext.render(this.baseUrl).as(String.class).orElse("https://app.timefold.ai");
        return new Connection(rApiKey, rModel, rBaseUrl);
    }

    /**
     * Builds an {@link HttpClient} with the standard Timefold Platform timeouts.
     * Callers must close it, typically via try-with-resources.
     */
    protected HttpClient buildHttpClient(RunContext runContext) throws Exception {
        HttpConfiguration configuration = HttpConfiguration.builder()
            .timeout(TimeoutConfiguration.builder()
                .connectTimeout(Property.ofValue(Duration.ofSeconds(30)))
                .readIdleTimeout(Property.ofValue(Duration.ofSeconds(60)))
                .build())
            .build();
        return HttpClient.builder()
            .runContext(runContext)
            .configuration(configuration)
            .build();
    }

    /**
     * Returns a pre-configured request builder with the API key and Accept headers set.
     */
    protected static HttpRequest.HttpRequestBuilder baseRequest(String url, String apiKey) {
        return HttpRequest.builder()
            .uri(URI.create(url))
            .addHeader("X-API-KEY", apiKey)
            .addHeader("Accept", "application/json");
    }

    /**
     * Builds the collection URL for the given model,
     * e.g. {@code https://app.timefold.ai/api/models/field-service-routing/v1/route-plans}.
     */
    protected static String collectionUrl(String baseUrl, TimefoldModel model) {
        return String.format(
            "%s/api/models/%s/v1/%s",
            stripTrailingSlash(baseUrl),
            model.modelId(),
            model.resource()
        );
    }

    protected static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Rendered values of the three connection properties.
     */
    public record Connection(String apiKey, TimefoldModel model, String baseUrl) {}
}
