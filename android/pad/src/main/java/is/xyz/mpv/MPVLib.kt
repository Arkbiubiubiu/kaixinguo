package `is`.xyz.mpv

import android.content.Context
import android.view.Surface
import java.util.concurrent.CopyOnWriteArrayList

/**
 * libmpv 的 JNI 绑定。
 *
 * 签名按 libplayer.so（mpv-android）的 dex 逐一核对：
 * - 回调 `event`/`eventProperty`/`logMessage` 是 **静态** 方法（native 用 GetStaticMethodID 找）；
 * - `create`/`init`/`command`/`setOptionString`… 是 native 方法，按名字+签名注册。
 * 名字或参数差一个字母，native 侧就会抛 NoSuchMethodError 并 SIGABRT。
 */
class MPVLib {
    interface EventObserver {
        fun event(eventId: Int)
        fun eventProperty(name: String)
    }

    interface LogObserver {
        fun log(prefix: String, level: String, text: String)
    }

    companion object {
        private val observers = CopyOnWriteArrayList<EventObserver>()
        private val logObservers = CopyOnWriteArrayList<LogObserver>()

        init {
            System.loadLibrary("player")
        }

        @JvmStatic
        fun addObserver(o: EventObserver) { if (!observers.contains(o)) observers.add(o) }

        @JvmStatic
        fun removeObserver(o: EventObserver) { observers.remove(o) }

        @JvmStatic
        fun addLogObserver(o: LogObserver) { if (!logObservers.contains(o)) logObservers.add(o) }

        @JvmStatic
        fun removeLogObserver(o: LogObserver) { logObservers.remove(o) }

        // ---- native 回调（必须是静态方法）----
        @JvmStatic
        fun event(eventId: Int) {
            observers.forEach { runCatching { it.event(eventId) } }
        }

        // dex 里确认有 5 个重载，按属性类型分别回调
        @JvmStatic
        fun eventProperty(name: String) {
            observers.forEach { runCatching { it.eventProperty(name) } }
        }

        @JvmStatic
        fun eventProperty(name: String, value: Boolean) {
            observers.forEach { runCatching { it.eventProperty(name) } }
        }

        @JvmStatic
        fun eventProperty(name: String, value: Long) {
            observers.forEach { runCatching { it.eventProperty(name) } }
        }

        @JvmStatic
        fun eventProperty(name: String, value: Double) {
            observers.forEach { runCatching { it.eventProperty(name) } }
        }

        @JvmStatic
        fun eventProperty(name: String, value: String) {
            observers.forEach { runCatching { it.eventProperty(name) } }
        }

        @JvmStatic
        fun logMessage(prefix: String, level: Int, text: String) {
            logObservers.forEach { runCatching { it.log(prefix, level.toString(), text) } }
        }

        // ---- native 方法 ----
        @JvmStatic external fun create(context: Context)
        @JvmStatic external fun init()
        @JvmStatic external fun destroy()
        @JvmStatic external fun attachSurface(surface: Surface)
        @JvmStatic external fun detachSurface()
        @JvmStatic external fun command(args: Array<String>)
        @JvmStatic external fun setOptionString(name: String, value: String): Int
        @JvmStatic external fun getPropertyString(name: String): String?
        @JvmStatic external fun getPropertyInt(name: String): Int?
        @JvmStatic external fun getPropertyDouble(name: String): Double?
        @JvmStatic external fun getPropertyBoolean(name: String): Boolean?
        @JvmStatic external fun setPropertyString(name: String, value: String)
        @JvmStatic external fun setPropertyInt(name: String, value: Int)
        @JvmStatic external fun setPropertyDouble(name: String, value: Double)
        @JvmStatic external fun setPropertyBoolean(name: String, value: Boolean)
        @JvmStatic external fun observeProperty(name: String, format: Int)
        @JvmStatic external fun grabThumbnail(width: Int): android.graphics.Bitmap?
    }
}
