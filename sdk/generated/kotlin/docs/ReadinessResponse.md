
# ReadinessResponse

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **status** | **kotlin.String** |  |  |
| **ready** | **kotlin.Boolean** |  |  |
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
| **failureReasons** | **kotlin.collections.List&lt;kotlin.String&gt;** |  |  |
| **requestId** | **kotlin.String** |  |  [optional] |
| **degradedReason** | **kotlin.String** |  |  [optional] |
