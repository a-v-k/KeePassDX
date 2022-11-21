package com.kunzisoft.keepass.activities.legacy

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import org.digicraft.keepass.R
import com.kunzisoft.keepass.activities.helpers.EntrySelectionHelper
import com.kunzisoft.keepass.activities.helpers.SpecialMode
import com.kunzisoft.keepass.activities.helpers.TypeMode
import com.kunzisoft.keepass.model.SearchInfo
import com.kunzisoft.keepass.settings.PreferencesUtil
import com.kunzisoft.keepass.view.SpecialModeView


/**
 * Activity to manage database special mode (ie: selection mode)
 */
abstract class DatabaseModeActivity : DatabaseActivity() {

    protected var mSpecialMode: SpecialMode = SpecialMode.DEFAULT
    private var mTypeMode: TypeMode = TypeMode.DEFAULT

    private var mSpecialModeView: SpecialModeView? = null

    override fun onBackPressed() {
        if (mSpecialMode != SpecialMode.DEFAULT)
            onCancelSpecialMode()
        else
            super.onBackPressed()
    }

    /**
     * To call the regular onBackPressed() method in special mode
     */
    protected fun onRegularBackPressed() {
        super.onBackPressed()
    }

    /**
     * Intent sender uses special retains data in callback
     */
    private fun isIntentSender(): Boolean {
        return (mSpecialMode == SpecialMode.SELECTION
                && mTypeMode == TypeMode.AUTOFILL)
                /* TODO Registration callback #765
                || (mSpecialMode == SpecialMode.REGISTRATION
                && mTypeMode == TypeMode.AUTOFILL
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                */
    }

    fun onLaunchActivitySpecialMode() {
        if (!isIntentSender()) {
            EntrySelectionHelper.removeModesFromIntent(intent)
            EntrySelectionHelper.removeInfoFromIntent(intent)
            finish()
        }
    }

    open fun onValidateSpecialMode() {
        if (isIntentSender()) {
            super.finish()
        } else {
            EntrySelectionHelper.removeModesFromIntent(intent)
            EntrySelectionHelper.removeInfoFromIntent(intent)
            if (mSpecialMode != SpecialMode.DEFAULT) {
                backToTheMainAppAndFinish()
            }
        }
    }

    open fun onCancelSpecialMode() {
        if (isIntentSender()) {
            // To get the app caller, only for IntentSender
            super.onBackPressed()
        } else {
            EntrySelectionHelper.removeModesFromIntent(intent)
            EntrySelectionHelper.removeInfoFromIntent(intent)
            if (mSpecialMode != SpecialMode.DEFAULT) {
                backToTheMainAppAndFinish()
            }
        }
    }

    protected fun backToTheAppCaller() {
        if (isIntentSender()) {
            // To get the app caller, only for IntentSender
            super.onBackPressed()
        } else {
            backToTheMainAppAndFinish()
        }
    }

    private fun backToTheMainAppAndFinish() {
        // To move the app in background and return to the main app
        moveTaskToBack(true)
        // To remove this instance in the OS app selector
        Handler(Looper.getMainLooper()).postDelayed({
            finish()
        }, 500)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mSpecialMode = EntrySelectionHelper.retrieveSpecialModeFromIntent(intent)
        mTypeMode = EntrySelectionHelper.retrieveTypeModeFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()

        mSpecialMode = EntrySelectionHelper.retrieveSpecialModeFromIntent(intent)
        mTypeMode = EntrySelectionHelper.retrieveTypeModeFromIntent(intent)
        val searchInfo: SearchInfo? = EntrySelectionHelper.retrieveRegisterInfoFromIntent(intent)?.searchInfo
                ?: EntrySelectionHelper.retrieveSearchInfoFromIntent(intent)

        // To show the selection mode
        mSpecialModeView = findViewById(R.id.special_mode_view)
        mSpecialModeView?.apply {
            // Populate title
            val selectionModeStringId = when (mSpecialMode) {
                SpecialMode.DEFAULT, // Not important because hidden
                SpecialMode.SEARCH -> R.string.search_mode
                SpecialMode.SAVE -> R.string.save_mode
                SpecialMode.SELECTION -> R.string.selection_mode
                SpecialMode.REGISTRATION -> R.string.registration_mode
            }
            val typeModeStringId = when (mTypeMode) {
                TypeMode.DEFAULT, // Not important because hidden
                TypeMode.MAGIKEYBOARD -> R.string.magic_keyboard_title
                TypeMode.AUTOFILL -> R.string.autofill
            }
            title = getString(selectionModeStringId)
            if (mTypeMode != TypeMode.DEFAULT)
                title = "$title (${getString(typeModeStringId)})"
            // Populate subtitle
            subtitle = searchInfo?.getName(resources)

            // Show the toolbar or not
            visible = when (mSpecialMode) {
                SpecialMode.DEFAULT -> false
                SpecialMode.SEARCH -> true
                SpecialMode.SAVE -> true
                SpecialMode.SELECTION -> true
                SpecialMode.REGISTRATION -> true
            }

            // Add back listener
            onCancelButtonClickListener = View.OnClickListener {
                onCancelSpecialMode()
            }

            // Create menu
            menu.clear()
            if (mTypeMode == TypeMode.AUTOFILL) {
                menuInflater.inflate(R.menu.autofill, menu)
                setOnMenuItemClickListener {  menuItem ->
                    when (menuItem.itemId) {
                        R.id.menu_block_autofill -> {
                            blockAutofill(searchInfo)
                        }
                    }
                    true
                }
            }
        }

        // To hide home button from the regular toolbar in special mode
        if (mSpecialMode != SpecialMode.DEFAULT
            && hideHomeButtonIfModeIsNotDefault()) {
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
            supportActionBar?.setDisplayShowHomeEnabled(false)
        }
    }

    open fun hideHomeButtonIfModeIsNotDefault(): Boolean {
        return true
    }

    private fun blockAutofill(searchInfo: SearchInfo?) {
        val webDomain = searchInfo?.webDomain
        val applicationId = searchInfo?.applicationId
        if (webDomain != null) {
            PreferencesUtil.addWebDomainToBlocklist(this,
                    webDomain)
        } else if (applicationId != null) {
            PreferencesUtil.addApplicationIdToBlocklist(this,
                    applicationId)
        }
        onCancelSpecialMode()
        Toast.makeText(this.applicationContext,
                R.string.autofill_block_restart,
                Toast.LENGTH_LONG).show()
    }
}