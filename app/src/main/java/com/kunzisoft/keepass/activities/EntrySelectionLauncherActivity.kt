/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.digicraft.keepass.R
import com.kunzisoft.keepass.activities.helpers.EntrySelectionHelper
import com.kunzisoft.keepass.activities.legacy.DatabaseModeActivity
import com.kunzisoft.keepass.database.element.Database
import com.kunzisoft.keepass.database.search.SearchHelper
import com.kunzisoft.keepass.magikeyboard.MagikeyboardService
import com.kunzisoft.keepass.model.EntryInfo
import com.kunzisoft.keepass.model.SearchInfo
import com.kunzisoft.keepass.otp.OtpEntryFields
import com.kunzisoft.keepass.settings.PreferencesUtil

/**
 * Activity to search or select entry in database,
 * Commonly used with Magikeyboard
 */
class EntrySelectionLauncherActivity : DatabaseModeActivity() {

    override fun applyCustomStyle(): Boolean {
        return false
    }

    override fun finishActivityIfReloadRequested(): Boolean {
        return true
    }

    override fun onDatabaseRetrieved(database: Database?) {
        super.onDatabaseRetrieved(database)
        var sharedWebDomain: String? = null
        var otpString: String? = null

        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if ("text/plain" == intent.type) {
                    // Retrieve web domain or OTP
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { extra ->
                        if (OtpEntryFields.isOTPUri(extra))
                            otpString = extra
                        else
                            sharedWebDomain = Uri.parse(extra).host
                    }
                }
            }
            Intent.ACTION_VIEW -> {
                // Retrieve OTP
                intent.dataString?.let { extra ->
                    if (OtpEntryFields.isOTPUri(extra))
                        otpString = extra
                }
            }
            else -> {}
        }

        // Build domain search param
        val searchInfo = SearchInfo().apply {
            this.webDomain = sharedWebDomain
            this.otpString = otpString
        }

        SearchInfo.getConcreteWebDomain(this, searchInfo.webDomain) { concreteWebDomain ->
            searchInfo.webDomain = concreteWebDomain
            launch(database, searchInfo)
        }
    }

    private fun launch(database: Database?,
                       searchInfo: SearchInfo) {

        if (!searchInfo.containsOnlyNullValues()) {
            // Setting to integrate Magikeyboard
            val searchShareForMagikeyboard = PreferencesUtil.isKeyboardSearchShareEnable(this)

            // If database is open
            val readOnly = database?.isReadOnly != false
            SearchHelper.checkAutoSearchInfo(this,
                    database,
                    searchInfo,
                    { openedDatabase, items ->
                        // Items found
                        if (searchInfo.otpString != null) {
                            if (!readOnly) {
                                GroupActivity.launchForSaveResult(
                                    this,
                                    openedDatabase,
                                    searchInfo,
                                    false)
                            } else {
                                Toast.makeText(applicationContext,
                                        R.string.autofill_read_only_save,
                                        Toast.LENGTH_LONG)
                                        .show()
                            }
                        } else if (searchShareForMagikeyboard) {
                            if (items.size == 1) {
                                // Automatically populate keyboard
                                val entryPopulate = items[0]
                                populateKeyboardAndMoveAppToBackground(
                                    this,
                                        entryPopulate,
                                        intent)
                            } else {
                                // Select the one we want
                                GroupActivity.launchForKeyboardSelectionResult(this,
                                    openedDatabase,
                                    searchInfo,
                                    true)
                            }
                        } else {
                            GroupActivity.launchForSearchResult(this,
                                openedDatabase,
                                searchInfo,
                                true)
                        }
                    },
                    { openedDatabase ->
                        // Show the database UI to select the entry
                        if (searchInfo.otpString != null) {
                            if (!readOnly) {
                                GroupActivity.launchForSaveResult(this,
                                    openedDatabase,
                                    searchInfo,
                                    false)
                            } else {
                                Toast.makeText(applicationContext,
                                        R.string.autofill_read_only_save,
                                        Toast.LENGTH_LONG)
                                        .show()
                            }
                        } else if (readOnly || searchShareForMagikeyboard) {
                            GroupActivity.launchForKeyboardSelectionResult(this,
                                openedDatabase,
                                searchInfo,
                                false)
                        } else {
                            GroupActivity.launchForSaveResult(this,
                                openedDatabase,
                                searchInfo,
                                false)
                        }
                    },
                    {
                        // If database not open
                        if (searchInfo.otpString != null) {
                            if (!readOnly) {
                                FileDatabaseSelectActivity.launchForSaveResult(this,
                                        searchInfo)
                            } else {
                                Toast.makeText(applicationContext,
                                        R.string.autofill_read_only_save,
                                        Toast.LENGTH_LONG)
                                        .show()
                            }
                        } else if (searchShareForMagikeyboard) {
                            FileDatabaseSelectActivity.launchForKeyboardSelectionResult(this,
                                    searchInfo)
                        } else {
                            FileDatabaseSelectActivity.launchForSearchResult(this,
                                    searchInfo)
                        }
                    }
            )
        }
        finish()
    }
}

fun populateKeyboardAndMoveAppToBackground(activity: Activity,
                                           entry: EntryInfo,
                                           intent: Intent,
                                           toast: Boolean = true) {
    // Populate Magikeyboard with entry
    MagikeyboardService.addEntryAndLaunchNotificationIfAllowed(activity, entry, toast)
    // Consume the selection mode
    EntrySelectionHelper.removeModesFromIntent(intent)
    activity.moveTaskToBack(true)
}
