package com.example.conditions;

import java.util.Arrays;
import java.util.Locale;

final class PayloadTypes {
    static final int LIMIT = 10;
    static final String[] PICKER_TYPES = {"image/*", "audio/*", "text/plain", "text/markdown",
            "text/x-markdown", "text/csv", "application/pdf", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/rtf", "text/rtf", "application/vnd.oasis.opendocument.text",
            "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.spreadsheet", "application/vnd.oasis.opendocument.presentation",
            "application/octet-stream"};

    static boolean allowed(String name, String mime) {
        name = name.toLowerCase(Locale.ROOT);
        mime = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        String extension = name.substring(name.lastIndexOf('.') + 1);
        if (mime.startsWith("video/") || Arrays.asList("mp4", "mov", "mkv", "avi", "webm",
                "3gp", "m4v", "wmv", "mpeg", "mpg", "ts").contains(extension)) return false;
        if (mime.startsWith("image/") || mime.startsWith("audio/")) return true;
        if (!mime.isEmpty() && !mime.equals("application/octet-stream")
                && Arrays.asList(PICKER_TYPES).contains(mime)) return true;
        if (!mime.isEmpty() && !mime.equals("application/octet-stream")) return false;
        return Arrays.asList("txt", "md", "markdown", "word", "doc", "docx", "pdf", "rtf",
                "odt", "csv", "xls", "xlsx", "ods", "ppt", "pptx", "odp",
                "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp",
                "mp3", "wav", "m4a", "aac", "ogg", "flac", "opus").contains(extension);
    }
}
