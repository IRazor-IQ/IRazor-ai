package com.irazor.ai

object ScreenContextRepository {
    @Volatile
    private var _context: String = ""
    @Volatile
    private var _foregroundApp: String = ""
    @Volatile
    private var _timestamp: Long = 0L
    @Volatile
    private var _serviceEnabled: Boolean = false

    fun setContext(context: String, foregroundApp: String) {
        _context = context
        _foregroundApp = foregroundApp
        _timestamp = System.currentTimeMillis()
    }

    fun getContext(): String = _context
    fun getForegroundApp(): String = _foregroundApp
    fun getTimestamp(): Long = _timestamp
    fun isServiceEnabled(): Boolean = _serviceEnabled
    fun setServiceEnabled(enabled: Boolean) { _serviceEnabled = enabled }
}
