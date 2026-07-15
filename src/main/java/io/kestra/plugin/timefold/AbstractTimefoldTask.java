package io.kestra.plugin.timefold;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Base class holding the connection and model-selection properties common to
 * all Timefold Platform tasks.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractTimefoldTask extends Task {
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
    @NotNull
    protected Property<TimefoldModel> model;

    @Schema(
        title = "Base URL of the Timefold Platform API",
        description = "Defaults to the Timefold managed cloud. Override this when running a " +
            "self-hosted Timefold deployment. The model and resource path segments are appended " +
            "automatically, so provide only the host, e.g. `https://app.timefold.ai`."
    )
    @NotNull
    @lombok.Builder.Default
    protected Property<String> baseUrl = Property.ofValue("https://app.timefold.ai");
}
