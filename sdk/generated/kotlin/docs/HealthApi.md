# HealthApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**v1HealthGet**](HealthApi.md#v1HealthGet) | **GET** /v1/health | Liveness check |
| [**v1LiveGet**](HealthApi.md#v1LiveGet) | **GET** /v1/live | Liveness check |
| [**v1ReadyGet**](HealthApi.md#v1ReadyGet) | **GET** /v1/ready | Readiness check |


<a id="v1HealthGet"></a>
# **v1HealthGet**
> HealthDto v1HealthGet(xRequestId)

Liveness check

Unauthenticated process-only liveness probe.

### Example
```kotlin
// Import classes:
//import org.openapitools.client.infrastructure.*
//import org.openapitools.client.models.*

val apiInstance = HealthApi()
val xRequestId : kotlin.String = xRequestId_example // kotlin.String | Caller-provided correlation id; generated when omitted.
try {
    val result : HealthDto = apiInstance.v1HealthGet(xRequestId)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling HealthApi#v1HealthGet")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling HealthApi#v1HealthGet")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **xRequestId** | **kotlin.String**| Caller-provided correlation id; generated when omitted. | [optional] |

### Return type

[**HealthDto**](HealthDto.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a id="v1LiveGet"></a>
# **v1LiveGet**
> HealthDto v1LiveGet(xRequestId)

Liveness check

Unauthenticated process-only liveness probe.

### Example
```kotlin
// Import classes:
//import org.openapitools.client.infrastructure.*
//import org.openapitools.client.models.*

val apiInstance = HealthApi()
val xRequestId : kotlin.String = xRequestId_example // kotlin.String | Caller-provided correlation id; generated when omitted.
try {
    val result : HealthDto = apiInstance.v1LiveGet(xRequestId)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling HealthApi#v1LiveGet")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling HealthApi#v1LiveGet")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **xRequestId** | **kotlin.String**| Caller-provided correlation id; generated when omitted. | [optional] |

### Return type

[**HealthDto**](HealthDto.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a id="v1ReadyGet"></a>
# **v1ReadyGet**
> ReadinessResponse v1ReadyGet(xRequestId)

Readiness check

Unauthenticated dependency, projection, backlog, and watcher readiness.

### Example
```kotlin
// Import classes:
//import org.openapitools.client.infrastructure.*
//import org.openapitools.client.models.*

val apiInstance = HealthApi()
val xRequestId : kotlin.String = xRequestId_example // kotlin.String | Caller-provided correlation id; generated when omitted.
try {
    val result : ReadinessResponse = apiInstance.v1ReadyGet(xRequestId)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling HealthApi#v1ReadyGet")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling HealthApi#v1ReadyGet")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **xRequestId** | **kotlin.String**| Caller-provided correlation id; generated when omitted. | [optional] |

### Return type

[**ReadinessResponse**](ReadinessResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json
