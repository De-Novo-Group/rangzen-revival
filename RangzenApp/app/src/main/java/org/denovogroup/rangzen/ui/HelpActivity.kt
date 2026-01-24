/*
 * Copyright (c) 2026, De Novo Group
 * Help Activity - displays HTML documentation in a WebView
 */
package org.denovogroup.rangzen.ui

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import org.denovogroup.rangzen.R
import timber.log.Timber
import java.util.Locale

/**
 * Activity to display HTML help documents.
 * Supports both English and Farsi (RTL) content.
 * 
 * Usage: Start with intent extra "doc_type" = "tutorial" | "faq" | "testers_guide"
 */
class HelpActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DOC_TYPE = "doc_type"
        const val DOC_TUTORIAL = "tutorial"
        const val DOC_FAQ = "faq"
        const val DOC_TESTERS_GUIDE = "testers_guide"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)

        // Handle edge-to-edge display
        val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        windowInsetsController.isAppearanceLightStatusBars = false  // Light icons on dark bg
        
        val rootView = findViewById<android.view.View>(R.id.help_root)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = insets.top, bottom = insets.bottom)
            windowInsets
        }

        // Setup back button
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener {
            finish()
        }

        // Get document type from intent
        val docType = intent.getStringExtra(EXTRA_DOC_TYPE) ?: DOC_TUTORIAL
        
        // Determine language based on system locale
        val lang = if (Locale.getDefault().language == "fa") "fa" else "en"
        
        // Build asset path
        val assetPath = "file:///android_asset/docs/${docType}_${lang}.html"
        Timber.d("Loading help document: $assetPath")

        // Setup WebView
        val webView = findViewById<WebView>(R.id.webview_help)
        webView.apply {
            settings.javaScriptEnabled = true  // For FAQ accordions
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()  // Handle links internally
            loadUrl(assetPath)
        }
    }
}
