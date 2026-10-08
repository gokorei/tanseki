# DocumentsApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**v1DocumentsBacklinksPost**](DocumentsApi.md#v1DocumentsBacklinksPost) | **POST** /v1/documents:backlinks | List documents linking to a document |
| [**v1DocumentsDeletePost**](DocumentsApi.md#v1DocumentsDeletePost) | **POST** /v1/documents:delete | Delete a document |
| [**v1DocumentsDeletedPost**](DocumentsApi.md#v1DocumentsDeletedPost) | **POST** /v1/documents:deleted | List deleted documents |
| [**v1DocumentsGet**](DocumentsApi.md#v1DocumentsGet) | **GET** /v1/documents | List documents |
| [**v1DocumentsGetPost**](DocumentsApi.md#v1DocumentsGetPost) | **POST** /v1/documents:get | Get a document |
| [**v1DocumentsHistoryPost**](DocumentsApi.md#v1DocumentsHistoryPost) | **POST** /v1/documents:history | Document history |
| [**v1DocumentsQueryPost**](DocumentsApi.md#v1DocumentsQueryPost) | **POST** /v1/documents:query | Get many documents |
| [**v1DocumentsRenamePost**](DocumentsApi.md#v1DocumentsRenamePost) | **POST** /v1/documents:rename | Rename a document |
| [**v1DocumentsRestorePost**](DocumentsApi.md#v1DocumentsRestorePost) | **POST** /v1/documents:restore | Restore a deleted document |
| [**v1DocumentsTraversePost**](DocumentsApi.md#v1DocumentsTraversePost) | **POST** /v1/documents:traverse | Traverse the graph |
| [**v1DocumentsUpsertPost**](DocumentsApi.md#v1DocumentsUpsertPost) | **POST** /v1/documents:upsert | Create or update a document |


<a id="v1DocumentsBacklinksPost"></a>
# **v1DocumentsBacklinksPost**
> BacklinksResponse v1DocumentsBacklinksPost(backlinksRequest, xRequestId)

List documents linking to a document

Return the documents that link TO a document, optionally narrowed to one relationship (links-to, references, embeds, mentions). The reverse of documents:traverse.

### Example
```kotlin
// Import classes:
//import org.openapitools.client.infrastructure.*
//import org.openapitools.client.models.*

val apiInstance = DocumentsApi()
val backlinksRequest : BacklinksRequest =  // BacklinksRequest | Document id, optional relationship, and collection.
val xRequestId : kotlin.String = xRequestId_example // kotlin.String | Caller-provided correlation id; generated when omitted.
try {
    val result : BacklinksResponse = apiInstance.v1DocumentsBacklinksPost(backlinksRequest, xRequestId)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DocumentsApi#v1DocumentsBacklinksPost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DocumentsApi#v1DocumentsBacklinksPost")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **backlinksRequest** | [**BacklinksRequest**](BacklinksRequest.md)| Document id, optional relationship, and collection. | |
| **xRequestId** | **kotlin.String**| Caller-provided correlation id; generated when omitted. | [optional] |

### Return type

[**BacklinksResponse**](BacklinksResponse.md)

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

 - **Content-Type**: application/json
 - **Accept**: application/json

<a id="v1DocumentsDeletePost"></a>
# **v1DocumentsDeletePost**
> DeleteResult v1DocumentsDeletePost(deleteDocumentRequest, xRequestId, ifMatch, idempotencyKey)

Delete a document

Tombstones the document while preserving its history; supports &#x60;If-Match&#x60; and &#x60;Idempotency-Key&#x60;.

### Example
```kotlin
// Import classes:
//import org.openapitools.client.infrastructure.*
//import org.openapitools.client.models.*

val apiInstance = DocumentsApi()
val deleteDocumentRequest : DeleteDocumentRequest =  // DeleteDocumentRequest | Document id and optional deletion metadata.
val xRequestId : kotlin.String = xRequestId_example // kotlin.String | Caller-provided correlation id; generated when omitted.
val ifMatch : kotlin.String = ifMatch_example // kotlin.String | Current revision ETag required for this write.
val idempotencyKey : kotlin.String = idempotencyKey_example // kotlin.String | Request-bound key used to reserve and replay a write.
try {
    val result : DeleteResult = apiInstance.v1DocumentsDeletePost(deleteDocumentRequest, xRequestId, ifMatch, idempotencyKey)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DocumentsApi#v1DocumentsDeletePost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DocumentsApi#v1DocumentsDeletePost")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **deleteDocumentRequest** | [**DeleteDocumentRequest**](DeleteDocumentRequest.md)| Document id and optional deletion metadata. | |
| **xRequestId** | **kotlin.String**| Caller-provided correlation id; generated when omitted. | [optional] |
| **ifMatch** | **kotlin.String**| Current revision ETag required for this write. | [optional] |
| **idempotencyKey** | **kotlin.String**| Request-bound key used to reserve and replay a write. | [optional] |

### Return type

[**DeleteResult**](DeleteResult.md)

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

 - **Content-Type**: application/json
 - **Accept**: application/json

<a id="v1DocumentsDeletedPost"></a>
# **v1DocumentsDeletedPost**
> ListDeletedResponse v1DocumentsDeletedPost(listDeletedRequest, xRequestId)

List deleted documents

Tombstones only, with the revision and time each was deleted. Deleted documents are absent from every other listing. The page size is capped at 200, like every other listing.

### Example
```kotlin
// Import classes:
//import org.openapitools.client.infrastructure.*
//import org.openapitools.client.models.*

val apiInstance = DocumentsApi()
val listDeletedRequest : ListDeletedRequest =  // ListDeletedRequest | Optional collection and page size.
val xRequestId : kotlin.String = xRequestId_example // kotlin.String | Caller-provided correlation id; generated when omitted.
try {
    val result : ListDeletedResponse = apiInstance.v1DocumentsDeletedPost(listDeletedRequest, xRequestId)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DocumentsApi#v1DocumentsDeletedPost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DocumentsApi#v1DocumentsDeletedPost")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **listDeletedRequest** | [**ListDeletedRequest**](ListDeletedRequest.md)| Optional collection and page size. | |
| **xRequestId** | **kotlin.String**| Caller-provided correlation id; generated when omitted. | [optional] |

### Return type

[**ListDeletedResponse**](ListDeletedResponse.md)

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

 - **Content-Type**: application/json
 - **Accept**: application/json

<a id="v1DocumentsGet"></a>
# **v1DocumentsGet**
> DocumentListResponse v1DocumentsGet(xRequestId, collection, pathPrefix, limit, offset, cursor)

List documents

Paginated document references, optionally scoped to a collection.

### Example
```kotlin
// Import classes:
//import org.openapitools.client.infrastructure.*
//import org.openapitools.client.models.*

val apiInstance = DocumentsApi()
val xRequestId : kotlin.String = xRequestId_example // kotlin.String | Caller-provided correlation id; generated when omitted.
val collection : kotlin.String = collection_example // kotlin.String | Collection to list.
val pathPrefix : kotlin.String = pathPrefix_example // kotlin.String | Only documents whose path starts with this prefix, for building a folder tree.
val limit : kotlin.Int = 56 // kotlin.Int | Page size (1..500, default 50).
val offset : kotlin.Int = 56 // kotlin.Int | Zero-based offset. Retained for existing clients; prefer `cursor`.
val cursor : kotlin.String = cursor_example // kotlin.String | Opaque token from a previous response's `nextCursor`; resumes after it. Mutually exclusive with `offset`.
try {
    val result : DocumentListResponse = apiInstance.v1DocumentsGet(xRequestId, collection, pathPrefix, limit, offset, cursor)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DocumentsApi#v1DocumentsGet")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DocumentsApi#v1DocumentsGet")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **xRequestId** | **kotlin.String**| Caller-provided correlation id; generated when omitted. | [optional] |
| **collection** | **kotlin.String**| Collection to list. | [optional] |
| **pathPrefix** | **kotlin.String**| Only documents whose path starts with this prefix, for building a folder tree. | [optional] |
| **limit** | **kotlin.Int**| Page size (1..500, default 50). | [optional] |
| **offset** | **kotlin.Int**| Zero-based offset. Retained for existing clients; prefer &#x60;cursor&#x60;. | [optional] |
| **cursor** | **kotlin.String**| Opaque token from a previous response&#39;s &#x60;nextCursor&#x60;; resumes after it. Mutually exclusive with &#x60;offset&#x60;. | [optional] |

### Return type

[**DocumentListResponse**](DocumentListResponse.md)

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

<a id="v1DocumentsGetPost"></a>
# **v1DocumentsGetPost**
> DocumentDto v1DocumentsGetPost(getDocumentRequest, xRequestId)

Get a document

Fetch one document by id. Ids are path-derived and contain &#39;/&#39;, so they travel in the body.

### Example
```kotlin
// Import classes:
//import org.openapitools.client.infrastructure.*
//import org.openapitools.client.models.*

val apiInstance = DocumentsApi()
val getDocumentRequest : GetDocumentRequest =  // GetDocumentRequest | Document id and optional collection.
val xRequestId : kotlin.String = xRequestId_example // kotlin.String | Caller-provided correlation id; generated when omitted.
try {
    val result : DocumentDto = apiInstance.v1DocumentsGetPost(getDocumentRequest, xRequestId)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DocumentsApi#v1DocumentsGetPost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DocumentsApi#v1DocumentsGetPost")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **getDocumentRequest** | [**GetDocumentRequest**](GetDocumentRequest.md)| Document id and optional collection. | |
| **xRequestId** | **kotlin.String**| Caller-provided correlation id; generated when omitted. | [optional] |

### Return type

[**DocumentDto**](DocumentDto.md)

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

 - **Content-Type**: application/json
 - **Accept**: application/json

<a id="v1DocumentsHistoryPost"></a>
# **v1DocumentsHistoryPost**
> HistoryResponse v1DocumentsHistoryPost(historyRequest, xRequestId)

Document history

Revision list including the deletion revision, oldest-first and paginated.

### Example
```kotlin
// Import classes:
//import org.openapitools.client.infrastructure.*
//import org.openapitools.client.models.*

val apiInstance = DocumentsApi()
val historyRequest : HistoryRequest =  // HistoryRequest | Document id, collection, and pagination bounds.
val xRequestId : kotlin.String = xRequestId_example // kotlin.String | Caller-provided correlation id; generated when omitted.
try {
    val result : HistoryResponse = apiInstance.v1DocumentsHistoryPost(historyRequest, xRequestId)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DocumentsApi#v1DocumentsHistoryPost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DocumentsApi#v1DocumentsHistoryPost")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **historyRequest** | [**HistoryRequest**](HistoryRequest.md)| Document id, collection, and pagination bounds. | |
| **xRequestId** | **kotlin.String**| Caller-provided correlation id; generated when omitted. | [optional] |

### Return type

[**HistoryResponse**](HistoryResponse.md)

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

 - **Content-Type**: application/json
 - **Accept**: application/json

<a id="v1DocumentsQueryPost"></a>
# **v1DocumentsQueryPost**
> QueryDocumentsResponse v1DocumentsQueryPost(queryDocumentsRequest, xRequestId)

Get many documents

Batch-fetch documents by id; missing ids are omitted from the response.

### Example
```kotlin
// Import classes:
//import org.openapitools.client.infrastructure.*
//import org.openapitools.client.models.*

val apiInstance = DocumentsApi()
val queryDocumentsRequest : QueryDocumentsRequest =  // QueryDocumentsRequest | Document ids and optional collection.
val xRequestId : kotlin.String = xRequestId_example // kotlin.String | Caller-provided correlation id; generated when omitted.
try {
    val result : QueryDocumentsResponse = apiInstance.v1DocumentsQueryPost(queryDocumentsRequest, xRequestId)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DocumentsApi#v1DocumentsQueryPost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DocumentsApi#v1DocumentsQueryPost")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **queryDocumentsRequest** | [**QueryDocumentsRequest**](QueryDocumentsRequest.md)| Document ids and optional collection. | |
| **xRequestId** | **kotlin.String**| Caller-provided correlation id; generated when omitted. | [optional] |

### Return type

[**QueryDocumentsResponse**](QueryDocumentsResponse.md)

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

 - **Content-Type**: application/json
 - **Accept**: application/json

<a id="v1DocumentsRenamePost"></a>
# **v1DocumentsRenamePost**
> RenameResult v1DocumentsRenamePost(renameDocumentRequest, xRequestId)

Rename a document

Move a document to a new id, rewriting the inbound &#x60;[[wikilinks]]&#x60; that pointed at it. A document&#39;s id is its path, so the two move together; this cannot be expressed as a create plus a delete. Fails with a conflict when the target path is held by a live document, or when &#x60;If-Match&#x60; does not match the current revision. A store that cannot rename reports 501. Frontmatter references (&#x60;files:&#x60;, &#x60;repo:&#x60;, &#x60;pr:&#x60;) are not rewritten, so a document that embeds the renamed note by frontmatter rather than by wikilink loses that edge on the next index.

### Example
```kotlin
// Import classes:
//import org.openapitools.client.infrastructure.*
//import org.openapitools.client.models.*

val apiInstance = DocumentsApi()
val renameDocumentRequest : RenameDocumentRequest =  // RenameDocumentRequest | Current document id, new id, optional message, author, and revision.
val xRequestId : kotlin.String = xRequestId_example // kotlin.String | Caller-provided correlation id; generated when omitted.
try {
    val result : RenameResult = apiInstance.v1DocumentsRenamePost(renameDocumentRequest, xRequestId)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DocumentsApi#v1DocumentsRenamePost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DocumentsApi#v1DocumentsRenamePost")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **renameDocumentRequest** | [**RenameDocumentRequest**](RenameDocumentRequest.md)| Current document id, new id, optional message, author, and revision. | |
| **xRequestId** | **kotlin.String**| Caller-provided correlation id; generated when omitted. | [optional] |

### Return type

[**RenameResult**](RenameResult.md)

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

 - **Content-Type**: application/json
 - **Accept**: application/json

<a id="v1DocumentsRestorePost"></a>
# **v1DocumentsRestorePost**
> RestoreResult v1DocumentsRestorePost(restoreDocumentRequest, xRequestId)

Restore a deleted document

Return a tombstoned document to the live set under its original id and path. Fails with a conflict when the path has since been claimed by another document.

### Example
```kotlin
// Import classes:
//import org.openapitools.client.infrastructure.*
//import org.openapitools.client.models.*

val apiInstance = DocumentsApi()
val restoreDocumentRequest : RestoreDocumentRequest =  // RestoreDocumentRequest | Document id, optional message, author, and revision.
val xRequestId : kotlin.String = xRequestId_example // kotlin.String | Caller-provided correlation id; generated when omitted.
try {
    val result : RestoreResult = apiInstance.v1DocumentsRestorePost(restoreDocumentRequest, xRequestId)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DocumentsApi#v1DocumentsRestorePost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DocumentsApi#v1DocumentsRestorePost")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **restoreDocumentRequest** | [**RestoreDocumentRequest**](RestoreDocumentRequest.md)| Document id, optional message, author, and revision. | |
| **xRequestId** | **kotlin.String**| Caller-provided correlation id; generated when omitted. | [optional] |

### Return type

[**RestoreResult**](RestoreResult.md)

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

 - **Content-Type**: application/json
 - **Accept**: application/json

<a id="v1DocumentsTraversePost"></a>
# **v1DocumentsTraversePost**
> TraverseResponse v1DocumentsTraversePost(traverseRequest, xRequestId)

Traverse the graph

Walk typed edges (links-to, references, embeds, mentions) from a document. &#x60;rel&#x60; names a relationship type, not a frontmatter key: &#x60;files&#x60;, &#x60;repo&#x60;, &#x60;pr&#x60;, &#x60;jira&#x60; and &#x60;author&#x60; say where an edge comes from and are rejected with a 400 naming the four valid values rather than answered with an empty list.

### Example
```kotlin
// Import classes:
//import org.openapitools.client.infrastructure.*
//import org.openapitools.client.models.*

val apiInstance = DocumentsApi()
val traverseRequest : TraverseRequest =  // TraverseRequest | Document id, relationship, depth, and collection.
val xRequestId : kotlin.String = xRequestId_example // kotlin.String | Caller-provided correlation id; generated when omitted.
try {
    val result : TraverseResponse = apiInstance.v1DocumentsTraversePost(traverseRequest, xRequestId)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DocumentsApi#v1DocumentsTraversePost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DocumentsApi#v1DocumentsTraversePost")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **traverseRequest** | [**TraverseRequest**](TraverseRequest.md)| Document id, relationship, depth, and collection. | |
| **xRequestId** | **kotlin.String**| Caller-provided correlation id; generated when omitted. | [optional] |

### Return type

[**TraverseResponse**](TraverseResponse.md)

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

 - **Content-Type**: application/json
 - **Accept**: application/json

<a id="v1DocumentsUpsertPost"></a>
# **v1DocumentsUpsertPost**
> UpsertResult v1DocumentsUpsertPost(upsertDocumentRequest, xRequestId, ifMatch, idempotencyKey)

Create or update a document

Idempotent on content hash. A supplied contentHash is a precondition on the existing document content. Honours &#x60;If-Match&#x60; (optimistic concurrency) and &#x60;Idempotency-Key&#x60; (response replay).

### Example
```kotlin
// Import classes:
//import org.openapitools.client.infrastructure.*
//import org.openapitools.client.models.*

val apiInstance = DocumentsApi()
val upsertDocumentRequest : UpsertDocumentRequest =  // UpsertDocumentRequest | Complete create-or-update request.
val xRequestId : kotlin.String = xRequestId_example // kotlin.String | Caller-provided correlation id; generated when omitted.
val ifMatch : kotlin.String = ifMatch_example // kotlin.String | Current revision ETag required for this write.
val idempotencyKey : kotlin.String = idempotencyKey_example // kotlin.String | Request-bound key used to reserve and replay a write.
try {
    val result : UpsertResult = apiInstance.v1DocumentsUpsertPost(upsertDocumentRequest, xRequestId, ifMatch, idempotencyKey)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DocumentsApi#v1DocumentsUpsertPost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DocumentsApi#v1DocumentsUpsertPost")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **upsertDocumentRequest** | [**UpsertDocumentRequest**](UpsertDocumentRequest.md)| Complete create-or-update request. | |
| **xRequestId** | **kotlin.String**| Caller-provided correlation id; generated when omitted. | [optional] |
| **ifMatch** | **kotlin.String**| Current revision ETag required for this write. | [optional] |
| **idempotencyKey** | **kotlin.String**| Request-bound key used to reserve and replay a write. | [optional] |

### Return type

[**UpsertResult**](UpsertResult.md)

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

 - **Content-Type**: application/json
 - **Accept**: application/json
