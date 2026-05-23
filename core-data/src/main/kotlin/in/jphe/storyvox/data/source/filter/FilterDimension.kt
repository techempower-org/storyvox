package `in`.jphe.storyvox.data.source.filter

sealed interface FilterDimension {
    val key: String
    val label: String

    data class Sort(
        override val key: String = "sort",
        override val label: String = "Sort by",
        val options: List<SortOption>,
        val default: SortOption = options.first(),
    ) : FilterDimension

    data class SortOption(val id: String, val label: String)

    data class Select(
        override val key: String,
        override val label: String,
        val options: List<String>,
    ) : FilterDimension

    data class TagSet(
        override val key: String,
        override val label: String,
        val options: List<String>,
        val allowExclude: Boolean = false,
    ) : FilterDimension

    data class NumberRange(
        override val key: String,
        override val label: String,
        val min: Float,
        val max: Float,
        val step: Float = 1f,
        val formatLabel: String = "",
    ) : FilterDimension

    data class DateRange(
        override val key: String = "dateRange",
        override val label: String = "Date range",
        val presets: List<DatePreset> = DEFAULT_DATE_PRESETS,
    ) : FilterDimension

    data class DatePreset(val id: String, val label: String)

    data class Toggle(
        override val key: String,
        override val label: String,
        val default: Boolean = false,
    ) : FilterDimension

    companion object {
        val DEFAULT_DATE_PRESETS = listOf(
            DatePreset("any", "Any time"),
            DatePreset("7d", "Last 7 days"),
            DatePreset("30d", "Last 30 days"),
            DatePreset("90d", "Last 90 days"),
            DatePreset("1y", "Last year"),
        )
    }
}
