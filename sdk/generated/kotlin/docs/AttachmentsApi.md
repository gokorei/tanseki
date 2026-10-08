# AttachmentsApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**v1BlobsHashGet**](AttachmentsApi.md#v1BlobsHashGet) | **GET** /v1/blobs/{hash} | Download an attachment by digest |
| [**v1BlobsPost**](AttachmentsApi.md#v1BlobsPost) | **POST** /v1/blobs | Upload an attachment |


<a id="v1BlobsHashGet"></a>
# **v1BlobsHashGet**
> kotlin.Any v1BlobsHashGet(hash, xRequestId)

Download an attachment by digest

Returns the original bytes, after verifying them against the digest the hash identifies. A blob whose bytes no longer match is refused with 500 blob_corrupt rather than served.  The response is always application/octet-stream: a blob stores bytes and no media type, and inferring one from the content would be a guess. Store the type alongside the reference if you need it.

### Example
```kotlin
// Import classes:
//import org.openapitools.client.infrastructure.*
//import org.openapitools.client.models.*

val apiInstance = AttachmentsApi()
val hash : kotlin.String = hash_example // kotlin.String | Lowercase SHA-256 digest, as returned by the upload route.
val xRequestId : kotlin.String = xRequestId_example // kotlin.String | Caller-provided correlation id; generated when omitted.
try {
    val result : kotlin.Any = apiInstance.v1BlobsHashGet(hash, xRequestId)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling AttachmentsApi#v1BlobsHashGet")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling AttachmentsApi#v1BlobsHashGet")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **hash** | **kotlin.String**| Lowercase SHA-256 digest, as returned by the upload route. | |
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

<a id="v1BlobsPost"></a>
# **v1BlobsPost**
> BlobRefDto v1BlobsPost(body, xRequestId)

Upload an attachment

Stores the request body verbatim and returns its content-addressed reference. Uploading identical bytes twice yields the same reference. The body is read as raw binary, never base64 inside JSON.

### Example
```kotlin
// Import classes:
//import org.openapitools.client.infrastructure.*
//import org.openapitools.client.models.*

val apiInstance = AttachmentsApi()
val body : kotlin.Any =  // kotlin.Any | Raw attachment bytes, read verbatim. At most 33554432 bytes.
val xRequestId : kotlin.String = xRequestId_example // kotlin.String | Caller-provided correlation id; generated when omitted.
try {
    val result : BlobRefDto = apiInstance.v1BlobsPost(body, xRequestId)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling AttachmentsApi#v1BlobsPost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling AttachmentsApi#v1BlobsPost")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **body** | **kotlin.Any**| Raw attachment bytes, read verbatim. At most 33554432 bytes. | |
| **xRequestId** | **kotlin.String**| Caller-provided correlation id; generated when omitted. | [optional] |

### Return type

[**BlobRefDto**](BlobRefDto.md)

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

 - **Content-Type**: application/octet-stream
 - **Accept**: application/json
