package `in`.jphe.storyvox.data.source.filter

data class FilterState(val values: Map<String, FilterValue> = emptyMap()) {
    fun isActive(): Boolean = values.isNotEmpty()
    fun activeCount(): Int = values.size

    operator fun get(key: String): FilterValue? = values[key]

    fun with(key: String, value: FilterValue): FilterState =
        copy(values = values + (key to value))

    fun without(key: String): FilterState =
        copy(values = values - key)

    fun stringVal(key: String): String? =
        (values[key] as? FilterValue.StringVal)?.value

    fun stringSetVal(key: String): FilterValue.StringSetVal? =
        values[key] as? FilterValue.StringSetVal

    fun rangeVal(key: String): FilterValue.RangeVal? =
        values[key] as? FilterValue.RangeVal

    fun boolVal(key: String): Boolean? =
        (values[key] as? FilterValue.BoolVal)?.value
}

sealed interface FilterValue {
    data class StringVal(val value: String) : FilterValue
    data class StringSetVal(
        val included: Set<String>,
        val excluded: Set<String> = emptySet(),
    ) : FilterValue
    data class RangeVal(val min: Float?, val max: Float?) : FilterValue
    data class BoolVal(val value: Boolean) : FilterValue
}
