package com.kingzcheung.xime.service

import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.InputModes
import com.kingzcheung.xime.settings.LanguagePreferences
import com.kingzcheung.xime.settings.OrientationLayouts
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.isHandwritingSchema
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keep focus callbacks responsive and serialize dictionary work with subsequent typing. */
internal class EditorInputPreparation(private val service: XimeInputMethodService) {
    fun schedule(restarting: Boolean, numberEditor: Boolean) {
        if (!RimeEngine.isInitialized() || service.uiState.value.isDeploying) return
        service.keyRouter.postRimeJob {
            val owner = InputCommandOwner.requireOwner()
            val engine = service.rimeEngine
            if (engine.isMaintaining()) return@postRimeJob
            val available = engine.getAvailableSchemas().toList()
            val saved = SettingsPreferences.getCurrentSchema(service)
            // 横竖屏分开记布局：这个方向记的条目（横屏 26 键、竖屏九键），关着时就是 saved。
            val stored = if (service.editorInputPolicy.scoped) saved
                else OrientationLayouts.prepared(service, saved, InputModes.available(service.uiState.value.schemas))
            val selectable = if (stored.isNotBlank() && stored !in available && stored != InputModes.ENGLISH &&
                (isHandwritingSchema(stored) || SchemaManager.isSchemaCompiled(service, stored))) available + stored else available
            val policy = service.editorInputPolicy
            var target = policy.schemaOverride ?: LanguagePreferences.nativeSchema(stored, selectable, LanguagePreferences.enabled(service))
            if (policy.directInput && isHandwritingSchema(target)) {
                target = engine.getCurrentSchema().takeIf { it in available } ?: available.firstOrNull() ?: return@postRimeJob
            }
            var missingHandwriting = false
            if (isHandwritingSchema(target)) {
                com.kingzcheung.xime.model.ModelStorage.migrateLegacyForModel(service, "ochwpro")
                val directory = com.kingzcheung.xime.model.ModelStorage.getModelDir(service, "ochwpro")
                missingHandwriting = !java.io.File(directory, "ochwpro.onnx").isFile ||
                    !java.io.File(directory, "char_index.json").isFile
                if (missingHandwriting) target = available.firstOrNull() ?: return@postRimeJob
            }
            val handwriting = isHandwritingSchema(target)
            if (!handwriting && target !in available && !SchemaManager.isSchemaCompiled(service, target)) {
                target = available.firstOrNull() ?: return@postRimeJob
            }
            val current = engine.getCurrentSchema()
            val nativeTarget = if (handwriting) current else target
            if (nativeTarget.isEmpty()) return@postRimeJob
            service.schemaController.applyPageSizeSetting(nativeTarget)
            if (!prepareEditorEngine(current, nativeTarget, engine::switchSchema,
                    { engine.clearQueuedComposition(); engine.clearQueuedT9Composition() },
                    service.sessionController::restorePersistedSchemaOptions)) {
                FileLogger.w("InputLifecycle", "Editor preparation failed: schema unavailable")
                return@postRimeJob
            }
            val ascii = engine.isAsciiMode()
            withContext(Dispatchers.Main) {
                owner.requireCurrent(service.inputReadiness, service.uiState.value.inputSessionId)
                if (target != saved && !policy.scoped) SettingsPreferences.setCurrentSchema(service, target)
                // 换了方向的条目时，该语言的选中条目也跟着换，语言键切回来、菜单里打勾的都是它。
                if (stored != saved && target == stored && !policy.scoped) InputModes.selectProfile(service, target,
                    InputModes.languageOf(target, service.uiState.value.schemas))
                val refreshName = service.uiState.value.currentSchemaId != target || service.uiState.value.schemas.isEmpty()
                service.uiState.value = service.uiState.value.copy(currentSchemaId = target, isAsciiMode = ascii)
                if (policy.scoped && !handwriting) {
                    service.keyboardViewModel.discardTemporaryHandwriting()
                    service.keyboardViewModel.switchMain(com.kingzcheung.xime.keyboard.MainType.FULL)
                }
                service.keyboardViewModel.applyPreparedEditorKeyboard(ascii, target, handwriting,
                    restarting && !policy.scoped, policy.field.numberPanel ||
                        numberEditor && SettingsPreferences.isAutoNumberKeyboardEnabled(service))
                service.keyboardViewModel.synchronizeInputSession(service.uiState.value.inputSessionId,
                    service.uiState.value.t9ResetSignal, ascii, target)
                policy.completePreparation(ascii, target)
                if (refreshName) service.sessionController.updateSchemaName()
                else service.sessionController.refreshSchemaSwitches()
                if (missingHandwriting) android.widget.Toast.makeText(service, "请先下载手写模型", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
