package com.kunzisoft.keepass.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.kunzisoft.keepass.activities.legacy.DatabaseModeActivity
import com.kunzisoft.keepass.databinding.ActivityContributorBinding
import com.kunzisoft.keepass.utils.UriUtil


/**
 * Checkout implementation for the app
 */
class ContributorActivity : DatabaseModeActivity() {

    private lateinit var layoutBinding: ActivityContributorBinding

    /**
     * Initialize the Google Pay API on creation of the activity
     *
     * @see AppCompatActivity.onCreate
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Use view binding to access the UI elements
        layoutBinding = ActivityContributorBinding.inflate(layoutInflater)
        setContentView(layoutBinding.root)

        layoutBinding.keepassdxButton.setOnClickListener {
            UriUtil.openExternalApp(this, "com.kunzisoft.keepass.free")
        }

        layoutBinding.filesyncButton.setOnClickListener {
            // TODO
        }
        layoutBinding.filesyncSoon.setOnClickListener {
            UriUtil.gotoUrl(this,"https://github.com/Kunzisoft/FileSync")
        }
    }
}