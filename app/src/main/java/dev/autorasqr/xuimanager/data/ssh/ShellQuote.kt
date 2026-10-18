package dev.autorasqr.xuimanager.data.ssh

/** POSIX single-quote escaping: the result is a single shell word whatever the input contains. */
fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
