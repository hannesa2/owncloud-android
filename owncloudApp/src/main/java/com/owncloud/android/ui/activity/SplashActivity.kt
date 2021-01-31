/**
 * ownCloud Android client application
 *
 * @author Abel García de Prada
 *
 * Copyright (C) 2020 ownCloud GmbH.
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2,
 * as published by the Free Software Foundation.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.owncloud.android.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.integration.android.IntentIntegrator
import com.owncloud.android.ui.fragment.OCFileListFragment

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intentLaunch = Intent(this, FileDisplayActivity::class.java)

        intent.getStringExtra(OCFileListFragment.SHORTCUT_EXTRA)?.let {
            if (it == "QR") {
                IntentIntegrator(this).initiateScan()
                return
            } else {
                intentLaunch.putExtra(
                    OCFileListFragment.SHORTCUT_EXTRA,
                    intent.getStringExtra(OCFileListFragment.SHORTCUT_EXTRA)
                )
            }
        }
        startActivity(intentLaunch)
        finish()
    }

    private fun displayToast(toast: String) {
        Toast.makeText(this, toast, Toast.LENGTH_LONG).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents == null) {
                displayToast("Cancelled from fragment")
            } else {
                var url = result.contents
                if (!result.contents.startsWith("http://") && !result.contents.startsWith("https://"))
                    url = "http://" + result.contents

                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(browserIntent)

                displayToast(result.contents)
            }
        }
        finish()
    }
}
