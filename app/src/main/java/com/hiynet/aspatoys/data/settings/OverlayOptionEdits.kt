package com.hiynet.aspatoys.data.settings

/** The APK queues edits, rather than ownership of the entire game settings snapshot. */
internal object OverlayOptionEdits {
    // Drafts queued by older APKs could only edit size and groups.
    val legacyFields = setOf("width", "height", "groups")

    fun changed(draft: OverlayOptions, baseline: OverlayOptions): Set<String> = buildSet {
        if (draft.width != baseline.width) add("width")
        if (draft.height != baseline.height) add("height")
        if (draft.fontScale != baseline.fontScale) add("font")
        if (draft.idleSeconds != baseline.idleSeconds) add("idle")
        if (draft.idleOpacity != baseline.idleOpacity) add("idleOpacity")
        if (draft.groups != baseline.groups) add("groups")
    }

    fun merge(current: OverlayOptions, draft: OverlayOptions, fields: Set<String>): OverlayOptions = current.copy(
        width = if ("width" in fields) draft.width else current.width,
        height = if ("height" in fields) draft.height else current.height,
        fontScale = if ("font" in fields) draft.fontScale else current.fontScale,
        idleSeconds = if ("idle" in fields) draft.idleSeconds else current.idleSeconds,
        idleOpacity = if ("idleOpacity" in fields) draft.idleOpacity else current.idleOpacity,
        groups = if ("groups" in fields) draft.groups else current.groups,
        // Opacity, bar position and remembered speed are always owned by the game.
    ).normalized()
}
