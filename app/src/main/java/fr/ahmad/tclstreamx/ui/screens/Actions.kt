package fr.ahmad.tclstreamx.ui.screens

import android.content.Context
import android.widget.Toast
import fr.ahmad.tclstreamx.AppContainer
import fr.ahmad.tclstreamx.R
import fr.ahmad.tclstreamx.data.db.FavoriteEntity
import fr.ahmad.tclstreamx.data.db.LockedGroupEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Long press on a category: hide it behind the parental code (a code must exist first). */
fun lockCategory(context: Context, c: AppContainer, group: String) {
    if (group.isBlank()) return
    c.appScope.launch {
        val message = if (!c.settings.current().pinSet) {
            R.string.parental_need_pin
        } else {
            c.db.locks().lock(LockedGroupEntity(group, 1))
            R.string.parental_group_locked
        }
        withContext(Dispatchers.Main) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }
}

/** Long press on a film or series: add to or remove from the favorites. */
fun toggleFavorite(context: Context, c: AppContainer, key: String) {
    c.appScope.launch {
        val dao = c.db.favorites()
        val added = if (dao.isFavorite(key)) {
            dao.remove(key)
            false
        } else {
            dao.add(FavoriteEntity(key, System.currentTimeMillis()))
            true
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(context, if (added) R.string.live_add_favorite else R.string.live_remove_favorite, Toast.LENGTH_SHORT).show()
        }
    }
}

/** Long press on a "continue watching" title: forget its resume point. */
fun forgetResume(c: AppContainer, key: String) {
    c.appScope.launch { c.db.vodProgress().delete(key) }
}
