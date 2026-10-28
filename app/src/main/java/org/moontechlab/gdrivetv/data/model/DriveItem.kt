package org.moontechlab.gdrivetv.data.model

private val DATE_REGEX = Regex("""(?:^|[^0-9])(19\d\d|20\d\d)[-_]?([01]\d)[-_]?([0-3]\d)(?:[^0-9]|$)""")
private val YEAR_REGEX = Regex("""(19\d\d|20\d\d)""")

data class DateInfo(val year: Int, val month: Int, val day: Int)

data class DriveItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long = 0,
    val modifiedTime: String? = null,
    val thumbnailLink: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val takenTime: String? = null
) {
    val isFolder: Boolean
        get() = mimeType == "application/vnd.google-apps.folder"

    val isImage: Boolean
        get() = mimeType.startsWith("image/") ||
                name.endsWith(".jpg", ignoreCase = true) ||
                name.endsWith(".jpeg", ignoreCase = true) ||
                name.endsWith(".png", ignoreCase = true) ||
                name.endsWith(".heic", ignoreCase = true) ||
                name.endsWith(".webp", ignoreCase = true) ||
                name.endsWith(".gif", ignoreCase = true)

    val isVideo: Boolean
        get() = mimeType.startsWith("video/") ||
                name.endsWith(".mov", ignoreCase = true) ||
                name.endsWith(".mp4", ignoreCase = true) ||
                name.endsWith(".m4v", ignoreCase = true) ||
                name.endsWith(".mkv", ignoreCase = true) ||
                name.endsWith(".webm", ignoreCase = true) ||
                name.endsWith(".avi", ignoreCase = true)

    val isMedia: Boolean
        get() = isImage || isVideo

    val dateInfo: DateInfo? by lazy {
        // 1. Check EXIF takenTime (e.g. "2026:04:19 14:40:11" or "2026-04-19...")
        if (!takenTime.isNullOrEmpty() && takenTime.length >= 10) {
            val y = takenTime.substring(0, 4).toIntOrNull() ?: 0
            val mo = takenTime.substring(5, 7).toIntOrNull() ?: 0
            val d = takenTime.substring(8, 10).toIntOrNull() ?: 0
            if (y in 1990..2040 && mo in 1..12 && d in 1..31) {
                return@lazy DateInfo(y, mo, d)
            }
        }
        // 2. Check filename for YYYYMMDD or YYYY-MM-DD or YYYY_MM_DD
        val m = DATE_REGEX.find(name)
        if (m != null) {
            val y = m.groupValues[1].toIntOrNull() ?: 0
            val mo = m.groupValues[2].toIntOrNull() ?: 0
            val d = m.groupValues[3].toIntOrNull() ?: 0
            if (y in 1990..2040 && mo in 1..12 && d in 1..31) {
                return@lazy DateInfo(y, mo, d)
            }
        }
        // 3. Fallback to modifiedTime
        val time = modifiedTime
        if (!time.isNullOrEmpty() && time.length >= 10) {
            val y = time.substring(0, 4).toIntOrNull() ?: 0
            val mo = time.substring(5, 7).toIntOrNull() ?: 0
            val d = time.substring(8, 10).toIntOrNull() ?: 0
            if (y in 1990..2040 && mo in 1..12 && d in 1..31) {
                return@lazy DateInfo(y, mo, d)
            }
        }
        null
    }

    val sortKey: String by lazy {
        val d = dateInfo
        if (d != null) {
            String.format("%04d%02d%02d_%s", d.year, d.month, d.day, name)
        } else {
            modifiedTime ?: name
        }
    }

    val folderYear: Int? by lazy {
        YEAR_REGEX.find(name)?.groupValues?.get(1)?.toIntOrNull()
    }
}

data class DriveFileList(
    val files: List<DriveItem>,
    val nextPageToken: String? = null
)
