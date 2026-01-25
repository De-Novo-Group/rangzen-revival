/*
 * Copyright (c) 2026, De Novo Group
 * Portrait-locked QR scanner activity
 */
package org.denovogroup.rangzen.ui

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * QR scanner activity locked to portrait orientation.
 * Orientation is set via android:screenOrientation in AndroidManifest.xml
 */
class PortraitCaptureActivity : CaptureActivity()
