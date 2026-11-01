package ai.closepaw.ui.session

import ai.closepaw.R

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Utility functions for time formatting in the session list UI.
 */
object TimeUtils {
    
    /**
     * Format timestamp as relative time.
     * 
     * - < 1 minute: strings.getString(R.string.ui_just_now)
     * - < 60 minutes: "X minutes ago"
     * - < 24 hours: "X hours ago"
     * - < 7 days: "X days ago"
     * - Same year: "MMM d" (e.g., "Jan 21")
     * - Different year: "MMM d, yyyy" (e.g., "Jan 21, 2023")
     * 
     * @param timestamp The epoch timestamp in milliseconds
     * @return A human-readable relative time string
     */
    fun formatRelativeTime(strings: android.content.res.Resources, timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        
        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> strings.getString(R.string.ui_just_now)
            diff < TimeUnit.HOURS.toMillis(1) -> {
                val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
                if (minutes == 1L) strings.getString(R.string.ui_1_minute_ago) else strings.getString(R.string.ui_minutes_ago, minutes)
            }
            diff < TimeUnit.DAYS.toMillis(1) -> {
                val hours = TimeUnit.MILLISECONDS.toHours(diff)
                if (hours == 1L) strings.getString(R.string.ui_1_hour_ago) else strings.getString(R.string.ui_hours_ago, hours)
            }
            diff < TimeUnit.DAYS.toMillis(7) -> {
                val days = TimeUnit.MILLISECONDS.toDays(diff)
                if (days == 1L) strings.getString(R.string.ui_yesterday) else strings.getString(R.string.ui_days_ago, days)
            }
            else -> {
                val dateTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(timestamp), 
                    ZoneId.systemDefault()
                )
                val currentYear = LocalDateTime.now().year
                
                val locale = strings.configuration.locales[0]
                val skeleton = if (dateTime.year == currentYear) "MMMd" else "yMMMd"
                val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
                dateTime.format(DateTimeFormatter.ofPattern(pattern, locale))
            }
        }
    }
    
    /**
     * Format message count for display.
     * 
     * @param count The number of messages
     * @return A formatted string like "3 messages" or strings.getString(R.string.ui_1_message)
     */
    fun formatMessageCount(strings: android.content.res.Resources, count: Int): String {
        return if (count == 1) strings.getString(R.string.ui_1_message) else strings.getString(R.string.ui_messages, count)
    }
}
