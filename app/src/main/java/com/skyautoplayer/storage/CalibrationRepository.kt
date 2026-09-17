package com.skyautoplayer.storage

import android.content.Context
import android.graphics.Rect
import com.skyautoplayer.calibration.CalibrationProfile
import com.skyautoplayer.calibration.Insets
import com.skyautoplayer.calibration.NormalizedPoint
import org.json.JSONArray
import org.json.JSONObject

interface CalibrationStore {
    fun load(profileId: String): CalibrationProfile?
    fun save(profile: CalibrationProfile)

    /** Drops a profile - used by the W12 「重置校准」 action. */
    fun clear(profileId: String)
}

class InMemoryCalibrationStore : CalibrationStore {
    private val profiles = mutableMapOf<String, CalibrationProfile>()
    override fun load(profileId: String): CalibrationProfile? = profiles[profileId]
    override fun save(profile: CalibrationProfile) { profiles[profile.profileId] = profile }
    override fun clear(profileId: String) { profiles.remove(profileId) }
}

/** Device-local persistence for calibration profiles. */
class SharedPreferencesCalibrationStore(context: Context) : CalibrationStore {
    private val preferences = context.getSharedPreferences("calibration_profiles", Context.MODE_PRIVATE)

    override fun load(profileId: String): CalibrationProfile? {
        val raw = preferences.getString(profileId, null) ?: return null
        return runCatching { decode(profileId, JSONObject(raw)) }.getOrNull()
    }

    override fun save(profile: CalibrationProfile) {
        preferences.edit().putString(profile.profileId, encode(profile).toString()).apply()
    }

    override fun clear(profileId: String) {
        preferences.edit().remove(profileId).apply()
    }

    private fun encode(profile: CalibrationProfile) = JSONObject().apply {
        put("displayId", profile.displayId)
        put("orientation", profile.orientation)
        put("naturalOrientation", profile.naturalOrientation)
        put("displayWidthPx", profile.displayWidthPx)
        put("displayHeightPx", profile.displayHeightPx)
        put("contentBounds", rect(profile.contentBounds))
        put("safeInsets", insets(profile.safeInsets))
        put("systemBarInsets", insets(profile.systemBarInsets))
        put("cutoutInfo", profile.cutoutInfo)
        put("navigationMode", profile.navigationMode)
        put("densityDpi", profile.densityDpi)
        put("displayScale", profile.displayScale.toDouble())
        put("normalizedKeyPoints", JSONArray().apply { profile.normalizedKeyPoints.forEach { put(JSONObject().put("x", it.x.toDouble()).put("y", it.y.toDouble())) } })
        put("coordinateSpace", profile.coordinateSpace)
        put("gameLayoutVersion", profile.gameLayoutVersion)
        put("createdAt", profile.createdAt)
        put("updatedAt", profile.updatedAt)
        put("calibrationQuality", profile.calibrationQuality)
        put("schemaVersion", profile.schemaVersion)
    }

    private fun decode(profileId: String, json: JSONObject) = CalibrationProfile(
        profileId = profileId,
        displayId = json.getInt("displayId"), orientation = json.getInt("orientation"),
        naturalOrientation = json.getInt("naturalOrientation"), displayWidthPx = json.getInt("displayWidthPx"),
        displayHeightPx = json.getInt("displayHeightPx"), contentBounds = readRect(json.getJSONObject("contentBounds")),
        safeInsets = readInsets(json.getJSONObject("safeInsets")), systemBarInsets = readInsets(json.getJSONObject("systemBarInsets")),
        cutoutInfo = json.optString("cutoutInfo").takeUnless { it.isEmpty() }, navigationMode = json.optString("navigationMode", "unknown"),
        densityDpi = json.optInt("densityDpi"), displayScale = json.optDouble("displayScale", 1.0).toFloat(),
        normalizedKeyPoints = json.getJSONArray("normalizedKeyPoints").let { array -> List(array.length()) { i -> array.getJSONObject(i).let { NormalizedPoint(it.getDouble("x").toFloat(), it.getDouble("y").toFloat()) } } },
        coordinateSpace = json.optString("coordinateSpace", "content"), gameLayoutVersion = json.optString("gameLayoutVersion", "default"),
        createdAt = json.optLong("createdAt"), updatedAt = json.optLong("updatedAt"), calibrationQuality = json.optString("calibrationQuality", "manual"), schemaVersion = json.optInt("schemaVersion", 1)
    )

    private fun rect(r: Rect) = JSONObject().apply { put("left", r.left); put("top", r.top); put("right", r.right); put("bottom", r.bottom) }
    private fun insets(i: Insets) = JSONObject().apply { put("left", i.left); put("top", i.top); put("right", i.right); put("bottom", i.bottom) }
    private fun readRect(j: JSONObject) = Rect(j.getInt("left"), j.getInt("top"), j.getInt("right"), j.getInt("bottom"))
    private fun readInsets(j: JSONObject) = Insets(j.getInt("left"), j.getInt("top"), j.getInt("right"), j.getInt("bottom"))
}
