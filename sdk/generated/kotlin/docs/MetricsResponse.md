
# MetricsResponse

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **schemaVersion** | **kotlin.Int** |  |  |
| **collectedAt** | **kotlin.String** |  |  |
| **currentDocuments** | **kotlin.Int** |  |  |
| **currentEdges** | **kotlin.Int** |  |  |
| **dependencies** | [**GokoreitansekiserviceapiDependencyMetrics**](GokoreitansekiserviceapiDependencyMetrics.md) |  |  |
| **projection** | [**GokoreitansekiserviceapiProjectionMetrics**](GokoreitansekiserviceapiProjectionMetrics.md) |  |  |
| **pendingOverlay** | [**GokoreitansekiserviceapiPendingOverlayMetrics**](GokoreitansekiserviceapiPendingOverlayMetrics.md) |  |  |
| **outbox** | [**GokoreitansekiserviceapiOutboxMetrics**](GokoreitansekiserviceapiOutboxMetrics.md) |  |  |
| **reconcile** | [**GokoreitansekiserviceapiReconcileMetrics**](GokoreitansekiserviceapiReconcileMetrics.md) |  |  |
| **watcher** | [**GokoreitansekiserviceapiWatcherMetrics**](GokoreitansekiserviceapiWatcherMetrics.md) |  |  |
| **failedEvents** | **kotlin.Long** |  |  |
| **requests** | [**GokoreitansekiserviceapiLatencyMetrics**](GokoreitansekiserviceapiLatencyMetrics.md) |  |  |
| **indexing** | [**GokoreitansekiserviceapiLatencyMetrics**](GokoreitansekiserviceapiLatencyMetrics.md) |  |  |
| **ready** | **kotlin.Boolean** |  |  |
| **degraded** | **kotlin.Boolean** |  |  |
| **failureReasons** | **kotlin.collections.List&lt;kotlin.String&gt;** |  |  |
| **collections** | [**kotlin.collections.List&lt;CollectionMetrics&gt;**](CollectionMetrics.md) |  |  |
| **degradedReason** | **kotlin.String** |  |  [optional] |
| **scope** | [**MetricsScope**](MetricsScope.md) |  |  [optional] |
