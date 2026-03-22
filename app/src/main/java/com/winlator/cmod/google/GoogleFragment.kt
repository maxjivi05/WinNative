package com.winlator.cmod.google

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment

class GoogleFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                GoogleScreen()
            }
        }
    }

    fun onSavedGamesPermissionResult(resultCode: Int, data: Intent?) {
        val currentActivity = activity ?: return
        CloudSyncManager.onSavedGamesPermissionResult(currentActivity)
    }
}
