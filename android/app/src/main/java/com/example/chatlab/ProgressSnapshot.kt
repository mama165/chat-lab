package com.example.chatlab

import org.json.JSONArray
import org.json.JSONObject

/**
 * The analysis as it is at [now], for a copy made during the run: counts, times and the state of each
 * folder. No photo address is written here.
 */
fun Progress.snapshotJson(now: Long): JSONObject {
    val left = remaining(now)
    return JSONObject()
        .put("at", now)
        .put("running", running)
        .put("paused", paused)
        .put("done", done)
        .put("total", total)
        .put("percent", (fraction * 100).toInt())
        .put("errors", errors)
        .put("levelLabel", levelLabel)
        .put("activeElapsedMs", activeElapsed(now))
        .put("remainingMs", left ?: JSONObject.NULL)
        .put("loadingModels", loadingModels)
        .put(
            "folders",
            JSONArray(folders.map { folder ->
                JSONObject()
                    .put("name", folder.name)
                    .put("done", folder.done)
                    .put("total", folder.total)
                    .put("later", folder.later)
            }),
        )
}
