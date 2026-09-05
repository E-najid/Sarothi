package com.ngi.sarothi.core.capability

/**
 * The only way core code raises user-visible notifications. Channels and icons
 * are the app module's business; core just states what happened.
 */
interface Notifier {
    fun progress(taskId: String, title: String, text: String, max: Int, current: Int)
    fun info(id: String, title: String, text: String)
    fun warning(id: String, title: String, text: String)
    fun error(id: String, title: String, text: String)
    fun cancel(id: String)
    fun cancelTask(taskId: String)
}
