package io.kestra.plugin.timefold;

/**
 * The Timefold Platform models that this plugin supports.
 * <p>
 * Each model is exposed on the platform under
 * {@code /api/models/{modelId}/v1/{resource}} where the resource collection
 * differs per model (Field Service Routing and Pick-up and Delivery Routing use
 * {@code route-plans}; Employee Scheduling uses {@code schedules}).
 */
public enum TimefoldModel {
    FIELD_SERVICE_ROUTING("field-service-routing", "route-plans"),
    EMPLOYEE_SCHEDULING("employee-scheduling", "schedules"),
    PICKUP_DELIVERY_ROUTING("pickup-delivery-routing", "route-plans");

    private final String modelId;
    private final String resource;

    TimefoldModel(String modelId, String resource) {
        this.modelId = modelId;
        this.resource = resource;
    }

    /**
     * The model identifier used in the API path, e.g. {@code field-service-routing}.
     */
    public String modelId() {
        return modelId;
    }

    /**
     * The REST collection resource for this model, e.g. {@code route-plans}
     * for Field Service Routing or {@code schedules} for Employee Scheduling.
     */
    public String resource() {
        return resource;
    }
}
