package com.encore.app

internal fun kindLabel(k: String?): String = when (k) {
    "image" -> "Photo"
    "video" -> "Video"
    "voice" -> "Voice message"
    "file" -> "File"
    else -> "Message"
}

internal fun clock(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

internal fun seenLabel(ms: Long): String =
    if (System.currentTimeMillis() - ms < 24L * 60 * 60 * 1000) clock(ms)
    else java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault()).format(java.util.Date(ms))

internal fun dayLabel(ms: Long): String {
    val cal = java.util.Calendar.getInstance()
    fun key(t: Long): Int {
        cal.timeInMillis = t
        return cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
    }
    val d = key(ms)
    val now = System.currentTimeMillis()
    return when (d) {
        key(now) -> "Today"
        key(now - 24L * 60 * 60 * 1000) -> "Yesterday"
        else -> java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(ms))
    }
}

internal fun addItem(list: List<MsgItem>, x: MsgItem): List<MsgItem> =
    (list.filter { it.msg.id != x.msg.id } + x).sortedBy { it.msg.id }

internal fun setReaction(
    map: Map<String, List<Pair<String, String>>>,
    mid: String,
    uid: String,
    emoji: String?
): Map<String, List<Pair<String, String>>> {
    val rest = (map[mid] ?: emptyList()).filter { it.first != uid }
    val next = if (emoji == null) rest else rest + (uid to emoji)
    return if (next.isEmpty()) map - mid else map + (mid to next)
}

internal fun buildRows(list: List<MsgItem>): List<Any> {
    val out = ArrayList<Any>()
    var lastDay = ""
    for (x in list) {
        val d = dayLabel(x.msg.createdAt)
        if (d != lastDay) {
            out.add(d)
            lastDay = d
        }
        out.add(x)
    }
    return out
}
