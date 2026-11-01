package com.shilapi.xcertplay

/** Let iOS lay out a side dock instead of crowding climate and app controls into one portrait row.
 * This is a session override only: ordinary CarPlay and the user's saved dock preference survive. */
internal object UltraPortraitLayout {
    fun dock(requested: CarPlayDock, climate: Boolean, width: Int, height: Int): CarPlayDock =
        if (climate && height > width && width > 0) CarPlayDock.DRIVER_SIDE else requested
}
