package com.linkn.screenintake.store

/** Refuse ambiguous edits instead of silently falling back to a stale list position. */
internal fun <T> selectUnchangedRecord(rows: List<T>, matches: (T) -> Boolean): T =
    rows.filter(matches).singleOrNull()
        ?: error("记录已变化或存在相同记录，请刷新后重新选择；没有修改任何记录")
