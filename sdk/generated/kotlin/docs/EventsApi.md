# EventsApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**v1EventsGet**](EventsApi.md#v1EventsGet) | **GET** /v1/events | Subscribe to committed document changes (SSE) |


<a id="v1EventsGet"></a>
# **v1EventsGet**
> kotlin.Any v1EventsGet(xRequestId)

Subscribe to committed document changes (SSE)

A &#x60;text/event-stream&#x60; of changes that have actually become searchable. Each event carries the feed&#39;s own &#x60;sequence&#x60;, so a client that reconnects can send the last one it saw as &#x60;Last-Event-ID&#x60; (or &#x60;?after&#x3D;&#x60;) and receive only what it missed. A sequence that has aged out of the retained window yields a &#x60;resync&#x60; event instead of a silently incomplete stream.  The feed is process-local: a daemon restart resets sequences, so a client resuming across one must reload rather than resume.  Events are scoped to the collections the credential may read; a document outside that scope never appears, not even as a gap.

### Example
```kotlin
// Import classes:
//import org.openapitools.client.infrastructure.*
//import org.openapitools.client.models.*

val apiInstance = EventsApi()
val xRequestId : kotlin.String = xRequestId_example // kotlin.String | Caller-provided correlation id; generated when omitted.
try {
    val result : kotlin.Any = apiInstance.v1EventsGet(xRequestId)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling EventsApi#v1EventsGet")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling EventsApi#v1EventsGet")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **xRequestId** | **kotlin.String**| Caller-provided correlation id; generated when omitted. | [optional] |

### Return type

[**kotlin.Any**](kotlin.Any.md)

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
 - **Accept**: application/octet-stream, application/json
