# SearchApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**v1SearchGet**](SearchApi.md#v1SearchGet) | **GET** /v1/search | Search documents |


<a id="v1SearchGet"></a>
# **v1SearchGet**
> SearchResponse v1SearchGet(xRequestId, q, collection, tags, fm, mode, limit, offset)

Search documents

Full-text search, with optional filters. &#x60;q&#x60; may be omitted when a filter is present: &#x60;fm&#x3D;repo&#x3D;org/repo&#x60; alone answers \&quot;which documents carry this property\&quot;, bounded by &#x60;limit&#x60;. With neither &#x60;q&#x60; nor a filter the query is empty and returns nothing rather than the whole store, so an omitted parameter is never mistaken for a successful empty result. A filter value is resolved by the ordinary scalar rules — &#x60;42&#x60; and &#x60;true&#x60; select the number 42 and the boolean true, because that is what the document&#39;s own YAML said — and quoting forces the string: &#x60;fm&#x3D;pr&#x3D;\&quot;42\&quot;&#x60; selects a document storing \&quot;42\&quot; as text, and &#x60;fm&#x3D;pr&#x3D;42&#x60; will not. &#x60;mode&#x3D;hybrid&#x60; adds vector kNN ranking.

### Example
```kotlin
// Import classes:
//import org.openapitools.client.infrastructure.*
//import org.openapitools.client.models.*

val apiInstance = SearchApi()
val xRequestId : kotlin.String = xRequestId_example // kotlin.String | Caller-provided correlation id; generated when omitted.
val q : kotlin.String = q_example // kotlin.String | Query keywords.
val collection : kotlin.String = collection_example // kotlin.String | Restrict to a collection.
val tags : kotlin.collections.List<kotlin.String> =  // kotlin.collections.List<kotlin.String> | Require every listed tag; repeat for multiple tags.
val fm : kotlin.collections.List<kotlin.String> =  // kotlin.collections.List<kotlin.String> | Frontmatter equality filters, `key=value`; repeat for multiple filters. Usable without `q`: a filter on its own enumerates what matches. A value is resolved by ordinary scalar rules — `42`, `1.5` and `true` select numbers and a boolean, `org/repo` and `1.2.3` select text — and quoting forces a string: `\"42\"` selects a stored string, unquoted `42` does not. An empty value (`fm=pr=`), an unclosed quote, or a contract key (`fm=tags=review`) is rejected; filter tags with `tags` instead.
val mode : kotlin.String = mode_example // kotlin.String | `lexical` (default) or `hybrid`.
val limit : kotlin.Int = 56 // kotlin.Int | Page size (1..500, default 50).
val offset : kotlin.Int = 56 // kotlin.Int | Zero-based offset.
try {
    val result : SearchResponse = apiInstance.v1SearchGet(xRequestId, q, collection, tags, fm, mode, limit, offset)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling SearchApi#v1SearchGet")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling SearchApi#v1SearchGet")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **xRequestId** | **kotlin.String**| Caller-provided correlation id; generated when omitted. | [optional] |
| **q** | **kotlin.String**| Query keywords. | [optional] |
| **collection** | **kotlin.String**| Restrict to a collection. | [optional] |
| **tags** | [**kotlin.collections.List&lt;kotlin.String&gt;**](kotlin.String.md)| Require every listed tag; repeat for multiple tags. | [optional] |
| **fm** | [**kotlin.collections.List&lt;kotlin.String&gt;**](kotlin.String.md)| Frontmatter equality filters, &#x60;key&#x3D;value&#x60;; repeat for multiple filters. Usable without &#x60;q&#x60;: a filter on its own enumerates what matches. A value is resolved by ordinary scalar rules — &#x60;42&#x60;, &#x60;1.5&#x60; and &#x60;true&#x60; select numbers and a boolean, &#x60;org/repo&#x60; and &#x60;1.2.3&#x60; select text — and quoting forces a string: &#x60;\&quot;42\&quot;&#x60; selects a stored string, unquoted &#x60;42&#x60; does not. An empty value (&#x60;fm&#x3D;pr&#x3D;&#x60;), an unclosed quote, or a contract key (&#x60;fm&#x3D;tags&#x3D;review&#x60;) is rejected; filter tags with &#x60;tags&#x60; instead. | [optional] |
| **mode** | **kotlin.String**| &#x60;lexical&#x60; (default) or &#x60;hybrid&#x60;. | [optional] |
| **limit** | **kotlin.Int**| Page size (1..500, default 50). | [optional] |
| **offset** | **kotlin.Int**| Zero-based offset. | [optional] |

### Return type

[**SearchResponse**](SearchResponse.md)

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
