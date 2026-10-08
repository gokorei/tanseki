# MetricsApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**v1MetricsGet**](MetricsApi.md#v1MetricsGet) | **GET** /v1/metrics | Read operational metrics |


<a id="v1MetricsGet"></a>
# **v1MetricsGet**
> MetricsResponse v1MetricsGet(xRequestId, collection)

Read operational metrics

Authoritative current projection, backlog, watcher, and latency signals.

### Example
```kotlin
// Import classes:
//import org.openapitools.client.infrastructure.*
//import org.openapitools.client.models.*

val apiInstance = MetricsApi()
val xRequestId : kotlin.String = xRequestId_example // kotlin.String | Caller-provided correlation id; generated when omitted.
val collection : kotlin.String = collection_example // kotlin.String | Collection scope for this metrics read.
try {
    val result : MetricsResponse = apiInstance.v1MetricsGet(xRequestId, collection)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling MetricsApi#v1MetricsGet")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling MetricsApi#v1MetricsGet")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **xRequestId** | **kotlin.String**| Caller-provided correlation id; generated when omitted. | [optional] |
| **collection** | **kotlin.String**| Collection scope for this metrics read. | [optional] |

### Return type

[**MetricsResponse**](MetricsResponse.md)

### Authorization


Configure apiKey:
    ApiClient.apiKey["X-API-Key"] = ""
    ApiClient.apiKeyPrefix["X-API-Key"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```
Configure bearerAuth dynamically:
```kotlin
apiInstance.accessTokenProvider = { "" }
```

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json
