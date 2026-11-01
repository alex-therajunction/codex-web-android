package io.github.alextherajunction.codexweb

internal const val NEW_CHAT_COMPOSER_DRAFT_KEY = "__new_chat__"

internal fun composerDraftKey(threadId: String?): String =
    threadId ?: NEW_CHAT_COMPOSER_DRAFT_KEY

internal fun AppUiState.withComposerDraft(value: String): AppUiState {
    val key = composerDraftKey
    val drafts = if (value.isEmpty()) {
        composerDrafts - key
    } else {
        composerDrafts + (key to value)
    }
    return copy(composerText = value, composerDrafts = drafts)
}

internal fun AppUiState.withThreadComposerDraft(threadId: String?): AppUiState {
    val key = composerDraftKey(threadId)
    return copy(
        composerText = composerDrafts[key].orEmpty(),
        composerDraftKey = key,
    )
}

internal fun AppUiState.withAdoptedSelectedThreadDraft(): AppUiState {
    if (openingThreadId != null) return this
    val selectedKey = composerDraftKey(snapshot.selectedThreadId)
    if (selectedKey == composerDraftKey) return this
    val currentDraft = composerDrafts[composerDraftKey].orEmpty()
    if (composerText.isNotEmpty() || currentDraft.isNotEmpty()) return this
    return withThreadComposerDraft(snapshot.selectedThreadId)
}

internal fun AppUiState.afterComposerSend(
    originKey: String,
    submittedText: String,
): AppUiState {
    val activeKey = composerDraftKey(snapshot.selectedThreadId)
    val originDraft = composerDrafts[originKey]
    val originHoldsSubmittedText = originDraft == null || originDraft == submittedText
    val isCurrentOrigin = composerDraftKey == originKey

    if (isCurrentOrigin && composerText == submittedText && originHoldsSubmittedText) {
        return copy(
            composerText = "",
            composerDrafts = composerDrafts - originKey,
            composerDraftKey = activeKey,
        )
    }

    var drafts = composerDrafts
    if (originHoldsSubmittedText) drafts = drafts - originKey
    if (isCurrentOrigin && activeKey != originKey) {
        val pendingDraft = drafts[originKey].orEmpty()
        drafts = drafts - originKey
        if (pendingDraft.isNotEmpty()) drafts = drafts + (activeKey to pendingDraft)
        return copy(composerDrafts = drafts, composerDraftKey = activeKey)
    }
    return copy(composerDrafts = drafts)
}
