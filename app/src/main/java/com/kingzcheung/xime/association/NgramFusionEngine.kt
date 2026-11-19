package com.kingzcheung.xime.association

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NgramFusionEngine(private val context: Context, private val userNgramCache: UserNgramCache = UserNgramCache(context)) {
    companion object {
        private const val TAG = "NgramFusionEngine"
        private const val DEFAULT_LAMBDA = 0.7f
        /** Mixture weights over probabilities: bundled n-gram table, personal data. */
        const val BASE_WEIGHT = 0.7f
        const val USER_WEIGHT = 0.15f
        /** Typed observations reach full weight after this many events on the same context. */
        const val USER_CONFIDENCE_EVENTS = 8f
    }
    
    private var lambda = DEFAULT_LAMBDA
    private var isInitialized = false
    private var baseModel: BaseAssociationModel? = null
    /** 整词后面常接什么（从内置词库生成）；安装包里没有这个文件时为 null，联想照旧只用联想表。 */
    private var wordTable: WordContinuationTable? = null
    
    suspend fun initialize(): Boolean {
        if (isInitialized) return true
        
        val result = userNgramCache.initialize()
        baseModel = withContext(Dispatchers.IO) {
            // Without the marks the table still works; after a whole word it then behaves as before.
            val wordCompletions = try {
                openTextAsset(BaseAssociationModel.WORD_COMPLETIONS_ASSET).use(BaseAssociationModel::readWordCompletions)
            } catch (error: Exception) {
                Log.e(TAG, "Association word completion marks failed to load", error)
                emptyMap()
            }
            try {
                context.assets.open(BaseAssociationModel.ASSET).use { input ->
                    java.util.zip.GZIPInputStream(input).reader(Charsets.UTF_8).use {
                        BaseAssociationModel.readWithWordCompletions(it, wordCompletions)
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Bundled association model failed to load", error)
                null
            }
        }
        wordTable = withContext(Dispatchers.IO) {
            try {
                openTextAsset(WordContinuationTable.ASSET).use(WordContinuationTable::read)
            } catch (error: Exception) {
                Log.e(TAG, "Word continuation table failed to load", error)
                null
            }
        }
        isInitialized = result || baseModel != null
        return isInitialized
    }
    
    /**
     * 打包时 .gz 资源会被解压、去掉 .gz 后缀放进安装包（AssetManager 只认去掉后缀的名字），
     * 所以先按去掉后缀的名字读纯文本，找不到再按原名读 gzip。
     */
    private fun openTextAsset(name: String): java.io.Reader {
        val plain = name.removeSuffix(".gz")
        if (plain != name) {
            try {
                return context.assets.open(plain).reader(Charsets.UTF_8)
            } catch (_: java.io.FileNotFoundException) {
            }
        }
        return java.util.zip.GZIPInputStream(context.assets.open(name)).reader(Charsets.UTF_8)
    }

    fun recordUserInput(text: String) {
        userNgramCache.recordInput(text)
    }
    
    /** [afterWholeWord]: see [BaseAssociationModel.predict]; only the corpus table depends on it. */
    fun fuseCandidates(
        context: String,
        afterWholeWord: Boolean = false,
    ): List<AssociationCandidate> {
        if (!isInitialized) return emptyList()
        
        // One scale: every source contributes a probability of the continuation given the
        // context, mixed with fixed weights. The corpus table leads; personal observations only
        // add a bounded share, so a rare long phrase cannot outrank 个/是/些 after 这 on its own.
        val allCandidates = mutableMapOf<String, Float>()
        baseModel?.predict(context, BaseAssociationModel.MAX_PER_CONTEXT, afterWholeWord)?.forEach {
            allCandidates[it.text] = BASE_WEIGHT * it.score
        }
        // The corpus table knows the last one or two characters only. When the context ends with a longer
        // dictionary word (厄尔尼诺), what follows that whole word (现象) joins on the same scale.
        mergeWordContinuations(allCandidates, baseModel?.contextLength(context) ?: 0, wordTable?.lookup(context), BASE_WEIGHT)
        // Imported personal corpora carry their own counts (0.4·share); typed observations are
        // shares over few events, so they earn weight gradually (n/USER_CONFIDENCE_EVENTS).
        userNgramCache.profileCandidates(context).forEach { candidate ->
            allCandidates[candidate.text] = (allCandidates[candidate.text] ?: 0f) + USER_WEIGHT * candidate.score / 0.4f
        }
        val userCandidates = userNgramCache.getUserCandidates(context, 10)
        val confidence = minOf(1f, userNgramCache.observationCount(context) / USER_CONFIDENCE_EVENTS)
        userCandidates.forEach { (word, userScore) ->
            allCandidates[word] = (allCandidates[word] ?: 0f) + USER_WEIGHT * confidence * userScore
        }

        return allCandidates.map { (word, score) ->
            AssociationCandidate(word, score)
        }.sortedByDescending { it.score }
    }
    
    suspend fun saveCache() {
        userNgramCache.save()
    }
    
    fun clearCache() {
        userNgramCache.clear()
    }
    
    fun getCacheSize(): Int = userNgramCache.getCacheSize()
    fun learningData(): PersonalLearningData = userNgramCache.snapshot()
    suspend fun importLearningData(data: PersonalLearningData, merge: Boolean = false) = userNgramCache.importData(data, merge)
    
    fun isInitialized(): Boolean = isInitialized
}
