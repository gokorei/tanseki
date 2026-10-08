
# DocumentDto

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **id** | **kotlin.String** |  |  |
| **collection** | **kotlin.String** |  |  |
| **path** | **kotlin.String** |  |  |
| **content** | **kotlin.String** |  |  |
| **contentHash** | **kotlin.String** |  |  |
| **revision** | **kotlin.String** |  |  |
| **updatedAt** | **kotlin.String** |  |  |
| **deleted** | **kotlin.Boolean** |  |  |
| **frontmatter** | [**kotlin.collections.Map&lt;kotlin.String, kotlin.Any?&gt;**](kotlin.Any.md) | An open, string-keyed map of JSON values. A value may be a string, number, boolean, array, or object, and keeps that type across a write and a read: an array is stored as a YAML block sequence and returns as an array, not as its repr string, and a number keeps its exact digits (&#x60;9007199254740993&#x60; round-trips unchanged). A value sent as a quoted string stays a string and is never re-typed. Keys outside the contract set (&#x60;title&#x60;, &#x60;author&#x60;, &#x60;tags&#x60;, &#x60;updated_at&#x60;, &#x60;content_hash&#x60;) take any JSON value; those five take a scalar instead, &#x60;tags&#x60; takes an array of strings, and a shape they cannot hold is refused with the key and the shape named rather than dropped. A document keeps whatever shape it was stored as, so one written before this behaviour existed still reads back the text it was written with. &#x60;updated_at&#x60; is the one key whose text form is NOT preserved: it is read as an instant and re-rendered in a single canonical spelling, so &#x60;2026-10-03T12:00:00+00:00&#x60; is stored as &#x60;2026-10-03T12:00:00Z&#x60;. The instant survives; the spelling does not. Compare instants, not strings. |  |
