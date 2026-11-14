@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.kingzcheung.xime.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.kingzcheung.xime.clipboard.db.ClipboardDatabase
import com.kingzcheung.xime.clipboard.db.ClipboardEntry
import com.kingzcheung.xime.clipboard.db.ClipboardImportRow
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import android.content.ClipboardManager as AndroidClipboardManager
import com.kingzcheung.xime.platform.AndroidPlatform

data class ClipboardItem(
    val id: Long = 0,
    val text: String,
    /** 固定内容的可选触发编码（如 dh），空 = 仅内容命中不参与编码匹配。 */
    val code: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    val isQuickSend: Boolean = false,
    val consumed: Boolean = false,
    /** A password kept by 「记录密码等敏感内容」: shown as dots, pasted privately (#95). */
    val sensitive: Boolean = false,
)

class ClipboardManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ClipboardManager"
        /** The board reads unfixed history in pages of this many records. */
        internal const val HISTORY_PAGE = 300

        private const val PREFS_NAME = "clipboard_prefs"
        private const val KEY_CLIPBOARD_ITEMS = "clipboard_items"
        private const val KEY_QUICK_SEND_ITEMS = "quick_send_items"
        private const val KEY_HISTORY_DEDUPED = "history_deduped_v1"
        /** Records put into a group before 11-02 were fixed with it; set once they have been unfixed. */
        private const val KEY_GROUPS_UNPINNED = "groups_unpinned_v1"
        private const val INTERNAL_IMAGE_CLIP_LABEL = "cyime_image"
        /** Label of text written by clipboard sync, so history files it as received from another device. */
        internal const val RECEIVED_CLIP_LABEL = "cyime_received"
        /** 短信验证码只用一次：自动复制 10 分钟后从系统剪贴板删掉。 */
        internal const val SMS_CODE_LIFETIME_MS = 10 * 60_000L
        /** 候选栏只在短信到达后的 3 分钟内提示验证码；之后仍可从系统剪贴板粘贴，直到 10 分钟清掉。 */
        internal const val SMS_CODE_SUGGESTION_MS = 3 * 60_000L
        /** 别的设备发来的验证码：剪贴板描述里记着几点清掉（System.currentTimeMillis），见 VerificationCodeRelay。 */
        internal const val CODE_EXPIRES_EXTRA = "cyime.code_expires_at"

        /** 验证码几点从剪贴板清掉：本机短信 10 分钟；别的设备发来的按它带的时间，最多也是 10 分钟。 */
        internal fun smsCodeExpiresAt(timestamp: Long, expiresAt: Long): Long =
            if (expiresAt > 0) minOf(expiresAt, timestamp + SMS_CODE_LIFETIME_MS) else timestamp + SMS_CODE_LIFETIME_MS

        /** 开着无痕时第一次复制的提示（剪贴板面板顶上另有一行同样意思的说明）。 */
        internal const val INCOGNITO_COPY_NOTICE =
            "无痕模式开着：复制的内容不记进键盘剪贴板，粘贴照常能用。在键盘菜单点「无痕」可以关掉"

        /** The candidate-bar pill shows a code only shortly after the SMS arrived. */
        internal fun smsCodeSuggested(timestamp: Long, now: Long): Boolean =
            now - timestamp < SMS_CODE_SUGGESTION_MS

        /**
         * 弹出键盘时只补读没读过的、还会显示出来的那一条：图片、链接等非纯文字、短信验证码，或 30 秒内复制的文字。
         * [missed]：键盘一直在监听，复制时却没读到（有的系统在短信弹窗等界面盖住键盘时不给输入法读），
         * 这一条不管多久前复制的都补读一次。
         */
        internal fun shouldReadOnOpen(timestamp: Long, lastRead: Long, now: Long, smsCode: Boolean, plainText: Boolean,
            missed: Boolean = false): Boolean =
            timestamp != lastRead && (smsCode || missed || !plainText || now - timestamp < RECENT_CLIP_ON_OPEN_MS)

        /**
         * 一条复制里记进历史的文字：按顺序找第一项不空的文字、链接、intent，都没有再让系统转成文字。
         * 以前只看第一项、文字不为 null 就用：有的短信应用点链接、电话「复制」时第一项文字是空串，
         * 真正的内容在链接或后面的项里，读到空的就当没读到（用户 11-03）。
         */
        internal fun clipText(parts: List<ClipPart>): String? {
            for (part in parts) {
                part.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
                part.uri?.takeIf { it.isNotBlank() }?.let { return it }
                part.intentUri?.takeIf { it.isNotBlank() }?.let { return it }
            }
            return parts.firstNotNullOfOrNull { part -> part.coerce()?.toString()?.takeIf { it.isNotBlank() } }
        }

        internal const val RECENT_CLIP_ON_OPEN_MS = 30_000L
        /** 弹出键盘时同一条复制最多补读几次：系统一直不给读时不每次弹出都去读。 */
        internal const val OPEN_READ_TRIES = 2

        /** 打开「记录密码等敏感内容」后记下的密码，复制 1 小时后自动删除（固定的留着，#95）。 */
        internal const val SENSITIVE_LIFETIME_MS = 60 * 60_000L

        /**
         * What one copy may do in history. A copy the system marks sensitive (password managers) or made
         * while a password field was open is [ClipPrivacy.PRIVATE] (never kept), or [ClipPrivacy.SECRET]
         * (kept hidden) when the user turned on 「记录密码等敏感内容」. Incognito and clipboard search
         * always keep a copy out, switch or not.
         */
        internal fun clipPrivacy(markedSensitive: Boolean, passwordField: Boolean, incognito: Boolean,
            keepSensitive: Boolean): ClipPrivacy = when {
            incognito -> ClipPrivacy.PRIVATE
            markedSensitive || passwordField -> if (keepSensitive) ClipPrivacy.SECRET else ClipPrivacy.PRIVATE
            else -> ClipPrivacy.NORMAL
        }

        /** The code already sits right before the cursor: typed, pasted or filled in some other way. */
        internal fun smsCodeEntered(code: String, textBeforeCursor: CharSequence?): Boolean =
            code.isNotEmpty() && textBeforeCursor != null && textBeforeCursor.endsWith(code)

        @Volatile
        private var instance: ClipboardManager? = null

        fun getInstance(context: Context): ClipboardManager {
            return instance ?: synchronized(this) {
                instance ?: ClipboardManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val androidClipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as AndroidClipboardManager
    private var lastSmsClipTimestamp = -1L
    /** Clips first read while private; reading them again later (another field, incognito off) never saves them. */
    private val privateClips = PrivateClipMemory()
    /** Clips first read as passwords kept hidden; read again from an ordinary field they stay hidden. */
    private val secretClips = PrivateClipMemory()
    val images = ClipboardImages.getInstance(context)

    private val clipboardListener = AndroidClipboardManager.OnPrimaryClipChangedListener {
        readClipboard()
    }

    /** 这次开着无痕后已经提示过「复制的内容没记进来」；无痕开关一变就重新算。 */
    @Volatile private var incognitoCopyNoticed = false
    private val incognitoSwitchListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == com.kingzcheung.xime.settings.SettingsPreferences.KEY_INCOGNITO) {
            incognitoCopyNoticed = false
            if (listeningSince != Long.MAX_VALUE) listeningSince = System.currentTimeMillis()
        }
    }

    init {
        com.kingzcheung.xime.settings.SettingsPreferences.getPrefsPublic(context)
            .registerOnSharedPreferenceChangeListener(incognitoSwitchListener)
    }

    /**
     * 好多人开了无痕以为复制坏了（用户 10-31）：无痕只是不记进键盘剪贴板、不同步，系统复制粘贴照常。
     * 开着无痕后第一次复制时提示一次，不每次都弹。
     */
    private fun noticeIncognitoCopy() {
        if (incognitoCopyNoticed) return
        incognitoCopyNoticed = true
        Handler(Looper.getMainLooper()).post {
            runCatching {
                android.widget.Toast.makeText(context, INCOGNITO_COPY_NOTICE, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * [live]: read the moment the copy was made (the listener and its retries). Only then can the open
     * password field be where it was copied; a read when the keyboard opens or the board opens
     * catches a copy made elsewhere earlier, and the field opened now says nothing about it.
     */
    private fun readClipboard(retries: Int = 3, live: Boolean = true) {
        try {
            val clipData = androidClipboardManager.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val item = clipData.getItemAt(0)
                // 系统标记为敏感（如密码管理器复制的密码）或无痕中：不进入历史、图片预览与同步。
                val clipKey = PrivateClipMemory.key(clipData.description.timestamp,
                    item.text ?: item.uri?.toString() ?: item.intent?.toUri(0))
                val sensitive = clipData.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
                val manualIncognito = com.kingzcheung.xime.settings.SettingsPreferences.isIncognito(context)
                val privacy = clipPrivacy(sensitive, live && com.kingzcheung.xime.settings.IncognitoState.passwordField,
                    manualIncognito || com.kingzcheung.xime.settings.IncognitoState.manual ||
                        com.kingzcheung.xime.settings.IncognitoState.clipboardSearch,
                    com.kingzcheung.xime.settings.SettingsPreferences.isClipboardSensitiveCaptureEnabled(context))
                val privateNow = privacy == ClipPrivacy.PRIVATE
                // 手动无痕挡下的一次新复制（不是密码、不是本来就私密的那条）：第一次提示为什么没记进来。
                val incognitoCopy = manualIncognito && !sensitive && clipKey !in privateClips &&
                    clipData.description.label != INTERNAL_IMAGE_CLIP_LABEL &&
                    clipData.description.label != VerificationCodeSmsReceiver.CLIP_LABEL &&
                    clipData.description.label != RECEIVED_CLIP_LABEL
                // A clip copied in a password field (or incognito) stays private: re-reading it within
                // the next 30 s from a normal field once saved it to history.
                if (privateNow) privateClips.remember(clipKey)
                if (privacy == ClipPrivacy.SECRET) secretClips.remember(clipKey)
                if (incognitoCopy) noticeIncognitoCopy()
                val private = privateNow || clipKey in privateClips
                val secret = !private && clipKey in secretClips
                val imageMime = (0 until clipData.description.mimeTypeCount).map { clipData.description.getMimeType(it) }
                    .firstOrNull { it.startsWith("image/") }
                val imageSource = copiedImageSource((0 until clipData.itemCount).mapNotNull { index ->
                    val entry = clipData.getItemAt(index)
                    (entry.uri ?: entry.intent?.data)?.takeIf { it.scheme == "content" }?.let { uri ->
                        CopiedImageSource(uri.toString(), runCatching { context.contentResolver.getType(uri) }.getOrNull())
                    }
                }, imageMime)
                if (imageSource != null) {
                    lastReadClipTimestamp = clipData.description.timestamp
                    // 图片粘贴失败时写入系统剪贴板是兼容回退，不是用户新复制。
                    // Pictures copied as passwords are never kept, switch or not.
                    if (private || secret || clipData.description.label == INTERNAL_IMAGE_CLIP_LABEL) {
                        images.dismissPreview()
                        return
                    }
                    images.capture(Uri.parse(imageSource.uri), clipData.description.timestamp,
                        imageSource.resolvedMime?.takeIf { it.startsWith("image/") } ?: imageMime ?: "image/png")
                    return
                }
                images.dismissPreview()
                val text = clipText((0 until clipData.itemCount).map { index ->
                    val entry = clipData.getItemAt(index)
                    ClipPart(entry.text, entry.uri?.toString(), entry.intent?.toUri(0)) { entry.coerceToText(context) }
                })
                // 只记这条复制长什么样（类型、标签、各项有什么），不记内容：排查哪种复制没进剪贴板。
                Log.i(TAG, "clip read: mimes=" + (0 until clipData.description.mimeTypeCount).map { clipData.description.getMimeType(it) } +
                    " label=" + clipData.description.label + " items=" + (0 until clipData.itemCount).map { index ->
                        clipData.getItemAt(index).let { "t${it.text?.length ?: -1}u${if (it.uri != null) 1 else 0}i${if (it.intent != null) 1 else 0}" }
                    } + " sensitive=" + sensitive + " privacy=" + privacy + " empty=" + text.isNullOrEmpty())
                if (!text.isNullOrEmpty()) {
                    // 读到内容才算读过：系统不给读时拿到的是空的，弹出键盘时还要补读。
                    lastReadClipTimestamp = clipData.description.timestamp
                    if (clipData.description.label == VerificationCodeSmsReceiver.CLIP_LABEL && VerificationCodeExtractor.isCode(text)) {
                        // Automatic SMS codes stay in memory and never enter history or sync.
                        val timestamp = clipData.description.timestamp
                        val expiresAt = smsCodeExpiresAt(timestamp,
                            clipData.description.extras?.getLong(CODE_EXPIRES_EXTRA, 0L) ?: 0L)
                        val remaining = expiresAt - System.currentTimeMillis()
                        if (remaining <= 0) {
                            // 键盘进程中途被回收时定时器没跑，下次读到就补删。
                            expireSmsCode(timestamp)
                        } else if (lastSmsClipTimestamp != timestamp) {
                            lastSmsClipTimestamp = timestamp
                            val handler = Handler(Looper.getMainLooper())
                            val now = System.currentTimeMillis()
                            if (smsCodeSuggested(timestamp, now)) {
                                _verificationCode.value = ClipboardItem(text = text, timestamp = timestamp)
                                handler.postDelayed({ dismissVerificationCode(timestamp) },
                                    minOf(timestamp + SMS_CODE_SUGGESTION_MS, expiresAt) - now)
                            }
                            handler.postDelayed({ expireSmsCode(timestamp) }, remaining)
                        }
                        return
                    }
                    _verificationCode.value = null
                    // 通讯录插件开着时，复制的正好是联系人名字或号码：不记进键盘剪贴板。
                    if (!private && !com.kingzcheung.xime.contacts.ContactSuggestions.isContactText(text))
                        addItem(text, fromDevice = clipData.description.label == RECEIVED_CLIP_LABEL, sensitive = secret)
                    return
                }
            }
            if (retries > 0) {
                Handler(Looper.getMainLooper()).postDelayed({ readClipboard(retries - 1, live) }, 100L)
            } else {
                Log.w(TAG, "Failed to read clipboard after all retries")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot read clipboard: missing permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error reading clipboard", e)
        }
    }

    /**
     * Stops suggesting the SMS code on the candidate bar (dismissed, entered, or shown long enough).
     * The system clipboard keeps it until [expireSmsCode], so a manual paste still works.
     */
    fun dismissVerificationCode(timestamp: Long? = null) {
        val code = _verificationCode.value ?: return
        if (timestamp == null || code.timestamp == timestamp) _verificationCode.value = null
    }

    /** Drops an expired SMS code; clears the system clipboard only while it still holds that same code. */
    private fun expireSmsCode(timestamp: Long) {
        if (_verificationCode.value?.timestamp == timestamp) _verificationCode.value = null
        runCatching {
            val description = androidClipboardManager.primaryClipDescription
            if (description?.label == VerificationCodeSmsReceiver.CLIP_LABEL && description.timestamp == timestamp) {
                androidClipboardManager.clearPrimaryClip()
            }
        }.onFailure { Log.w(TAG, "Could not clear expired SMS code", it) }
    }

    private val database = ClipboardDatabase.getInstance(context)
    private val dao = database.clipboardDao()
    private val scope = ClipboardDatabase.scope()
    private val clipboardPinsReady = CompletableDeferred<Unit>()
    /** Grouped records are marked and the history limits are known; trimming history waits for it. */
    private val limitsReady = CompletableDeferred<Unit>()
    private val clipboardPinWriter = ClipboardPinWriter(scope, clipboardPinsReady, dao::setClipboardPinned,
        onFailure = { Log.e(TAG, "Could not save clipboard pin state", it) })

    private val _clipboardItems = MutableStateFlow<List<ClipboardItem>>(emptyList())
    val clipboardItems: StateFlow<List<ClipboardItem>> = _clipboardItems.asStateFlow()
    private val _verificationCode = MutableStateFlow<ClipboardItem?>(null)
    val verificationCode: StateFlow<ClipboardItem?> = _verificationCode.asStateFlow()

    /** 监听或弹出键盘时已经读过的那一条（系统给每次复制记的时间），同一条不再读第二次。 */
    @Volatile private var lastReadClipTimestamp = -1L
    /** 从这时起的复制都该由监听读到；没读到的就是漏了（无痕开关一变就从那时重新算，开着无痕时的复制不补）。 */
    @Volatile private var listeningSince = Long.MAX_VALUE
    /** 弹出键盘时为哪一条复制补读过、补读了几次。 */
    private var openTriedClip = -1L
    private var openTries = 0

    /**
     * 弹出键盘时补看一眼，只为补上键盘进程不在时的复制。先只看描述（复制时间、类型、标签），
     * 看描述不算读剪贴板；监听已经读过的同一条、30 秒前复制的普通文字（本来也不进候选栏）都不读内容。
     * 以前每次弹出键盘都整条读一次，系统一直提示读了剪贴板（用户 11-01：「哪怕我没用剪贴板」）。
     */
    fun refreshClipboard() {
        // 最近截图不在这里查：换输入框、应用重启输入框都会走到这，以前每次都查一遍相册；改为键盘弹出时查一次（setKeyboardVisible）。
        deleteExpiredSensitive()
        // 「保留时间」 runs out while nothing is copied too; checked whenever the keyboard opens.
        if (SettingsPreferences.getClipboardKeepDays(context) != ClipboardRetention.NEVER) scope.launch { trimHistory() }
        readMissedCopy()
    }

    /**
     * 只看描述，补读监听没读到的那一条。短信里点链接、电话弹出的「复制」菜单盖住了键盘，
     * 有的系统这时不给输入法读剪贴板，监听读到的是空的，长按复制时键盘还在就读得到（用户 11-02 转述）。
     * 键盘弹出（含输入框没变、只是键盘重新显示）时再读一次。
     */
    fun readMissedCopy(boardOpened: Boolean = false) {
        val description = runCatching { androidClipboardManager.primaryClipDescription }.getOrNull() ?: return
        val timestamp = description.timestamp
        if (timestamp != openTriedClip) { openTriedClip = timestamp; openTries = 0 }
        // 打开剪贴板面板就是要看刚复制的：不受弹出键盘的补读次数限制，读过的照样不再读。
        if (openTries >= OPEN_READ_TRIES && !boardOpened) return
        val plainText = (0 until description.mimeTypeCount).all {
            description.getMimeType(it) == ClipDescription.MIMETYPE_TEXT_PLAIN || description.getMimeType(it) == ClipDescription.MIMETYPE_TEXT_HTML
        }
        if (shouldReadOnOpen(timestamp, lastReadClipTimestamp, System.currentTimeMillis(),
                smsCode = description.label == VerificationCodeSmsReceiver.CLIP_LABEL, plainText = plainText,
                missed = timestamp >= listeningSince)
        ) {
            openTries++
            readClipboard(retries = 0, live = false)
        }
    }

    private val _quickSendItems = MutableStateFlow<List<ClipboardItem>>(emptyList())
    /** Existing plugin protocol name; the source is now the unified set of fixed text records. */
    val quickSendItems: StateFlow<List<ClipboardItem>> = _quickSendItems.asStateFlow()

    private val historyWindow = MutableStateFlow(HISTORY_PAGE)
    private val _room = MutableStateFlow(ClipboardRoom())
    /** Stored size against what this device can hold; full means new copies are not saved. */
    internal val room: StateFlow<ClipboardRoom> = _room.asStateFlow()

    private val _unpinnedCount = MutableStateFlow(0)
    /** All unfixed text records in the database, including pages not read yet. */
    val unpinnedCount: StateFlow<Int> = _unpinnedCount.asStateFlow()

    /** Read the next page of older history; called when the board scrolls near its end. */
    fun loadMoreHistory() {
        val loaded = historyWindow.value
        if (loaded < _unpinnedCount.value) historyWindow.value = loaded + HISTORY_PAGE
    }

    /** Back to the first page when the board closes, so memory stays small. */
    fun resetHistoryWindow() { historyWindow.value = HISTORY_PAGE }

    /** A search submitted from the keyboard search bar; the board shows only its matches until cleared. */
    val boardQuery = MutableStateFlow("")

    /** Fixed records and history matching a text fragment or pinyin initials (searched in the database). */
    suspend fun search(query: String): List<ClipboardItem> {
        if (query.isBlank()) return emptyList()
        clipboardPinsReady.await()
        return dao.search(ClipboardSearch.likePattern(query), ClipboardSearch.RESULT_LIMIT).map { it.toClipboardItem() }
    }

    /**
     * The keyboard search field while it is being typed. Without a composition this is [search];
     * with one, the committed text and the composition are matched together as pinyin initials
     * (on the 9-key keypad, as the digits of those initials).
     */
    internal suspend fun search(query: ClipboardLiveQuery): List<ClipboardItem> {
        if (query.isBlank) return emptyList()
        if (query.composing.isBlank()) return search(query.text)
        val typed = searchKey(query.text).takeIf { it != ClipboardSearch.EMPTY_KEY }.orEmpty().replace(" ", "")
        val key = typed + ClipboardSearch.composingInitials(query.composing, AndroidPinyinInitials::initialOf)
        if (key.isEmpty()) return search(query.text)
        clipboardPinsReady.await()
        val found = if (query.t9) dao.searchKeyDigits(ClipboardSearch.keyPattern(ClipboardSearch.t9Digits(key)), ClipboardSearch.RESULT_LIMIT)
            else dao.searchKey(ClipboardSearch.keyPattern(key), ClipboardSearch.RESULT_LIMIT)
        return found.map { it.toClipboardItem() }
    }

    /** Every text record (fixed and history) for a user-started export; read once from the database. */
    suspend fun exportItems(): List<ClipboardItem> {
        clipboardPinsReady.await()
        // Passwords kept by 「记录密码等敏感内容」 never leave the phone in a file.
        return dao.observeAll().first().filterNot { it.sensitive }.map { it.toClipboardItem() }
    }

    /**
     * Records read from an import file the user chose. Fixed records go in first, then history in
     * file order, so a full clipboard leaves out the oldest history. Records keep their own time;
     * text already here is not added twice. A record in a group goes into it (not fixed); a group
     * missing here is made. Nothing is sent to clipboard sync: this is not a copy.
     */
    internal suspend fun importItems(clips: List<ImportedClip>): ClipboardImportResult {
        clipboardPinsReady.await()
        val capacity = ClipboardCapacity.capacity(context.filesDir.usableSpace)
        var stored = dao.observeStoredBytes().first()
        val now = System.currentTimeMillis()
        val joins = linkedMapOf<String, MutableList<String>>()
        var added = 0; var pinned = 0; var existing = 0; var notSaved = 0
        val ordered = clips.withIndex().sortedByDescending { it.value.pinned || it.value.group != null }
        for ((index, clip) in ordered) {
            if (!ClipboardCapacity.fitsRow(clip.text)) { notSaved++; continue }
            val fixed = clip.pinned
            val key = searchKey(clip.text)
            val bytes = ClipboardCapacity.recordBytes(clip.text, key)
            val row = dao.importRecord(clip.text, clip.code, clip.timestamp.takeIf { it > 0 } ?: (now - index),
                fixed, key, ClipboardCapacity.accepts(stored, bytes, capacity))
            when (row.outcome) {
                ClipboardImportRow.ADDED -> { added++; if (fixed) pinned++; stored += bytes }
                ClipboardImportRow.EXISTING -> existing++
                else -> { notSaved++; continue }
            }
            clip.group?.let { joins.getOrPut(it) { mutableListOf() }.add("text:${row.id}") }
        }
        val groups = ClipboardGroupStore.getInstance(context)
        for ((name, keys) in joins) {
            val id = groups.idForName(name) ?: continue
            // A record already in a group stays where the user put it.
            val members = groups.state.value.members
            val joining = keys.filter { it !in members }
            groups.assign(joining, id)
            // Marked at once, so the history limits below leave them alone.
            joining.mapNotNull { it.removePrefix("text:").toLongOrNull() }.takeIf { it.isNotEmpty() }
                ?.chunked(500)?.forEach { dao.setGrouped(it, true) }
        }
        return ClipboardImportResult(added, pinned, existing, notSaved, trimmed = trimHistory())
    }

    /** 「数量上限」 and 「保留时间」 as saved; the first time, a limit that keeps all history already here. */
    private suspend fun readHistoryLimits(): ClipboardHistoryLimits {
        val count = SettingsPreferences.getClipboardMaxCount(context)
            ?: ClipboardRetention.initialCount(dao.countUnpinned()).also { SettingsPreferences.setClipboardMaxCount(context, it) }
        return ClipboardHistoryLimits(count, SettingsPreferences.getClipboardKeepDays(context))
    }

    /** The history limits for the settings page. */
    internal suspend fun historyLimits(): ClipboardHistoryLimits {
        limitsReady.await()
        return readHistoryLimits()
    }

    /** History records [limits] would delete right now (fixed and grouped ones never count). */
    internal suspend fun removedBy(limits: ClipboardHistoryLimits): Int {
        limitsReady.await()
        return ClipboardRetention.removedBy(dao.countUnpinned(),
            dao.countHistoryBefore(limits.cutoff(System.currentTimeMillis())), limits.maxCount)
    }

    /** The user's choice on the settings page, applied at once. */
    internal fun setHistoryLimits(limits: ClipboardHistoryLimits) {
        SettingsPreferences.setClipboardMaxCount(context, limits.maxCount)
        SettingsPreferences.setClipboardKeepDays(context, limits.keepDays)
        scope.launch { trimHistory() }
    }

    /** History beyond 「数量上限」 or older than 「保留时间」 goes, oldest first. Returns how many went. */
    private suspend fun trimHistory(): Int {
        limitsReady.await()
        return trimHistoryNow()
    }

    private suspend fun trimHistoryNow(): Int = runCatching {
        val limits = readHistoryLimits()
        dao.trimHistory(limits.maxCount, limits.cutoff(System.currentTimeMillis()))
    }.onFailure { Log.e(TAG, "Could not trim clipboard history", it) }.getOrDefault(0)

    private fun searchKey(text: String) = ClipboardSearch.key(text, AndroidPinyinInitials::initialOf)

    /**
     * Room for [adding] more bytes: the oldest history makes way (user 11-12: 「不限」 yet 「剪贴板已满」).
     * False (and the board says full) when fixed and grouped records alone leave no room; a copy too
     * large to push out history is left out quietly when it does not fit.
     */
    private suspend fun makeRoom(adding: Long, keep: Long = -1): Boolean {
        val capacity = ClipboardCapacity.capacity(context.filesDir.usableSpace)
        val made = runCatching { dao.makeRoom(adding, capacity, keep) }
            .onFailure { Log.e(TAG, "Could not make room in the clipboard", it) }.getOrDefault(false)
        if (made) _room.value = _room.value.copy(refused = false)
        else if (ClipboardCapacity.pushesOut(adding, capacity)) _room.value = _room.value.copy(refused = true)
        return made
    }

    private suspend fun hasRoomFor(text: String, key: String): Boolean = makeRoom(ClipboardCapacity.recordBytes(text, key))

    private val _recentItems = MutableStateFlow<List<ClipboardItem>>(emptyList())
    val recentItems: StateFlow<List<ClipboardItem>> = _recentItems.asStateFlow()

    /** 本地剪贴板变更事件流（新增/更新条目时发射，供剪贴板同步等外部消费）。 */
    private val _clipboardChanged = MutableSharedFlow<ClipboardItem>(extraBufferCapacity = 16)
    val clipboardChanged: SharedFlow<ClipboardItem> = _clipboardChanged.asSharedFlow()

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        val boardPrefs = context.getSharedPreferences("clipboard_board", Context.MODE_PRIVATE)
        val savedPins = boardPrefs.getStringSet("pins", emptySet()).orEmpty().toSet()
        val legacyMigration = migrateLegacyData()
        scope.launch {
            legacyMigration.join()
            try {
                // Earlier boards kept text pins separately from the Room retention flag.
                // Import those positive marks once, before exposing the text list to the UI.
                check(!prefs.contains(KEY_CLIPBOARD_ITEMS) && !prefs.contains(KEY_QUICK_SEND_ITEMS))
                // Room upgrades existing v3 databases; this also covers old records imported later.
                dao.migrateQuickSendToPinned()
                migrateClipboardTextPins(savedPins, { dao.setClipboardPinned(it, true) }) {
                    synchronized(boardPrefs) {
                        val currentPins = boardPrefs.getStringSet("pins", emptySet()).orEmpty()
                        check(boardPrefs.edit().putStringSet("pins", currentPins.filterNot { it.startsWith("text:") }.toSet()).commit())
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Could not migrate clipboard pin state; keeping the old markers for retry", error)
            } finally {
                clipboardPinsReady.complete(Unit)
            }
            // Before anything clears or expires history: records in a group are marked as kept.
            val groups = ClipboardGroupStore.getInstance(context)
            syncGroupedRecords(groups.state.value, boardPrefs)
            scope.launch {
                // Sorting or hiding tabs leaves membership intact, so the history needs no database write.
                groups.state.distinctUntilChangedBy { it.members }.collect { syncGroupedRecords(it, boardPrefs) }
            }
            // Duplicates saved before copies were merged (user 10-30): removed once; new copies merge as they come.
            if (!prefs.getBoolean(KEY_HISTORY_DEDUPED, false)) {
                runCatching { dao.deleteDuplicateHistory() }
                    .onSuccess { prefs.edit().putBoolean(KEY_HISTORY_DEDUPED, true).apply() }
                    .onFailure { Log.e(TAG, "Could not remove duplicate clipboard records", it) }
            }
            deleteExpiredSensitive()
            trimHistoryNow()
            limitsReady.complete(Unit)
            scope.launch { dao.observeUnpinnedCount().collect { _unpinnedCount.value = it } }
            scope.launch {
                dao.observeStoredBytes().collect { stored ->
                    val previous = _room.value
                    // Removing records makes room again.
                    _room.value = previous.copy(storedBytes = stored,
                        capacityBytes = ClipboardCapacity.capacity(context.filesDir.usableSpace),
                        refused = previous.refused && stored >= previous.storedBytes)
                }
            }
            scope.launch { backfillSearchKeys() }
            historyWindow.flatMapLatest { dao.observeWindow(it) }.collect { entries ->
                _clipboardItems.value = entries.map { it.toClipboardItem() }
                updateRecentItems()
            }
        }
        scope.launch {
            clipboardPinsReady.await()
            dao.observeQuickSend().collect { entries ->
                _quickSendItems.value = entries.map { it.toClipboardItem() }
            }
        }
        startListening()
    }

    /**
     * Records in a group are marked in the database (the groups themselves are board preferences) so
     * clearing history, the received-copies room and the password expiry leave them alone and the board
     * always loads them, without fixing them. A member whose record is gone leaves its group. Records
     * fixed only because they went into a group (before 11-02) are unfixed once, text and images.
     */
    private suspend fun syncGroupedRecords(state: ClipboardGroupState, boardPrefs: android.content.SharedPreferences) {
        val ids = state.members.keys.mapNotNull { key -> key.removePrefix("text:").takeIf { it != key }?.toLongOrNull() }
        try {
            dao.syncGrouped(ids)
            if (!prefs.getBoolean(KEY_GROUPS_UNPINNED, false)) {
                dao.unpinGrouped()
                val images = state.members.keys.filterTo(mutableSetOf()) { it.startsWith("image:") }
                if (images.isNotEmpty()) synchronized(boardPrefs) {
                    val pins = boardPrefs.getStringSet("pins", emptySet()).orEmpty()
                    check(boardPrefs.edit().putStringSet("pins", pins - images).commit())
                }
                prefs.edit().putBoolean(KEY_GROUPS_UNPINNED, true).apply()
            }
            val present = ids.chunked(500).flatMap { dao.existingIds(it) }.toSet()
            val gone = ids.filterNot { it in present }
            if (gone.isNotEmpty()) ClipboardGroupStore.getInstance(context).assign(gone.map { "text:$it" }, null)
        } catch (error: Exception) {
            Log.e(TAG, "Could not mark grouped clipboard records", error)
        }
    }

    /**
     * 将旧版 SharedPreferences 中的剪贴板/快捷发送数据一次性迁移到 Room。
     * 幂等：prefs 无数据时直接返回；迁移成功后删除 prefs 键，避免重复迁移。
     */
    fun migrateLegacyData(): Job = scope.launch {
        val legacyClipboard = prefs.getString(KEY_CLIPBOARD_ITEMS, null)
        val legacyQuickSend = prefs.getString(KEY_QUICK_SEND_ITEMS, null)
        if (legacyClipboard == null && legacyQuickSend == null) return@launch
        try {
            val entries = mutableListOf<ClipboardEntry>()
            legacyClipboard?.let { str ->
                deserializeItems(str).forEach { item ->
                    entries.add(item.toEntry())
                }
            }
            legacyQuickSend?.let { str ->
                deserializeItems(str).forEach { item ->
                    entries.add(item.toEntry(forcePinned = true))
                }
            }
            if (entries.isNotEmpty()) dao.insertAll(entries)
            prefs.edit()
                .remove(KEY_CLIPBOARD_ITEMS)
                .remove(KEY_QUICK_SEND_ITEMS)
                .apply()
            Log.i(TAG, "Migrated ${entries.size} legacy clipboard items to Room")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to migrate legacy clipboard items", e)
        }
    }

    /** Records saved before search existed get their keys in small batches, off the main thread. */
    private suspend fun backfillSearchKeys() {
        while (true) {
            val batch = runCatching { dao.missingSearchKeys(100) }.getOrDefault(emptyList())
            if (batch.isEmpty()) return
            batch.forEach { dao.setSearchKey(it.id, searchKey(it.text)) }
        }
    }

    private fun updateRecentItems() {
        val now = System.currentTimeMillis()
        val cutoff = now - 10 * 1000L
        _recentItems.value = _clipboardItems.value.filter { it.timestamp >= cutoff }
    }

    private fun deserializeItems(json: String): List<ClipboardItem> {
        if (json.isEmpty()) return emptyList()
        return json.split("|||").mapNotNull { itemStr ->
            val parts = itemStr.split(":::")
            if (parts.size == 5) {
                try {
                    ClipboardItem(
                        id = parts[0].toLong(),
                        text = parts[1].unescape(),
                        timestamp = parts[2].toLong(),
                        isPinned = parts[3].toBoolean(),
                        isQuickSend = parts[4].toBoolean()
                    )
                } catch (e: Exception) {
                    null
                }
            } else if (parts.size == 4) {
                try {
                    ClipboardItem(
                        id = parts[0].toLong(),
                        text = parts[1].unescape(),
                        timestamp = parts[2].toLong(),
                        isPinned = parts[3].toBoolean(),
                        isQuickSend = false
                    )
                } catch (e: Exception) {
                    null
                }
            } else null
        }
    }

    private fun String.unescape(): String {
        return this.replace("〈PIPE〉", "|||").replace("〈COLON〉", ":::")
    }

    private fun ClipboardItem.toEntry(forcePinned: Boolean = false): ClipboardEntry {
        return ClipboardEntry(
            id = 0,
            text = text,
            code = code,
            timestamp = timestamp,
            isPinned = forcePinned || isPinned || isQuickSend,
            isQuickSend = false,
            consumed = consumed
        )
    }

    private fun ClipboardEntry.toClipboardItem(): ClipboardItem {
        return ClipboardItem(
            id = id,
            text = text,
            code = code,
            timestamp = timestamp,
            isPinned = isPinned,
            isQuickSend = isQuickSend,
            consumed = consumed,
            sensitive = sensitive,
        )
    }

    private fun startListening() {
        androidClipboardManager.addPrimaryClipChangedListener(clipboardListener)
        if (listeningSince == Long.MAX_VALUE) listeningSince = System.currentTimeMillis()
    }

    fun release() {
        // Singleton — no cleanup needed.
    }

    fun addItem(text: String, fromDevice: Boolean = false, sensitive: Boolean = false) {
        if (text.isBlank()) return
        scope.launch {
            clipboardPinsReady.await()
            if (!ClipboardCapacity.fitsRow(text)) return@launch
            // A password gets no search key: it is never searched.
            val key = if (sensitive) "" else searchKey(text)
            val now = System.currentTimeMillis()
            val added = if (fromDevice) {
                // Received copies keep their own room and never push out or block this phone's own.
                dao.addReceived(text, now, key, ClipboardCapacity.DEVICE_COUNT, ClipboardCapacity.DEVICE_BYTES)
            } else {
                // A copy already in history only moves up and needs no room; a new one may push out the oldest history.
                // A received record copied again here becomes this phone's own and needs room like a new one.
                val same = dao.findSameText(text)
                val needsRoom = same == null || (same.fromDevice && !same.isPinned)
                if (needsRoom && !hasRoomFor(text, key)) return@launch
                dao.upsert(text, now, key, sensitive = sensitive)
            }
            // A new record: the oldest history beyond 「数量上限」 makes way (fixed and grouped ones stay).
            if (added) trimHistory()
            if (sensitive) {
                // Deleted an hour after the copy; checked again whenever the keyboard opens, in case
                // the keyboard process was gone when the hour ran out.
                Handler(Looper.getMainLooper()).postDelayed({ deleteExpiredSensitive() }, SENSITIVE_LIFETIME_MS + 1_000L)
                return@launch
            }
            _clipboardChanged.emit(
                ClipboardItem(
                    text = text,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    /** Passwords kept longer than [SENSITIVE_LIFETIME_MS]; fixed ones stay. */
    private fun deleteExpiredSensitive() {
        scope.launch {
            runCatching { dao.deleteSensitiveBefore(System.currentTimeMillis() - SENSITIVE_LIFETIME_MS) }
                .onFailure { Log.e(TAG, "Could not delete expired passwords", it) }
        }
    }

    /** 长按「不是密码」：这条按普通记录显示、能搜，不再 1 小时后删除（用户 11-09：普通网页文字被当成密码）。 */
    fun markNotSensitive(id: Long, text: String) {
        scope.launch {
            runCatching {
                dao.setSensitive(id, false)
                dao.setSearchKey(id, searchKey(text))
            }.onFailure { Log.e(TAG, "Could not unmark a kept password", it) }
        }
    }

    fun removeItem(id: Long) {
        scope.launch {
            dao.deleteClipboardById(id)
        }
    }

    /** Explicit selection may delete fixed records too; it never affects unselected records. */
    fun removeItems(ids: List<Long>) {
        if (ids.isEmpty()) return
        scope.launch {
            dao.deleteClipboardByIds(ids)
        }
    }

    /** Clear ordinary history while preserving every fixed record and its trigger code. */
    fun clearClipboard() {
        scope.launch {
            clipboardPinsReady.await()
            dao.clearAllClipboard()
        }
    }

    fun splitItem(id: Long) {
        scope.launch {
            val item = _clipboardItems.value.find { it.id == id } ?: return@launch
            dao.deleteClipboardById(id)
            val now = System.currentTimeMillis()
            item.text.forEachIndexed { index, char ->
                dao.insert(
                    ClipboardEntry(
                        text = char.toString(),
                        timestamp = now + index
                    )
                )
            }
        }
    }

    fun clearAll() {
        scope.launch {
            clipboardPinsReady.await()
            dao.clearUnpinned()
        }
    }

    fun addToQuickSend(id: Long) {
        setClipboardPinned(id, true)
    }

    fun removeFromQuickSend(id: Long) {
        removeItem(id)
    }

    fun togglePinQuickSend(id: Long) {
        setClipboardPinned(id, true)
    }

    fun setClipboardPinned(id: Long, pinned: Boolean): Job = clipboardPinWriter.set(id, pinned)

    fun updateQuickSendItem(id: Long, newText: String, newCode: String = ""): Boolean {
        return updateClipboardItem(id, newText, newCode)
    }

    /** Edit in place: preserve pin state and retain the code when it was not edited. */
    fun updateClipboardItem(id: Long, newText: String, newCode: String? = null): Boolean {
        if (newText.isBlank() || !ClipboardCapacity.fitsRow(newText)) return false
        scope.launch {
            clipboardPinsReady.await()
            // A search result may lie outside the loaded page; the database decides whether it exists.
            val old = dao.findById(id) ?: return@launch
            val key = searchKey(newText)
            val growth = ClipboardCapacity.recordBytes(newText, key) - ClipboardCapacity.recordBytes(old.text, old.searchKey)
            if (growth > 0 && !makeRoom(growth, keep = id)) return@launch
            dao.updateClipboardItem(id, newText, newCode?.trim(), System.currentTimeMillis())
            dao.setSearchKey(id, key)
            // Edited into text another history record already holds: keep only the edited one.
            dao.deleteHistoryCopies(newText, id)
        }
        return true
    }

    fun addQuickSendItem(text: String, code: String = "") {
        addPinnedText(text, code)
    }

    /**
     * [groupId]: added from that group's tab, the record goes into the group and is not fixed (a group
     * keeps its records without moving them to the top); otherwise it is a fixed record.
     */
    fun addPinnedText(text: String, code: String = "", groupId: String? = null) {
        if (text.isBlank() || !ClipboardCapacity.fitsRow(text)) return
        scope.launch {
            clipboardPinsReady.await()
            val key = searchKey(text)
            // Text already saved is fixed (or grouped) in place and needs no room.
            if (dao.findSameText(text) == null && !hasRoomFor(text, key)) return@launch
            val now = System.currentTimeMillis()
            if (groupId == null) dao.addPinned(text, code.trim(), now, key)
            else ClipboardGroupStore.getInstance(context).assign(setOf("text:${dao.addToGroup(text, code.trim(), now, key)}"), groupId)
        }
    }

    fun copyToSystemClipboard(text: String) {
        val clip = ClipData.newPlainText("kime_clipboard", text)
        androidClipboardManager.setPrimaryClip(clip)
    }

    /** Text arriving from a paired device: the same as a copy, but filed as received in history. */
    fun copyReceivedToSystemClipboard(text: String) {
        androidClipboardManager.setPrimaryClip(ClipData.newPlainText(RECEIVED_CLIP_LABEL, text))
    }

    /**
     * 已配对设备发来的验证码（「验证码发到其他设备」）：和本机短信验证码一样标成验证码、系统敏感内容，
     * 所以不进历史、不再同步转发、候选栏提示；到 [ttlMs] 后还没换掉就清掉。
     */
    fun copyReceivedCodeToSystemClipboard(code: String, ttlMs: Long) {
        val clip = ClipData.newPlainText(VerificationCodeSmsReceiver.CLIP_LABEL, code)
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
            putLong(CODE_EXPIRES_EXTRA, System.currentTimeMillis() + ttlMs)
        }
        androidClipboardManager.setPrimaryClip(clip)
        // 键盘没在监听（不是当前输入法）时也要按时清掉。
        val timestamp = runCatching { androidClipboardManager.primaryClipDescription?.timestamp }.getOrNull() ?: return
        Handler(Looper.getMainLooper()).postDelayed({ expireSmsCode(timestamp) }, ttlMs)
    }

    fun copyImageUriToSystemClipboard(image: ClipboardImage) {
        androidClipboardManager.setPrimaryClip(ClipData(INTERNAL_IMAGE_CLIP_LABEL, arrayOf(image.mimeType), ClipData.Item(Uri.parse(image.uri))))
    }

    fun getCurrentClipboardText(): String? {
        val clipData = androidClipboardManager.primaryClip
        return if (clipData != null && clipData.itemCount > 0) {
            clipData.getItemAt(0).text?.toString()
        } else null
    }

    fun getRecentItems(seconds: Int = 30): List<ClipboardItem> {
        val now = System.currentTimeMillis()
        val cutoff = now - seconds * 1000L
        // 候选栏只展示未消费的最近剪贴板项（用户点选上屏后标记 consumed 不再显示）
        val sms = _verificationCode.value?.takeIf { smsCodeSuggested(it.timestamp, now) }
        return listOfNotNull(sms) + _clipboardItems.value.filter { it.timestamp >= cutoff && !it.consumed && it.text != sms?.text }
    }

    /**
     * 标记指定文本的剪贴板条目为"已消费"（候选栏不再显示）。
     * 匹配最近一条未消费的相同文本，避免影响历史重复条目。
     */
    fun markConsumed(text: String) {
        if (_verificationCode.value?.text == text) _verificationCode.value = null
        scope.launch {
            val item = _clipboardItems.value
                .filter { it.text == text && !it.consumed }
                .maxByOrNull { it.timestamp } ?: return@launch
            dao.markConsumed(item.id)
        }
    }

    fun copyImageToSystemClipboard(imagePath: String, label: String = "emoji_image"): Boolean {
        return try {
            val imageFile = File(imagePath)
            if (!imageFile.exists()) {
                Log.e(TAG, "Image file not found: $imagePath")
                return false
            }

            val cacheDir = File(context.cacheDir, "emoji_cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            val cacheFile = File(cacheDir, imageFile.name)
            FileInputStream(imageFile).use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val uri = getContentUriForImage(cacheFile) ?: return false

            val clip = ClipData.newUri(context.contentResolver, label, uri)
            androidClipboardManager.setPrimaryClip(clip)

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy image to clipboard", e)
            false
        }
    }

    /**
     * 生成图片 content URI。
     *
     * 优先使用 FileProvider；Android 12+ 部分厂商 ROM 上
     * FileProvider.getUriForFile 内部 resolveContentProvider 以 USER_ALL(-10000)
     * 校验跨用户权限时抛 "Invalid userId -10000"，此时降级为 MediaStore
     * 插入图片获取系统 content URI（API 29+ 免权限）。
     */
    private fun getContentUriForImage(imageFile: File): Uri? {
        try {
            return FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                imageFile
            )
        } catch (e: Exception) {
            Log.w(TAG, "FileProvider getUriForFile failed, falling back to MediaStore", e)
        }
        return insertImageToMediaStore(imageFile)
    }

    /** 把图片插入 MediaStore（Pictures/CyIME），返回系统 content URI。 */
    private fun insertImageToMediaStore(imageFile: File): Uri? {
        if (!AndroidPlatform.mediaStoreDownloads) {
            Log.e(TAG, "MediaStore fallback requires API 29+, clipboard image copy failed")
            return null
        }
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, imageFile.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CyIME")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(imageFile).use { input -> input.copyTo(output) }
                } ?: return null
                val update = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, update, null, null)
                uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore insert failed", e)
            null
        }
    }
}

/**
 * Clips read while the keyboard was private (password field, incognito, a clip marked sensitive).
 * Keyed by the clip's own timestamp and content, so the same clip read again later is recognised
 * and a new copy of the same text (a new timestamp) is not.
 */
internal class PrivateClipMemory(private val capacity: Int = 16) {
    private val keys = ArrayDeque<String>()

    @Synchronized fun remember(key: String) {
        if (key in keys) return
        keys.addLast(key)
        while (keys.size > capacity) keys.removeFirst()
    }

    @Synchronized operator fun contains(key: String): Boolean = key in keys

    companion object {
        fun key(timestamp: Long, content: CharSequence?): String = "$timestamp:${content?.toString()?.hashCode() ?: 0}"
    }
}

/** What one copy may do in clipboard history; see [ClipboardManager.clipPrivacy]. */
internal enum class ClipPrivacy { NORMAL, SECRET, PRIVATE }

/** 一条复制里的一项：文字、链接、intent 各自原样，[coerce] 交给系统转成文字（只在前面都没有时才调）。 */
internal class ClipPart(val text: CharSequence?, val uri: String?, val intentUri: String?,
    val coerce: () -> CharSequence? = { null })
